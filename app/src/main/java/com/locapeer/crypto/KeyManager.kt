package com.locapeer.crypto

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
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
    private val KEY_PRIVATE = stringPreferencesKey("private_key_hex")
    private val KEY_PUBLIC = stringPreferencesKey("public_key_hex")

    val privateKeyHexFlow: Flow<String?> = context.keyStore.data.map { it[KEY_PRIVATE] }
    val publicKeyHexFlow: Flow<String?> = context.keyStore.data.map { it[KEY_PUBLIC] }

    suspend fun ensureKeypair(): Pair<String, String> {
        val existing = context.keyStore.data.first()
        val privHex = existing[KEY_PRIVATE]
        val pubHex = existing[KEY_PUBLIC]
        if (privHex != null && pubHex != null) return privHex to pubHex
        return generateAndSaveKeypair()
    }

    private suspend fun generateAndSaveKeypair(): Pair<String, String> {
        val privBytes = crypto.generatePrivateKey()
        val xOnlyBytes = crypto.getXOnlyPublicKey(privBytes)
        val privHex = crypto.bytesToHex(privBytes)
        val pubHex = crypto.bytesToHex(xOnlyBytes)
        context.keyStore.edit { prefs ->
            prefs[KEY_PRIVATE] = privHex
            prefs[KEY_PUBLIC] = pubHex
        }
        return privHex to pubHex
    }

    fun getPrivateKeyHexBlocking(): String? = runBlocking {
        context.keyStore.data.first()[KEY_PRIVATE]
    }

    fun getPublicKeyHexBlocking(): String? = runBlocking {
        context.keyStore.data.first()[KEY_PUBLIC]
    }

    suspend fun exportPrivateKeyHex(): String = ensureKeypair().first

    /** Imports a keypair from a backed-up private key hex string. */
    suspend fun importPrivateKey(privHex: String) {
        val privBytes = crypto.hexToBytes(privHex)
        val xOnlyBytes = crypto.getXOnlyPublicKey(privBytes)
        val pubHex = crypto.bytesToHex(xOnlyBytes)
        context.keyStore.edit { prefs ->
            prefs[KEY_PRIVATE] = privHex
            prefs[KEY_PUBLIC] = pubHex
        }
    }
}
