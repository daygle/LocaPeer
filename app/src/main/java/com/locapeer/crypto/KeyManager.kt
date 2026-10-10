package com.locapeer.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.keyStore by preferencesDataStore(name = "locapeer_keys")
private const val TAG = "KeyManager"
private const val KEYSTORE_ALIAS = "locapeer_private_key"
// v2: per-use key that a device PIN/pattern/password can also authorise (API 30+). The v1
// key was biometric-only, so a credential unlock could never use it; it is deleted on sight.
private const val APPLOCK_KEYSTORE_ALIAS = "locapeer_applock_key_v2"
private const val APPLOCK_KEYSTORE_ALIAS_V1 = "locapeer_applock_key"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val AES_GCM = "AES/GCM/NoPadding"
private val APPLOCK_CHALLENGE = "locapeer-app-lock".toByteArray()
private const val GCM_TAG_BITS = 128
private const val IV_LENGTH = 12
private const val KEY_DECRYPT_ATTEMPTS = 3
private const val KEY_DECRYPT_RETRY_MS = 250L

@Singleton
class KeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: CryptoUtils
) {
    private val KEY_PRIVATE_ENCRYPTED = stringPreferencesKey("private_key_encrypted")
    private val KEY_PUBLIC_METADATA = stringPreferencesKey("public_key_hex")

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _publicKeyFlow = MutableStateFlow<String?>(null)
    val publicKeyHexFlow: StateFlow<String?> = _publicKeyFlow.asStateFlow()

    init {
        scope.launch {
            try {
                _publicKeyFlow.value = context.keyStore.data.first()[KEY_PUBLIC_METADATA]
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load public key from DataStore", e)
            }
        }
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGen.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGen.generateKey()
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKeystoreKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, IV_LENGTH)
        val ciphertext = combined.copyOfRange(IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKeystoreKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    suspend fun ensureKeypair(): Pair<String, String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val prefs = context.keyStore.data.first()
            val encryptedPriv = prefs[KEY_PRIVATE_ENCRYPTED]
            val pubHex = prefs[KEY_PUBLIC_METADATA]

            if (encryptedPriv != null && pubHex != null) {
                val privHex = decryptStoredKey(encryptedPriv)
                if (privHex != null && privHex.length == 64 && pubHex.length == 64) {
                    return@withContext privHex to pubHex
                }
            }

            Log.w(TAG, "No valid keypair found - generating new one")
            generateAndSaveKeypair()
        }
    }

    /**
     * Decrypts the stored identity blob. Returns null only when the blob is provably
     * unrecoverable (the wrapping Keystore key is gone or the ciphertext fails
     * authentication), in which case the caller regenerates. Any other failure - the
     * Keystore daemon being briefly unavailable after boot, a binder hiccup - is retried
     * and then rethrown: silently replacing the identity on a transient error would
     * permanently orphan every contact relationship built on the old key.
     */
    private suspend fun decryptStoredKey(encoded: String): String? {
        var lastError: Exception? = null
        repeat(KEY_DECRYPT_ATTEMPTS) { attempt ->
            try {
                return decrypt(encoded)
            } catch (e: Exception) {
                if (isPermanentKeyFailure(e)) {
                    Log.e(TAG, "Stored key is unrecoverable, will regenerate", e)
                    return null
                }
                lastError = e
                Log.w(TAG, "Transient failure decrypting stored key (attempt ${attempt + 1})", e)
                delay(KEY_DECRYPT_RETRY_MS * (attempt + 1))
            }
        }
        throw IllegalStateException("Keystore unavailable; refusing to replace identity", lastError)
    }

    private fun isPermanentKeyFailure(e: Exception): Boolean =
        e is AEADBadTagException ||
            e is UnrecoverableKeyException ||
            e is KeyPermanentlyInvalidatedException ||
            e is IllegalArgumentException || // malformed Base64
            e is IndexOutOfBoundsException // blob shorter than the IV

    private suspend fun saveKeypair(privHex: String, pubHex: String) {
        context.keyStore.edit { prefs ->
            prefs[KEY_PRIVATE_ENCRYPTED] = encrypt(privHex)
            prefs[KEY_PUBLIC_METADATA] = pubHex
        }
        _publicKeyFlow.value = pubHex
    }

    private suspend fun generateAndSaveKeypair(): Pair<String, String> = withContext(Dispatchers.IO) {
        val privBytes = crypto.generatePrivateKey()
        val privHex = crypto.bytesToHex(privBytes)
        val pubHex = crypto.bytesToHex(crypto.getXOnlyPublicKey(privBytes))
        saveKeypair(privHex, pubHex)
        privHex to pubHex
    }

    suspend fun getPublicKeyHex(): String? = withContext(Dispatchers.IO) {
        _publicKeyFlow.value ?: try { context.keyStore.data.first()[KEY_PUBLIC_METADATA] }
        catch (e: Exception) { Log.e(TAG, "Failed to get public key", e); null }
    }

    suspend fun getPrivateKeyHex(): String? = withContext(Dispatchers.IO) {
        try {
            val enc = context.keyStore.data.first()[KEY_PRIVATE_ENCRYPTED] ?: return@withContext null
            decryptStoredKey(enc)
        } catch (e: Exception) { Log.e(TAG, "Failed to read private key", e); null }
    }

    suspend fun exportPrivateKeyHex(): String = ensureKeypair().first

    suspend fun importPrivateKey(privHex: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val normalizedPriv = crypto.normalizePrivateKey(crypto.hexToBytes(privHex))
            val normalizedHex = crypto.bytesToHex(normalizedPriv)
            val pubHex = crypto.bytesToHex(crypto.getXOnlyPublicKey(normalizedPriv))
            saveKeypair(normalizedHex, pubHex)
        }
    }

    /**
     * Returns a [androidx.biometric.BiometricPrompt.CryptoObject] for the app lock (API 30+).
     * Its cipher only works after the user authenticates for this one operation, so the
     * unlock is proven by using it ([verifyAppLockCryptoObject]), not by the prompt callback.
     */
    @androidx.annotation.RequiresApi(30)
    fun getAppLockCryptoObject(): androidx.biometric.BiometricPrompt.CryptoObject? {
        return try {
            val cipher = Cipher.getInstance(AES_GCM)
            try {
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAppLockKey())
            } catch (e: KeyPermanentlyInvalidatedException) {
                // A biometric was added or the screen lock removed since the key was made.
                // Replace it: the new key still needs a fresh authentication before it works,
                // so this only recovers from an otherwise permanent lockout.
                Log.w(TAG, "App lock key invalidated; generating a new one", e)
                deleteAppLockKey(APPLOCK_KEYSTORE_ALIAS)
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAppLockKey())
            }
            androidx.biometric.BiometricPrompt.CryptoObject(cipher)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create app lock CryptoObject", e)
            null
        }
    }

    /**
     * True only if [cryptoObject] (from a successful prompt's result) holds a cipher the
     * Keystore lets us use, i.e. the user really authenticated for it. A hooked or spoofed
     * success callback without that authentication fails here.
     */
    fun verifyAppLockCryptoObject(cryptoObject: androidx.biometric.BiometricPrompt.CryptoObject?): Boolean {
        val cipher = cryptoObject?.cipher ?: return false
        return try {
            cipher.doFinal(APPLOCK_CHALLENGE).isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "App lock cipher rejected after authentication", e)
            false
        }
    }

    @androidx.annotation.RequiresApi(30)
    private fun getOrCreateAppLockKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
        if (ks.containsAlias(APPLOCK_KEYSTORE_ALIAS_V1)) ks.deleteEntry(APPLOCK_KEYSTORE_ALIAS_V1)
        (ks.getKey(APPLOCK_KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            APPLOCK_KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            // Timeout 0 = authenticate for every use; accept the same authenticators the
            // prompt offers, so a PIN/pattern/password unlock can use the key too.
            .setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    private fun deleteAppLockKey(alias: String) {
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }.deleteEntry(alias)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete app lock key $alias", e)
        }
    }
}
