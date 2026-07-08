package com.locapeer.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.keyStore by preferencesDataStore(name = "locapeer_keys")
private const val TAG = "KeyManager"
private const val KEYSTORE_ALIAS = "locapeer_private_key"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val AES_GCM = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128
private const val IV_LENGTH = 12

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
    val publicKeyHexFlow: Flow<String?> = _publicKeyFlow

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
                try {
                    val privHex = decrypt(encryptedPriv)
                    if (privHex.length == 64 && pubHex.length == 64) return@withContext privHex to pubHex
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decrypt stored key, will regenerate", e)
                }
            }

            Log.w(TAG, "No valid keypair found - generating new one")
            generateAndSaveKeypair()
        }
    }

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
        try { context.keyStore.data.first()[KEY_PUBLIC_METADATA] }
        catch (e: Exception) { Log.e(TAG, "Failed to get public key", e); null }
    }

    suspend fun getPrivateKeyHex(): String? = withContext(Dispatchers.IO) {
        try {
            val enc = context.keyStore.data.first()[KEY_PRIVATE_ENCRYPTED] ?: return@withContext null
            decrypt(enc)
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
}
