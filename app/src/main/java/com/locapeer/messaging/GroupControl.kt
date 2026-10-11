package com.locapeer.messaging

import com.locapeer.crypto.CryptoUtils
import java.nio.charset.StandardCharsets

/**
 * Creator-signed circle state ("group control"). The creator signs each version of a circle's
 * name and membership; the signature travels in every group message ([GroupMessage.cver] /
 * [GroupMessage.csig]) and any member may relay it. A recipient can therefore check who the
 * creator is even when its first message for a circle comes from another member (no trust on
 * first use), and applies only a higher version than it holds, so delayed or replayed messages
 * can't roll the circle back.
 *
 * Both fields are optional on the wire: older app versions ignore them, and circles that have
 * never carried a signature keep the earlier creator-authority rules.
 */
object GroupControl {

    /** Domain tag so this signature can never be confused with a Nostr event id signature. */
    private const val DOMAIN = "LPGC1"

    /**
     * The exact bytes signed: domain, circle id, creator, version, the sorted member set, then the
     * name last (the only free-text field, so a newline in it can't be mistaken for a separator).
     */
    internal fun signedPayload(gid: String, creator: String, version: Long, members: Collection<String>, name: String): ByteArray =
        listOf(DOMAIN, gid, creator, version.toString(), members.toSortedSet().joinToString(","), name)
            .joinToString("\n")
            .toByteArray(StandardCharsets.UTF_8)

    fun sign(crypto: CryptoUtils, creatorPriv: ByteArray, gid: String, creator: String, version: Long, members: Collection<String>, name: String): String =
        crypto.bytesToHex(crypto.schnorrSign(crypto.sha256(signedPayload(gid, creator, version, members, name)), creatorPriv))

    fun verify(crypto: CryptoUtils, sigHex: String, gid: String, creator: String, version: Long, members: Collection<String>, name: String): Boolean {
        if (version <= 0 || sigHex.length != 128 || creator.length != 64) return false
        return try {
            crypto.schnorrVerify(
                crypto.hexToBytes(sigHex),
                crypto.sha256(signedPayload(gid, creator, version, members, name)),
                crypto.hexToBytes(creator)
            )
        } catch (_: Exception) {
            false
        }
    }

    /** What a received group message may do to an already-known circle's name and membership. */
    enum class Update { APPLY_SIGNED, APPLY_UNSIGNED, IGNORE }

    /**
     * Decides whether a message updates a known circle. [signedVersion] is the message's verified
     * version (0 when unsigned). A signed update applies when its creator matches the recorded one
     * (or none is recorded yet) and its version is newer. A creator recorded only from another
     * member's unsigned claim ([knownCreatorConfirmed] false: never heard from that creator, and
     * not us) is unverified, so a signature, which proves the creator, replaces it; otherwise
     * the first contact to name a fake creator could freeze the real creator out for good. Once a circle holds a signed version,
     * unsigned updates are ignored; before that, the earlier rules apply: only the recorded creator
     * (or the sender, for circles that never recorded one) and never older than the last applied.
     */
    fun decide(
        knownCreator: String,
        knownVersion: Long,
        knownUpdatedAt: Long,
        knownCreatorConfirmed: Boolean,
        senderPubkey: String,
        claimedCreator: String,
        signedVersion: Long,
        eventCreatedAt: Long,
    ): Update {
        if (signedVersion > 0) {
            val creatorMatches = knownCreator.isBlank() || knownCreator == claimedCreator ||
                (knownVersion == 0L && !knownCreatorConfirmed)
            return if (creatorMatches && signedVersion > knownVersion) Update.APPLY_SIGNED else Update.IGNORE
        }
        if (knownVersion > 0) return Update.IGNORE
        val authority = knownCreator.ifBlank { senderPubkey }
        return if (senderPubkey == authority && eventCreatedAt >= knownUpdatedAt) Update.APPLY_UNSIGNED else Update.IGNORE
    }
}
