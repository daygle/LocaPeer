# LocaPeer

A private, peer-to-peer location sharing Android app built on the [Nostr](https://nostr.com) protocol. No accounts, no proprietary servers, and no tracking. A Nostr relay routes encrypted events between devices, but the relay never sees your data — everything is end-to-end encrypted before it leaves your device.

## Features

- **Real-time Location Sharing** - Broadcast your location on a schedule or on-demand; subscribers see it live on their map.
- **Modern End-to-End Encryption** - All location data and messages use the **NIP-44 v2** (ChaCha20-HMAC-SHA256) standard. The relay only sees encrypted noise.
- **SOS Alerts** - One-tap emergency broadcast with your current coordinates, delivered as a high-priority notification to your trusted peers.
- **Geofencing** - Set circular zones on the map and receive alerts when a tracked person enters or leaves.
- **Proximity Alerts** - Get notified when a tracked person comes within a configurable distance of you.
- **Encrypted Messaging** - Fully private direct messages between peers, featuring delivery receipts, read receipts, and typing indicators.
- **Location History** - Browse past location data by day in a list or on an interactive OpenStreetMap view.
- **Privacy Controls** - Set retention windows; old location data and messages are automatically purged from peers' devices via remote purge requests.
- **Supervised Mode** - Lock settings behind remote Nostr-based approval, ideal for parental controls or managed devices.
- **Screenshot Protection** - Sensitive data (like private IDs and maps) is shielded from screenshots and screen recordings.

## How it works

### Identities
Each device generates a unique Nostr keypair on first launch. Your public key is your identity - no email, phone number, or account required. Private keys are stored in the **Android Keystore** and persisted via **Jetpack DataStore**.

### Connecting
Share your **Invite QR code** or **Invite Link** (found in Settings → My Profile). When someone scans it, their device saves your public key and starts subscribing to your encrypted location events.

### Location Events
When broadcasting is enabled, the app sends a `HEARTBEAT` event (kind 1) encrypted specifically for each subscriber. Heartbeat frequency is **motion-adaptive** - the app updates more frequently when you are moving (driving/walking) and slows down when stationary to save battery.

### Supervised Mode
Enable Supervised Mode to lock the Settings screen. Access requires the designated supervisor to approve a request in real time from their own device - no static PIN or password to be compromised.

## Nostr Event Kinds

| Kind  | Name | Standard/Purpose |
|-------|------|---------|
| 1 | `HEARTBEAT` | Encrypted location ping (Custom) |
| 4 | `ENCRYPTED_DM` | NIP-44 Encrypted direct message |
| 10001 | `READ_RECEIPT` | NIP-44 Encrypted read acknowledgement |
| 10002 | `TYPING` | NIP-44 Encrypted ephemeral typing signal |
| 10003 | `PURGE_REQUEST` | NIP-44 Encrypted request to delete old heartbeats |
| 10004 | `MESSAGE_PURGE_REQUEST` | NIP-44 Encrypted request to delete old messages |
| 10005 | `DELIVERY_ACK` | NIP-44 Encrypted delivery confirmation |
| 10006 | `SUPERVISED_UNLOCK_REQUEST` | Managed device requests settings access |
| 10007 | `SUPERVISED_UNLOCK_RESPONSE` | Supervisor approves or denies access |
| 30000 | `SOS_ALERT` | High-priority SOS with location (Custom) |

## Technical Architecture

| Layer | Technology |
|-------|-----------|
| **UI** | Jetpack Compose + Material 3 |
| **Dependency Injection** | Hilt |
| **Storage** | Room (SQLite) + DataStore (Settings) |
| **Secure Storage** | Android Keystore + DataStore (Manual AES/GCM) |
| **Networking** | WebSockets via OkHttp |
| **Cryptography** | `secp256k1-kmp` (BIP-340) + Bouncy Castle (NIP-44 v2) |
| **Maps** | OSMDroid (OpenStreetMap) |
| **Location** | Android FusedLocationProvider |
| **Activity Sensing** | Android Activity Recognition (Motion-adaptive pulsing) |

## Building

**Requirements**
- Android Studio Hedgehog or later
- JDK 17+
- Android SDK 34 (Target) / SDK 26 (Minimum)

```bash
git clone https://github.com/daygle/LocaPeer.git
cd LocaPeer
./gradlew assembleDebug
```

**Default Relays**: `wss://relay.daygle.net`, `wss://relay.damus.io`.

## Privacy & Security

- **Zero Trust Architecture**: The Nostr relay is a dumb transport only; it stores ciphertext and cannot read any of your data.
- **Per-Peer Encryption**: Location events are encrypted individually for each subscriber using unique derived keys.
- **Hardware Protection**: Private keys never leave the device and are protected by hardware-level security (TEE/SE) where supported.
- **App Hardening**:
    - `FLAG_SECURE` prevents UI capturing and recording.
    - Exported components are restricted by system permissions (e.g., `RECEIVE_BOOT_COMPLETED`).
    - NIP-44 v2 provides state-of-the-art authentication and encryption (ChaCha20 + HMAC-SHA256).

## License

MIT
