package com.locapeer.crypto

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.keyStore by preferencesDataStore(name = "locapeer_keys")

@Singleton
class KeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: CryptoUtils
) {
    private val KEY_PRIVATE = "private_key_hex"
    private val KEY_PUBLIC_METADATA = stringPreferencesKey("public_key_hex")

    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    private val encryptedPrefs = EncryptedSharedPreferences.create(
        "locapeer_secure_keys",
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _publicKeyFlow = MutableStateFlow<String?>(null)
    val publicKeyHexFlow: Flow<String?> = _publicKeyFlow

    init {
        // Sync the public key from metadata DataStore to the flow
        runBlocking {
            _publicKeyFlow.value = context.keyStore.data.first()[KEY_PUBLIC_METADATA]
        }
    }

    suspend fun ensureKeypair(): Pair<String, String> {
        val privHex = encryptedPrefs.getString(KEY_PRIVATE, null)
        val pubHex = context.keyStore.data.first()[KEY_PUBLIC_METADATA]
        if (privHex != null && pubHex != null) {
            return Pair(privHex, pubHex)
        }
        return generateAndSaveKeypair()
    }

    private suspend fun generateAndSaveKeypair(): Pair<String, String> {
        val privBytes = crypto.generatePrivateKey()
        val xOnlyBytes = crypto.getXOnlyPublicKey(privBytes)
        val privHex = crypto.bytesToHex(privBytes)
        val pubHex = crypto.bytesToHex(xOnlyBytes)

        encryptedPrefs.edit().putString(KEY_PRIVATE, privHex).apply()
        context.keyStore.edit { prefs ->
            prefs[KEY_PUBLIC_METADATA] = pubHex
        }
        _publicKeyFlow.value = pubHex
        return privHex to pubHex
    }

    fun getPrivateKeyHexBlocking(): String? {
        return encryptedPrefs.getString(KEY_PRIVATE, null)
    }

    fun getPublicKeyHexBlocking(): String? = runBlocking {
        context.keyStore.data.first()[KEY_PUBLIC_METADATA]
    }

    suspend fun exportPrivateKeyHex(): String = ensureKeypair().first

    /** Imports a keypair from a backed-up private key hex string. */
    suspend fun importPrivateKey(privHex: String) {
        val privBytes = crypto.hexToBytes(privHex)
        val xOnlyBytes = crypto.getXOnlyPublicKey(privBytes)
        val pubHex = crypto.bytesToHex(xOnlyBytes)

        encryptedPrefs.edit().putString(KEY_PRIVATE, privHex).apply()
        context.keyStore.edit { prefs ->
            prefs[KEY_PUBLIC_METADATA] = pubHex
        }
        _publicKeyFlow.value = pubHex
    }
}
