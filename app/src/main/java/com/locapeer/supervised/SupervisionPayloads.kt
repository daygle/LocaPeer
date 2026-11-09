package com.locapeer.supervised

import kotlinx.serialization.Serializable

@Serializable
data class UnlockRequestPayload(val requestId: String, val deviceName: String)

@Serializable
data class UnlockResponsePayload(val requestId: String, val approved: Boolean)

@Serializable
data class SupervisedRegisterPayload(
    val devicePubkeyHex: String,
    val deviceName: String,
    val deviceRelayUrl: String,
    /**
     * When this request was created (epoch seconds). Echoed back in the answer so an answer
     * tapped from an old notification cannot decide a newer request. Null on older builds.
     */
    val requestedAt: Long? = null
)

@Serializable
data class SupervisedRegisterResponsePayload(
    val devicePubkeyHex: String,
    val accepted: Boolean,
    /**
     * The [SupervisedRegisterPayload.requestedAt] this answer belongs to. Null when the
     * supervisor's build is older and does not echo it, in which case the timing checks in
     * [com.locapeer.subscriber.isCurrentSupervisionRegistrationResponse] still apply.
     */
    val requestCreatedAt: Long? = null
)

/** Supervisor -> device (kind 1061): the supervisor proposes supervising this device. */
@Serializable
data class SupervisedInvitePayload(
    val supervisorName: String,
    val supervisorRelayUrl: String
)

/** Device -> supervisor (kind 1062): reply to a 1061 invite. Only the decline is a real
 *  state change on the supervisor side (acceptance is signalled by the 1055 register
 *  request that follows it), but both are echoed so the sender's state stays consistent. */
@Serializable
data class SupervisedInviteResponsePayload(
    val devicePubkeyHex: String,
    val accepted: Boolean
)

/** Supervisor -> device (kind 1063): supervision ended without removing the contact. */
@Serializable
data class SupervisedSupervisorEndPayload(
    val supervisorPubkeyHex: String
)

/** Device -> supervisor (kind 1064): request to turn supervised mode back off. */
@Serializable
data class SupervisedDisableRequestPayload(
    val requestId: String,
    val deviceName: String
)

/** Supervisor -> device (kind 1065): answer to a 1064 disable request. */
@Serializable
data class SupervisedDisableResponsePayload(
    val requestId: String,
    val approved: Boolean
)

/** Device -> supervisor (kind 1066): best-effort notice that this identity was erased. */
@Serializable
data class SupervisedWipePayload(
    val devicePubkeyHex: String
)

/** Device -> supervisor (kind 1067): ask to add a new tracking destination. */
@Serializable
data class ContactAddRequestPayload(
    val requestId: String,
    val contactPubkeyHex: String,
    val contactName: String
)

/** Supervisor -> device (kind 1068): answer to a 1067 contact-add request. */
@Serializable
data class ContactAddResponsePayload(
    val requestId: String,
    val approved: Boolean
)

/** One summarized settings change (kind 1069). [area] is a machine-readable shorthand the
 *  supervisor device renders (see SupervisionChangeReporter.AREA_* constants); [peerName]
 *  is already length-bounded by the reporter. */
@Serializable
data class SettingsChangeEntry(
    val area: String,
    val peerName: String? = null
)

/** Device -> supervisor (kind 1069): batched settings-change summary. */
@Serializable
data class SettingsChangeSummaryPayload(
    val entries: List<SettingsChangeEntry>
)
