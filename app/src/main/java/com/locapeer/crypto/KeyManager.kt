package com.locapeer.crypto

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.keyStore by preferencesDataStore(name = "locapeer_keys")
private const val TAG = "KeyManager"

@Singleton
class KeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: CryptoUtils
) {
    private val KEY_PRIVATE = "private_key_hex"
    private val KEY_PUBLIC_METADATA = stringPreferencesKey("public_key_hex")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val encryptedPrefs by lazy {
        try {
            EncryptedSharedPreferences.create(
                context,
                "locapeer_secure_keys",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create EncryptedSharedPreferences", e)
            context.deleteSharedPreferences("locapeer_secure_keys")
            EncryptedSharedPreferences.create(
                context,
                "locapeer_secure_keys",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

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

    suspend fun ensureKeypair(): Pair<String, String> = withContext(Dispatchers.IO) {
        val privHex = try {
            encryptedPrefs.getString(KEY_PRIVATE, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read private key", e)
            null
        }
        val pubHex = context.keyStore.data.first()[KEY_PUBLIC_METADATA]
        if (privHex != null && pubHex != null) {
            return@withContext Pair(privHex, pubHex)
        }
        generateAndSaveKeypair()
    }

    private suspend fun generateAndSaveKeypair(): Pair<String, String> = withContext(Dispatchers.IO) {
        val privBytes = crypto.generatePrivateKey()
        val xOnlyBytes = crypto.getXOnlyPublicKey(privBytes)
        val privHex = crypto.bytesToHex(privBytes)
        val pubHex = crypto.bytesToHex(xOnlyBytes)

        try {
            encryptedPrefs.edit().putString(KEY_PRIVATE, privHex).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save private key", e)
        }

        context.keyStore.edit { prefs ->
            prefs[KEY_PUBLIC_METADATA] = pubHex
        }
        _publicKeyFlow.value = pubHex
        privHex to pubHex
    }

    suspend fun getPublicKeyHex(): String? = withContext(Dispatchers.IO) {
        try {
            context.keyStore.data.first()[KEY_PUBLIC_METADATA]
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get public key", e)
            null
        }
    }

    suspend fun getPrivateKeyHex(): String? = withContext(Dispatchers.IO) {
        try {
            encryptedPrefs.getString(KEY_PRIVATE, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read private key", e)
            null
        }
    }

    suspend fun exportPrivateKeyHex(): String = ensureKeypair().first

    suspend fun importPrivateKey(privHex: String) = withContext(Dispatchers.IO) {
        val privBytes = crypto.hexToBytes(privHex)
        val xOnlyBytes = crypto.getXOnlyPublicKey(privBytes)
        val pubHex = crypto.bytesToHex(xOnlyBytes)

        try {
            encryptedPrefs.edit().putString(KEY_PRIVATE, privHex).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import private key", e)
        }

        context.keyStore.edit { prefs ->
            prefs[KEY_PUBLIC_METADATA] = pubHex
        }
        _publicKeyFlow.value = pubHex
    }
}
