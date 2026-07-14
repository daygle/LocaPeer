# LocaPeer

A private, peer-to-peer location sharing Android app built on the [Nostr](https://nostr.com) protocol. No accounts, no proprietary servers, and no tracking. A Nostr relay routes events between devices, but all location data, messages, and control payloads are end-to-end encrypted before they leave your device - the relay sees only opaque ciphertext for application events.

## Features

- **Real-time Location Sharing** - Broadcast your location on a configurable schedule or continuously; contacts see your live position on an interactive map.
- **Motion-Adaptive Heartbeats** - Update frequency scales automatically with your activity (driving/running/cycling/walking/stationary), detected by a custom GPS speed engine fused with Android Activity Recognition, saving power during low battery and while stationary without sacrificing accuracy. Per-motion intervals are user-tunable (Settings → Update Cadence) with sensible defaults from the engagement table below.
- **Sender-Side Quality Gates** - Drop incoming fixes coarser than a configurable accuracy radius before broadcast or storage (SOS is never gated). Pair with a separate history-only accuracy filter and a minimum-distance spacer to declutter long timelines without losing data.
- **Modern End-to-End Encryption** - All location data, messages, and control payloads use **NIP-44 v2** (ChaCha20-HMAC-SHA256) with full support for the standard bucketing padding scheme and extended length payloads (>64KiB). Relays store only opaque ciphertext for application events and cannot read your location or message content.
- **Per-Contact Privacy Controls** - Choose `EXACT` or `SUBURB` precision per contact, and `SEND`, `RECEIVE`, `SEND_RECEIVE`, or `NONE` for the relationship role independently. Set independent location and message retention windows; old data is automatically purged from your contact's device via encrypted remote purge requests.
- **Pause Sharing Per Contact** - A per-peer toggle that overrides the role and stops you from broadcasting to one specific contact without touching the rest. A subsequent temporary share on a paused peer is a no-op - the pause is the authoritative gate.
- **Sharing Schedules** - Define time-of-day and day-of-week rules (global or per-contact) to control when your location is broadcast automatically.
- **Role-Based Sharing** - Each contact relationship is independently configured as `SEND`, `RECEIVE`, `SEND_RECEIVE`, or `NONE` (messaging only). Roles can be changed at any time via in-app requests.
- **SOS Alerts** - One-tap emergency broadcast delivers your current coordinates as a high-priority notification exclusively to contacts you have designated as SOS contacts.
- **Geofencing** - Set circular zones by tapping the map, entering coordinates, or searching for an address (with the geocoding opt-in enabled), and receive enter, exit, or both alerts when a tracked contact crosses the boundary. Notification actions let you message or open the map directly.
- **Proximity Alerts** - Get notified when a contact comes within a configurable radius of your current position.
- **Missed-Location Alerts** - Opt in per contact to be notified when someone you track goes silent for noticeably longer than their expected update interval.
- **Encrypted Messaging** - Fully private direct messages with delivery receipts (SENDING → SENT → DELIVERED → READ), end-to-end encrypted read receipts via `READ_RECEIPT` (kind 1048), and real-time typing indicators. Long-press a sent message for a delivery-status sheet; long-press any message to delete locally, on the recipient's device, or both (NIP-09).
- **Custom Relays** - Add your own private or community Nostr relays to the connection set. Custom relays can be toggled independently, allowing you to route data exclusively through infrastructure you trust or control.
- **Rich Media Messages** - Send images from the system Photo Picker, voice notes (auto-stopped and capped), and one-tap location pins (rendered as tappable cards that open the map). Media is compressed and base64-encoded inside a nested NIP-44 envelope so each recipient still gets their own individually encrypted copy.
- **Temporary Sharing** - Share live location per contact for 15 min / 1 h / 3 h / 6 h / 12 h / until tomorrow 8 am. The window is enforced by a one-shot WorkManager job and stops automatically without further user action. For circles, share your location with every member at once for 15 min / 1 h / 3 h / 6 h with one job per member; paused members are skipped and the rest start receiving the moment they unpause.
- **Leave Contact** - One-tap "Delete Contact and Data" removes you from a contact's list on their device (via `PEER_REMOVED` + `DELETE_MY_MESSAGES` + `DELETE_MY_LOCATION`), clears all of their data locally, drops them from every circle you share, and severs the location link bidirectionally - in contrast to "Remove Contact" which is unilateral.
- **Group Chats (Circles)** - Create client-side groups of existing contacts, send text, images, voice notes and location pins to every member at once. There is no shared group key: each member receives an individually NIP-44-encrypted copy carrying the circle's id, name, member list and creator, so a circle materialises on every member's device automatically. Recipients can pin a location, see the sender of each bubble (unlike 1:1 chat) and use the same delete / long-press affordances.
- **Conversation Management** - Search by name or message content; sort by date / name / unread. Sub-tabs for Chats / Circles / Archived. Long-press or swipe a conversation row - left to delete (with local / both options), right to archive / unarchive. Multi-select to bulk mark read or unread, archive, or delete.
- **Location History** - Browse a contact's (or your own) past location data day-by-day, in a list or on an interactive OpenStreetMap view, with a direction/bearing summary and an adjustable time-of-day range. DST-aware day bounds (a 23 h or 25 h day stays anchored to local midnight), optional distance-thinning and accuracy filters declutter the trail, and street-address lookup is available as an explicit opt-in (off by default, since it queries the device geocoder).
- **Supervised Mode** - Lock Settings behind remote Nostr-based approval. Supervised devices register with a supervisor via a two-sided consent handshake; unlock requests are approved or denied in real time with no static PIN to compromise.
- **App Lock** - Require biometric or device-credential authentication at launch, with a configurable re-lock grace period (immediate / 30 s / 1 min / 5 min). The lock tracks whole-process backgrounding, not per-Activity stop, so a transient system dialog does not lock you out mid-task.
- **Theme & Material You** - Light, dark, or follow-system theme with an independent toggle for Material You dynamic colours (Android 12+).
- **Customizable Navigation & Units** - Choose which bottom-navigation tabs appear, reorder them, and set the screen shown on launch. Distances, speeds, elevation, and clock format follow your metric/imperial and 12/24-hour preferences, with sensible defaults taken from the device locale.
- **Customizable Map** - Pick your map starting point (current location, fit-to-all contacts, a fixed location picked on the map, or restore the last position), set the default zoom level (3 - 18), choose a pin colour from 12 preset hex swatches, and toggle between light and dark CartoDB tile styles - all chosen client-side with no third-party API key.
- **Relay Status & Queue Visibility** - A live Connected / Disconnected chip on the map reflects any-relay-up state; the About screen shows per-relay connection state and the count of events sitting in the per-relay outbox. Failed sends are parked in a per-relay pending-events table and retried with exponential backoff so a transient network blip doesn't drop a relay-bound event.
- **Localization** - Translated into 50+ languages with an in-app language picker (per-app locale on Android 13+, backed below).
- **Backup & Restore** - Export and selectively restore your identity, contacts, geofences, and settings to a local JSON file, optionally password-encrypted (AES-256-GCM with a PBKDF2-HMAC-SHA256 derived key). Newly created accounts are gently nudged to back up their private key during onboarding, before anything that could be lost exists.
- **Screenshot Protection** - `FLAG_SECURE` prevents UI capture of sensitive screens (maps, private key, profile QR).

## How It Works

### Identities
Each device generates a unique Nostr keypair on first launch. Your public key is your identity - no email, phone number, or account required. The private key is stored in the **Android Keystore** and persisted via **Jetpack DataStore**.

### Connecting
Share your **Invite QR code** or **Invite Link** (`locapeer://invite?data=<base64>`) found in Settings → My Profile. When a contact scans it, both devices exchange `TRACK_REQUEST` / `TRACK_ACCEPT` events to negotiate initial roles and begin subscribing to each other's encrypted location events.

### Location Events
When sharing is enabled, the app publishes a `HEARTBEAT` event encrypted individually for each subscriber. Heartbeat frequency is motion-adaptive:

| Motion State | Default Interval |
|---|---|
| Driving / Running | 2 min |
| Cycling | 3 min |
| Walking | 5 min |
| Stationary | 15 min |
| Low Battery (<20%) | 30 min |
| SOS active | 15 sec |

Motion state is detected by a custom speed-based classifier and corroborated by Android Activity Recognition (sensor-based), so GPS scatter alone can't mislabel a walk as a drive. It is displayed alongside each location ping.

### Precision Modes
Each contact relationship can be set to one of two precision levels:

| Mode | Behaviour |
|---|---|
| `EXACT` | Full GPS coordinates transmitted |
| `SUBURB` | Coordinates rounded to ~0.01°, obscuring exact position within a ~1.1 km radius |

### Sharing Schedules
Schedule rules specify which days of the week (Monday-Sunday bitmask) and which minutes of the day (`startMinute`-`endMinute`) sharing is active. Multiple rules are combined with OR logic. An empty rule set means always on. SOS alerts bypass schedules entirely. Rules can be set globally or overridden per contact.

### Circles (Group Chats)
Circles are a client-side grouping of contacts you've already added. The creator defines a name and member list; the circle id, name, member list and creator pubkey travel inside a NIP-44-encrypted `ENCRYPTED_DM` (kind 44) tagged with each recipient's pubkey, so the rest of the circle materialises locally on every member's device without a separate group-invite handshake. There is no shared group key: every member still receives their own individually encrypted copy. A location pin, image or voice note inside a circle uses the same envelope, but the text body is itself a nested media wire envelope (image bytes Base64-encoded and downscaled before encrypting).

### Temporary Location Sharing
Temporary shares use the same per-peer configuration the regular sharing uses, but add an `endsAt` epoch second that's enforced by a one-shot WorkManager job (`TempShareExpiryWorker`). Starting a temporary share on a contact the user has paused (sharingEnabled = false) is a no-op - the pause is the authoritative gate and must not be silently overwritten. The same job runs for every member when a circle share is started, with one job per member.

### Supervised Mode
1. **Registration**: The supervised device sends a `SUPERVISED_REGISTER` event to the supervisor's pubkey. The supervisor receives a persistent notification with **Accept** and **Decline** actions.
2. **Acceptance**: On accept, the supervisor's device records the relationship (`isMySupervised = true`) and sends `SUPERVISED_REGISTER_ACCEPT` back. The supervised device is notified and supervised mode becomes active.
3. **Unlock**: When the user needs settings access, a `SUPERVISED_UNLOCK_REQUEST` is sent. The supervisor approves or denies via a real-time notification; the result is reflected on the supervised device within 60 seconds (timeout).

## Nostr Event Kinds

| Kind | Name | Purpose |
|---|---|---|
| 44 | `ENCRYPTED_DM` | NIP-44 encrypted direct message |
| 5 | `EVENT_DELETION` | NIP-09 plaintext deletion request for a published event |
| 1040 | `HEARTBEAT` | Encrypted location ping with motion state |
| 1041 | `SOS_ALERT` | High-priority emergency broadcast with coordinates |
| 1042 | `TRACK_REQUEST` | Initiate or change a peer tracking relationship |
| 1043 | `TRACK_ACCEPT` | Accept a tracking request |
| 1044 | `PEER_REMOVED` | Notify peer of contact removal |
| 1045 | `PURGE_REQUEST` | Request deletion of old location data on peer device |
| 1046 | `MESSAGE_PURGE_REQUEST` | Request deletion of old messages on peer device |
| 1047 | `DELIVERY_ACK` | Confirm a single message was delivered |
| 1048 | `READ_RECEIPT` | Confirm one or more messages were read |
| 1049 | `SUPERVISED_UNLOCK_REQUEST` | Supervised device requests settings access |
| 1050 | `SUPERVISED_UNLOCK_RESPONSE` | Supervisor approves or denies access |
| 1051 | `DELETE_MY_MESSAGES` | Request peer delete all messages from this sender |
| 1052 | `DELETE_MY_LOCATION` | Request peer delete all location data from this sender |
| 1053 | `TYPING` | Real-time ephemeral typing indicator |
| 1054 | `TRACK_DECLINE` | Decline a tracking request |
| 1055 | `SUPERVISED_REGISTER` | Supervised device initiates supervisor registration |
| 1056 | `SUPERVISED_REGISTER_ACCEPT` | Supervisor accepts device registration |
| 1057 | `SUPERVISED_REGISTER_DECLINE` | Supervisor declines device registration |
| 1058 | `TRACKING_ALERT` | Notifies peer when someone receives an alert about them |

Custom application events (kinds 1040-1058) are tagged with the recipient's public key (`p` tag) and NIP-44 encrypted so only the intended recipient can decrypt them. The exception is kind 5 (`EVENT_DELETION`), which follows the standard NIP-09 format: its content is a plaintext deletion reason and it references the target event via an `e` tag.

## Technical Architecture

| Layer | Technology |
|---|---|
| **UI** | Jetpack Compose + Material 3 |
| **Dependency Injection** | Hilt |
| **Storage** | Room (SQLite) + DataStore (Settings & Preferences) |
| **Secure Storage** | Android Keystore + DataStore (AES/GCM) |
| **Networking** | WebSockets via OkHttp |
| **Cryptography** | `secp256k1-kmp` (BIP-340 Schnorr) + Bouncy Castle (NIP-44 v2 ChaCha20-HMAC-SHA256) |
| **Maps** | OSMDroid (OpenStreetMap) |
| **Location** | Google Play services: FusedLocationProviderClient + Activity Recognition |
| **Motion Classification** | Custom GPS speed engine (MotionMath) fused with Activity Recognition (MotionFusion) |
| **Background Work** | WorkManager (retention enforcement, missed-heartbeat watchdog) + boot receiver |
| **QR Codes** | ZXing (generation and scanning via `zxing-android-embedded`) |
| **Localization** | AppCompat per-app locales (`AppCompatDelegate.setApplicationLocales`) |

## Building

**Requirements**
- Android Studio (latest stable recommended, for `compileSdk` 37 support)
- JDK 17+
- Android SDK: `compileSdk` 37, `targetSdk` 36, `minSdk` 26 (Android 8.0)

```bash
git clone https://github.com/daygle/LocaPeer.git
cd LocaPeer
./gradlew assembleDebug
```

**Public relays**: `wss://relay.daygle.net`, `wss://nos.lol`, `wss://relay.damus.io`, `wss://relay.snort.social`

## Privacy & Security

- **Zero Trust Relay**: The Nostr relay is a dumb transport; all application event payloads are NIP-44 encrypted before leaving the device. The relay can see NIP-09 deletion requests (kind 5, plaintext by standard) and event metadata (kind, timestamp, pubkey), but cannot read your location data or message content.
- **Peer-Peer Encryption**: Each heartbeat is encrypted individually for its recipient using keys derived for that conversation.
- **Hardware-Backed Keys**: Private keys never leave the device and are protected by the TEE/SE via Android Keystore where supported.
- **Schnorr Signatures**: Every event is signed with BIP-340 Schnorr; recipients verify the signature before processing.
- **Tracking Transparency**: `TRACKING_ALERT` (kind 1058) events notify you if another user receives a geofence or proximity alert triggered by your movement, ensuring you are aware of how your location data is being monitored.
- **Sender-Side Quality Gate**: Optionally refuse to broadcast or store your own fixes coarser than a chosen accuracy, so a stray cell-tower fix never paints a misleading pin (SOS is never gated). A separate history-only accuracy filter hides - without deleting - low-quality pings from the displayed trail.
- **Encrypted Backups**: Backup files can be password-protected; the payload is AES-256-GCM encrypted under a PBKDF2-HMAC-SHA256 key (600,000 iterations).
- **Bidirectional Deletion**: Long-press a message to delete it locally, on the recipient's device via NIP-09 `EVENT_DELETION` (kind 5) - one event per fan-out copy for circle messages, exactly the `e` tag the recipient's matcher expects - or both at once. Bulk delete works the same way from the conversation and contacts screens.
- **Deletion Honesty**: Bulk "mark read / mark unread" on the conversation list never sends read receipts or NIP-09 deletion events to the sender; only opening the chat and explicitly removing content does.
- **On-Device Geocoding Opt-In**: All geocoding is off by default and gated behind a single "Look Up Addresses" opt-in, because it queries the OS geocoder off-device. That covers reverse geocoding (street addresses in History and on map pins, which sends tracked coordinates) and address search in the geofence editor (which sends only the text you type, when you explicitly run a search).
- **Replay Protection**: Control events are checked for freshness (300-second window for unlock events, 24-hour window for registration); a 30-day catch-up window for offline delivery replays only recent history.
- **Outbox Reliability**: Failed sends (e.g. transient disconnect) are parked in a per-relay pending-message table and retried with exponential backoff (5 s → 10 s → ... capped at 5 min) until the relay accepts them, so an event travelling across two flaky relays doesn't lose a copy just because one was down at sec 0.
- **Cross-Relay Suppression Defense**: Recent event IDs are cached only AFTER their Schnorr signature verifies, so a forged-event attack can't poison the cache and silence a genuine event that arrives later. Recent-ID cache is size-bounded (LRU, 2 000 entries) and persisted in DataStore every 100 events.
- **App Lock**: Optional biometric or device-credential prompt at launch, with a user-chosen re-lock grace period. Whole-process backgrounding (via `ProcessLifecycleOwner`) - not per-Activity stop - controls re-lock, so a transient system permission dialog doesn't sign you out mid-task. No static PIN to compromise; the device's own biometrics are the only keys.
- **UI Hardening**: `FLAG_SECURE` prevents screenshots and screen recordings on sensitive screens; private-key reveal notices this hiding is best-effort only.
- **Open Attributions**: The About screen lists the open-source libraries the app depends on (OSMDroid, OkHttp, Jetpack Compose, Hilt, Room) and credits OpenStreetMap / CartoDB for map data.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
