# Changelog

All notable changes to LocaPeer are documented in this file. Dates are
`YYYY-MM-DD`. Versions follow [Semantic Versioning](https://semver.org/) and the
format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/):

- **Added** - new features.
- **Changed** - changes to existing behaviour.
- **Deprecated** - soon-to-be-removed features.
- **Removed** - features that have gone away.
- **Fixed** - bug fixes.
- **Security** - security or privacy fixes (always called out explicitly).

The most recent release is on top. `Unreleased` collects work that has landed
on `main` but has not yet been tagged as a release - this is where new
contributors should look first to see what is about to ship.

## [Unreleased]

_Placeholder for the next batch of work landing on `main`._

## [1.1.29] - 2026-07-13

The most recent tagged release. Full audit of older releases is in progress;
see [the repository's release history](https://github.com/daygle/LocaPeer/releases)
for tagged builds beyond this.

### Added
- **Circles (group chat)** - Client-side groups of existing contacts. Send
  text, images, voice notes and location pins to every member at once. There
  is no shared group key; each member receives an individually NIP-44
  encrypted copy carrying the circle id, name, member list and creator, so
  the circle materializes on every member's device with no separate
  group-invite handshake. Members see a sender name on every incoming
  bubble (unlike 1:1 chat) and use the same delete / long-press affordances.
- **Rich media messages** - Images from the system Photo Picker, voice notes
  (auto-stopped and size-capped), and one-tap location pins rendered as
  tappable cards that open the map. Media travels Base64-encoded inside a
  nested NIP-44 envelope so each recipient still gets their own individually
  encrypted copy.
- **Delivery state machine** - SENDING -> SENT (relay accepted) -> DELIVERED
  (recipient device saw it) -> READ (recipient read it). Long-press the
  delivery row on a sent message for a sheet that explains what each state
  means.
- **Per-message delete (local / remote / both)** - Long-press a message to
  delete locally, on the recipient's device via NIP-09 `EVENT_DELETION`
  (one event per circle fan-out copy), or both.
- **Temporary location sharing** - Per contact for 15 min / 1 h / 3 h / 6 h
  / 12 h / until tomorrow 8 am. Shares are enforced by a one-shot
  `TempShareExpiryWorker` job and stop automatically. Circle shares expose
  15 min / 1 h / 3 h / 6 h with one job per member.
- **Conversation management** - Search by name or message content; sort by
  date / name / unread; sub-tabs for Chats / Circles / Archived. Swipe left
  to delete (with local / both options), swipe right to archive / unarchive.
  Multi-select to bulk mark read or unread, archive, or delete. Bulk "mark
  unread" toggle flips on when every selected chat is already read. Bulk
  mark read / unread never sends read receipts to the sender.
- **App lock** - Optional biometric or device-credential prompt at launch
  with a configurable re-lock grace period (immediate / 30 s / 1 min /
  5 min). Relocks on whole-process backgrounding, not per-Activity stop,
  so a transient system permission dialog does not lock you out mid-task.
- **Theme and Material You** - Light, dark, or follow-system theme with an
  independent toggle for Material You dynamic colours (Android 12+). The
  default map style follows the system.
- **Customizable map** - Pick a starting point (current location, fit-to-all
  contacts, a fixed location picked on the map, or restore the last
  position), set the default zoom (3 - 18), choose a pin colour from 12
  preset hex swatches, and toggle between light and dark CartoDB tile
  styles - all client-side with no third-party API key.
- **Pause sharing per contact** - Per-peer toggle that overrides the role and
  stops broadcasting to one specific contact. A subsequent temporary share
  on a paused peer is a no-op - the pause is the authoritative gate.
- **Leave contact** - "Delete Contact and Data" removes you from a contact's
  list on their device (sends `PEER_REMOVED` + `DELETE_MY_MESSAGES` +
  `DELETE_MY_LOCATION`), clears their data locally, drops them from every
  circle you share, and severs the link bidirectionally.
- **Sender-side quality gate** - Optionally refuse to broadcast or store
  own fixes coarser than a chosen accuracy radius. Pair with a separate
  history-only accuracy filter and a minimum-distance spacer to declutter
  long timelines without losing data.
- **Customizable update cadence** - Per-motion heartbeat interval sliders
  with a Reset-to-defaults action. SOS is never gated by accuracy or
  cadence.
- **DST-aware history day bounds** - Day navigation stays anchored to local
  midnight, so a 23 h or 25 h day around a DST transition does not drop or
  duplicate pings.
- **Relay status and outbox visibility** - A live Connected / Disconnected
  chip on the map; the About screen shows per-relay connection state and
  the count of events sitting in the per-relay outbox.

### Changed
- **Onboarding** - Newly created accounts are nudged to back up their
  private key on first run, before any data exists that could be lost.
- **History map** - Map tiles now reliably load in the background on the
  first switch to the Map tab; the lifecycle hook that detached the view
  too eagerly is fixed.
- **About screen** - Adds the relay-connection card, the queued-message
  count card, and the open-source library attributions.

### Fixed
- **Circle message long-press** - Long-press menu on a circle message now
  matches the 1:1 chat menu (delete locally, delete on contact, delete
  both for sender rows).
- **History timeline background** - Map tiles no longer fail to load on
  first switch.
- **Read-receipt leakage** - Bulk "mark read" on the conversation list no
  longer emits `READ_RECEIPT` events to the sender; only opening the chat
  does.

### Security
- **Cross-relay suppression defense** - Recent-event cache only records an
  id after its Schnorr signature verifies, so a forged event cannot poison
  the cache and silence a genuine event arriving later via another relay.
  Cache is size-bounded (LRU, 2 000 entries) and persisted every 100
  events.
- **Outbox reliability** - Failed sends are parked in a per-relay
  pending-events table and retried with exponential backoff (5 s baseline,
  jittered, capped at 5 min) until at least one relay accepts them.
