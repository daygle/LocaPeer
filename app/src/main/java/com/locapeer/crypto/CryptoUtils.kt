package com.locapeer.crypto

import java.util.Base64
import fr.acinq.secp256k1.Secp256k1
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CryptoUtils @Inject constructor() {

    private val random = SecureRandom()
    private val CURVE_N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

    fun generatePrivateKey(): ByteArray {
        val key = ByteArray(32)
        while (true) {
            random.nextBytes(key)
            val bi = BigInteger(1, key)
            if (bi > BigInteger.ZERO && bi < CURVE_N) break
        }
        return normalizePrivateKey(key)
    }

    /** True iff [privKey] is a 32-byte scalar in the valid secp256k1 range (0, CURVE_N). */
    fun isValidPrivateKey(privKey: ByteArray): Boolean {
        if (privKey.size != 32) return false
        val bi = BigInteger(1, privKey)
        return bi > BigInteger.ZERO && bi < CURVE_N
    }

    /**
     * Normalizes a private key so that its public key has an even Y coordinate,
     * as required by BIP-340.
     */
    fun normalizePrivateKey(privKey: ByteArray): ByteArray {
        require(isValidPrivateKey(privKey)) { "Private key must be a 32-byte value in (0, curve order)" }
        val pub = Secp256k1.pubkeyCreate(privKey)
        // pub[0] is 0x02 (even), 0x03 (odd), or 0x04 (uncompressed)
        val isEven = if (pub.size == 33) {
            pub[0] == 0x02.toByte()
        } else {
            pub[64] % 2 == 0
        }
        
        return if (isEven) {
            privKey
        } else {
            val bi = BigInteger(1, privKey)
            val normalized = CURVE_N.subtract(bi)
            val bytes = normalized.toByteArray()
            if (bytes.size > 32) {
                bytes.copyOfRange(bytes.size - 32, bytes.size)
            } else if (bytes.size < 32) {
                ByteArray(32 - bytes.size) + bytes
            } else {
                bytes
            }
        }
    }

    /** Returns the 32-byte x-only public key (Nostr/BIP-340 format). */
    fun getXOnlyPublicKey(privKey: ByteArray): ByteArray {
        val pub = Secp256k1.pubkeyCreate(privKey)
        // pubkeyCreate returns 65-byte uncompressed (0x04 || x(32) || y(32))
        // or 33-byte compressed (0x02/03 || x(32)). Either way bytes [1..32] = x.
        return pub.copyOfRange(1, 33)
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

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    fun hexToBytes(hex: String): ByteArray {
        require((hex.length % 2) == 0) { "Hex string must have even length" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, (i * 2) + 2).toInt(16).toByte()
        }
    }

    /** NIP-44 v2: Encrypts plaintext for a recipient. */
    fun nip44Encrypt(senderPrivKey: ByteArray, recipientXOnlyHex: String, plaintext: String): String {
        val conversationKey = getConversationKey(senderPrivKey, recipientXOnlyHex)
        val nonce = ByteArray(32).also { random.nextBytes(it) }
        val (chachaKey, chachaNonce, hmacKey) = deriveNip44Keys(conversationKey, nonce)

        val padded = nip44Pad(plaintext)
        val chacha = ChaCha7539Engine()
        chacha.init(true, ParametersWithIV(KeyParameter(chachaKey), chachaNonce))
        val ciphertext = ByteArray(padded.size)
        chacha.processBytes(padded, 0, padded.size, ciphertext, 0)

        val hmac = HMac(SHA256Digest())
        hmac.init(KeyParameter(hmacKey))
        hmac.update(nonce, 0, nonce.size)
        hmac.update(ciphertext, 0, ciphertext.size)
        val mac = ByteArray(32)
        hmac.doFinal(mac, 0)

        val payload = ByteBuffer.allocate(1 + 32 + ciphertext.size + 32)
            .put(0x02.toByte())
            .put(nonce)
            .put(ciphertext)
            .put(mac)
            .array()

        return Base64.getEncoder().encodeToString(payload)
    }

    /** NIP-44 v2: Decrypts a base64 payload. */
    fun nip44Decrypt(recipientPrivKey: ByteArray, senderXOnlyHex: String, payloadB64: String): String {
        val payload = try {
            Base64.getDecoder().decode(payloadB64)
        } catch (_: Exception) {
            throw SecurityException("Invalid base64 payload")
        }
        require(payload.size >= 1 + 32 + 32) { "Invalid NIP-44 payload size" }
        require(payload[0] == 0x02.toByte()) { "Unsupported NIP-44 version" }

        val nonce = payload.copyOfRange(1, 33)
        val mac = payload.copyOfRange(payload.size - 32, payload.size)
        val ciphertext = payload.copyOfRange(33, payload.size - 32)

        val conversationKey = getConversationKey(recipientPrivKey, senderXOnlyHex)
        val (chachaKey, chachaNonce, hmacKey) = deriveNip44Keys(conversationKey, nonce)

        // Verify MAC: HMAC-SHA256(hmac_key, nonce + ciphertext)
        val hmac = HMac(SHA256Digest())
        hmac.init(KeyParameter(hmacKey))
        hmac.update(nonce, 0, nonce.size)
        hmac.update(ciphertext, 0, ciphertext.size)
        val computedMac = ByteArray(32)
        hmac.doFinal(computedMac, 0)
        if (!MessageDigest.isEqual(mac, computedMac)) throw SecurityException("NIP-44 MAC mismatch")

        val chacha = ChaCha7539Engine()
        chacha.init(false, ParametersWithIV(KeyParameter(chachaKey), chachaNonce))
        val padded = ByteArray(ciphertext.size)
        chacha.processBytes(ciphertext, 0, ciphertext.size, padded, 0)

        return nip44Unpad(padded)
    }

    private fun getConversationKey(privKey: ByteArray, pubKeyHex: String): ByteArray {
        val recipientCompressed = xOnlyHexToCompressed(pubKeyHex)
        // sharedPoint = privKey * pubKey
        // We use pubKeyTweakMul(pubKey, privKey) to get the shared point P = privKey * PubKey.
        val sharedPoint = Secp256k1.pubKeyTweakMul(recipientCompressed, privKey)
        // x-coordinate is bytes 1..32 (both for compressed and uncompressed results)
        val sharedX = sharedPoint.copyOfRange(1, 33)

        // conversation_key = HKDF-Extract(salt="nip44-v2", IKM=shared_x)
        // HKDF-Extract(salt, IKM) is HMAC-SHA256(salt, IKM)
        val hmac = HMac(SHA256Digest())
        hmac.init(KeyParameter("nip44-v2".toByteArray(StandardCharsets.UTF_8)))
        hmac.update(sharedX, 0, sharedX.size)
        val conversationKey = ByteArray(32)
        hmac.doFinal(conversationKey, 0)
        return conversationKey
    }

    private fun deriveNip44Keys(conversationKey: ByteArray, nonce: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        // HKDF-Expand(PRK=conversation_key, info=nonce, L=76)
        hkdf.init(HKDFParameters.skipExtractParameters(conversationKey, nonce))
        val okm = ByteArray(76)
        hkdf.generateBytes(okm, 0, 76)

        return Triple(
            okm.copyOfRange(0, 32),  // ChaCha Key
            okm.copyOfRange(32, 44), // ChaCha Nonce (12 bytes)
            okm.copyOfRange(44, 76), // HMAC Key
        )
    }

    private fun nip44Pad(plaintext: String): ByteArray {
        val data = plaintext.toByteArray(StandardCharsets.UTF_8)
        val len = data.size
        // NIP-44 v2 supports up to u32 max length
        require(len > 0) { "Plaintext cannot be empty" }

        val isExtended = len >= 65535
        val headerSize = if (isExtended) 6 else 2
        val out = ByteBuffer.allocate(headerSize + len)
        
        if (isExtended) {
            out.putShort(0)
            out.putInt(len)
        } else {
            out.putShort(len.toShort())
        }
        out.put(data)
        
        val paddedLen = headerSize + calcNip44Padding(len)
        return out.array().copyOf(paddedLen)
    }

    private fun nip44Unpad(padded: ByteArray): String {
        if (padded.size < 2) throw SecurityException("Invalid padded data")
        val buffer = ByteBuffer.wrap(padded)
        val firstShort = buffer.short.toInt() and 0xFFFF
        
        val (len, headerSize) = if (firstShort == 0) {
            if (padded.size < 6) throw SecurityException("Invalid extended padded data")
            val extendedLen = buffer.int.toLong() and 0xFFFFFFFFL
            extendedLen.toInt() to 6
        } else {
            firstShort to 2
        }

        if (len > padded.size - headerSize) throw SecurityException("Invalid padding length")
        val data = ByteArray(len)
        buffer[data]
        return String(data, StandardCharsets.UTF_8)
    }

    /** Official NIP-44 padding-bucket algorithm: rounds the plaintext length up to the
     *  spec-defined chunk boundary so ciphertext length only reveals a coarse size bucket. */
    private fun calcNip44Padding(len: Int): Int {
        if (len <= 32) return 32
        val n = len - 1
        val floorLog2 = 31 - Integer.numberOfLeadingZeros(n)
        val nextPower = 1 shl (floorLog2 + 1)
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * (n / chunk + 1)
    }

    /** Backup encryption: Derives a 256-bit AES key from a password using PBKDF2-HMAC-SHA256. */
    fun deriveBackupKey(password: String, salt: ByteArray): ByteArray {
        val iterations = 600_000 // OWASP 2023 recommendation for SHA-256
        val keyLength = 256
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, iterations, keyLength)
        return factory.generateSecret(spec).encoded
    }

    /** Encrypts data for backup using AES-256-GCM. */
    fun aesEncrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, iv))
        return cipher.doFinal(data)
    }

    /** Decrypts data from backup using AES-256-GCM. */
    fun aesDecrypt(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }
}
