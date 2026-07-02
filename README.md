# LocaPeer

A private, peer-to-peer location sharing Android app built on the [Nostr](https://nostr.com) protocol. No accounts, no proprietary servers, and no tracking. A Nostr relay routes events between devices, but all location data, messages, and control payloads are end-to-end encrypted before they leave your device - the relay sees only opaque ciphertext for application events.

## Features

- **Real-time Location Sharing** - Broadcast your location on a configurable schedule or continuously; contacts see your live position on an interactive map.
- **Motion-Adaptive Heartbeats** - Update frequency scales automatically with your activity (driving/running/cycling/walking/stationary) and drops during low battery, saving power without sacrificing accuracy.
- **Modern End-to-End Encryption** - All location data, messages, and control payloads use **NIP-44 v2** (ChaCha20-HMAC-SHA256). Relays store only opaque ciphertext for application events and cannot read your location or message content.
- **Per-Contact Privacy Controls** - Choose `EXACT` or `SUBURB` precision per contact. Set independent location and message retention windows; old data is automatically purged from your contact's device via encrypted remote purge requests.
- **Sharing Schedules** - Define time-of-day and day-of-week rules (global or per-contact) to control when your location is broadcast automatically.
- **Role-Based Sharing** - Each contact relationship is independently configured as `SEND`, `RECEIVE`, `SEND_RECEIVE`, or `NONE` (messaging only). Roles can be changed at any time via in-app requests.
- **SOS Alerts** - One-tap emergency broadcast delivers your current coordinates as a high-priority notification exclusively to contacts you have designated as SOS contacts.
- **Geofencing** - Set circular zones on the map and receive enter, exit, or both alerts when a tracked contact crosses the boundary. Notification actions let you message or open the map directly.
- **Proximity Alerts** - Get notified when a contact comes within a configurable radius of your current position.
- **Encrypted Messaging** - Fully private direct messages with delivery receipts, read receipts, and real-time typing indicators.
- **Location History** - Browse a contact's (or your own) past location data day-by-day in a list or on an interactive OpenStreetMap view with a direction/bearing summary.
- **Supervised Mode** - Lock Settings behind remote Nostr-based approval. Supervised devices register with a supervisor via a two-sided consent handshake; unlock requests are approved or denied in real time with no static PIN to compromise.
- **Backup & Restore** - Export and selectively restore your identity, contacts, geofences, and settings to a local JSON file.
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

Motion state is detected via Android Activity Recognition and displayed alongside each location ping.

### Precision Modes
Each contact relationship can be set to one of two precision levels:

| Mode | Behaviour |
|---|---|
| `EXACT` | Full GPS coordinates transmitted |
| `SUBURB` | Coordinates rounded to ~0.01°, obscuring exact position within an ~1.1 km radius |

### Sharing Schedules
Schedule rules specify which days of the week (Monday–Sunday bitmask) and which minutes of the day (`startMinute`–`endMinute`) sharing is active. Multiple rules are combined with OR logic. An empty rule set means always on. SOS alerts bypass schedules entirely. Rules can be set globally or overridden per contact.

### Supervised Mode
1. **Registration**: The supervised device sends a `SUPERVISED_REGISTER` event to the supervisor's pubkey. The supervisor receives a persistent notification with **Accept** and **Decline** actions.
2. **Acceptance**: On accept, the supervisor's device records the relationship (`isMySupervised = true`) and sends `SUPERVISED_REGISTER_ACCEPT` back. The supervised device is notified and supervised mode becomes active.
3. **Unlock**: When the user needs settings access, a `SUPERVISED_UNLOCK_REQUEST` is sent. The supervisor approves or denies via a real-time notification; the result is reflected on the supervised device within 60 seconds (timeout).

## Nostr Event Kinds

| Kind | Name | Purpose |
|---|---|---|
| 4 | `ENCRYPTED_DM` | NIP-44 encrypted direct message |
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

Custom application events (kinds 1040–1057) are tagged with the recipient's public key (`p` tag) and NIP-44 encrypted so only the intended recipient can decrypt them. The exception is kind 5 (`EVENT_DELETION`), which follows the standard NIP-09 format: its content is a plaintext deletion reason and it references the target event via an `e` tag.

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
| **Location** | Android FusedLocationProviderClient |
| **Activity Sensing** | Android Activity Recognition API |
| **QR Codes** | ZXing (generation) + ML Kit Vision (scanning) |

## Building

**Requirements**
- Android Studio Hedgehog or later
- JDK 17+
- Android SDK 34 (target) / SDK 26 (minimum)

```bash
git clone https://github.com/daygle/LocaPeer.git
cd LocaPeer
./gradlew assembleDebug
```

**Default relays**: `wss://relay.daygle.net`, `wss://nos.lol`, `wss://relay.damus.io`, `wss://relay.snort.social`

## Privacy & Security

- **Zero Trust Relay**: The Nostr relay is a dumb transport; all application event payloads are NIP-44 encrypted before leaving the device. The relay can see NIP-09 deletion requests (kind 5, plaintext by standard) and event metadata (kind, timestamp, pubkey), but cannot read your location data or message content.
- **Per-Peer Encryption**: Each heartbeat is encrypted individually for its recipient using keys derived for that conversation.
- **Hardware-Backed Keys**: Private keys never leave the device and are protected by the TEE/SE via Android Keystore where supported.
- **Schnorr Signatures**: Every event is signed with BIP-340 Schnorr; recipients verify the signature before processing.
- **Replay Protection**: Control events are checked for freshness (300-second window for unlock events, 24-hour window for registration); a 30-day catch-up window for offline delivery replays only recent history.
- **UI Hardening**: `FLAG_SECURE` prevents screenshots and screen recordings on sensitive screens.

## License

MIT
