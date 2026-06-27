package com.locapeer.crypto

import java.util.Base64
import fr.acinq.secp256k1.Secp256k1
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CryptoUtils @Inject constructor() {

    private val random = SecureRandom()

    fun generatePrivateKey(): ByteArray {
        val key = ByteArray(32)
        random.nextBytes(key)
        return key
    }

    fun getPublicKeyCompressed(privKey: ByteArray): ByteArray =
        Secp256k1.pubkeyCreate(privKey)

    /** Returns the 32-byte x-only public key (Nostr/BIP-340 format). */
    fun getXOnlyPublicKey(privKey: ByteArray): ByteArray {
        val compressed = Secp256k1.pubkeyCreate(privKey)
        return compressed.drop(1).toByteArray()
    }

    /** Parses a 64-char hex public key into 33-byte compressed form (prepend 0x02). */
    fun xOnlyHexToCompressed(xOnlyHex: String): ByteArray {
        val x = hexToBytes(xOnlyHex)
        return byteArrayOf(0x02) + x
    }

    /** Signs a 32-byte message hash with BIP-340 Schnorr. */
    fun schnorrSign(msg32: ByteArray, privKey: ByteArray): ByteArray =
        Secp256k1.signSchnorr(msg32, privKey, null)

    /** Verifies a BIP-340 Schnorr signature. xOnlyPubKey is 32 bytes. */
    fun schnorrVerify(sig64: ByteArray, msg32: ByteArray, xOnlyPubKey: ByteArray): Boolean =
        Secp256k1.verifySchnorr(sig64, msg32, xOnlyPubKey)

    /** NIP-04: Encrypts plaintext for a recipient. Returns "base64?iv=base64". */
    fun nip04Encrypt(senderPrivKey: ByteArray, recipientXOnlyHex: String, plaintext: String): String {
        val recipientCompressed = xOnlyHexToCompressed(recipientXOnlyHex)
        val sharedPoint = Secp256k1.ecdh(senderPrivKey, recipientCompressed)
        val sharedSecret = sharedPoint.copyOf(32)

        val iv = ByteArray(16).also { random.nextBytes(it) }
        val ciphertext = aesCbcEncrypt(sharedSecret, iv, plaintext.toByteArray(StandardCharsets.UTF_8))

        val b64Cipher = Base64.getEncoder().encodeToString(ciphertext)
        val b64Iv = Base64.getEncoder().encodeToString(iv)
        return "$b64Cipher?iv=$b64Iv"
    }

    /** NIP-04: Decrypts a "base64?iv=base64" payload. */
    fun nip04Decrypt(recipientPrivKey: ByteArray, senderXOnlyHex: String, payload: String): String {
        val parts = payload.split("?iv=")
        require(parts.size == 2) { "Invalid NIP-04 payload" }
        val ciphertext = Base64.getDecoder().decode(parts[0])
        val iv = Base64.getDecoder().decode(parts[1])

        val senderCompressed = xOnlyHexToCompressed(senderXOnlyHex)
        val sharedPoint = Secp256k1.ecdh(recipientPrivKey, senderCompressed)
        val sharedSecret = sharedPoint.copyOf(32)

        val plain = aesCbcDecrypt(sharedSecret, iv, ciphertext)
        return String(plain, StandardCharsets.UTF_8)
    }

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = PaddedBufferedBlockCipher(CBCBlockCipher(AESEngine()))
        cipher.init(true, ParametersWithIV(KeyParameter(key), iv))
        val out = ByteArray(cipher.getOutputSize(data.size))
        val len = cipher.processBytes(data, 0, data.size, out, 0)
        val finalLen = cipher.doFinal(out, len)
        return out.copyOf(len + finalLen)
    }

    private fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = PaddedBufferedBlockCipher(CBCBlockCipher(AESEngine()))
        cipher.init(false, ParametersWithIV(KeyParameter(key), iv))
        val out = ByteArray(cipher.getOutputSize(data.size))
        val len = cipher.processBytes(data, 0, data.size, out, 0)
        val finalLen = cipher.doFinal(out, len)
        return out.copyOf(len + finalLen)
    }
}
