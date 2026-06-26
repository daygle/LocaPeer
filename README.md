# LocaPeer

A location sharing Android app built on the [Nostr](https://nostr.com) protocol. No accounts, no proprietary servers. A Nostr relay routes encrypted events between devices, but the relay never sees your data — everything is end-to-end encrypted before it leaves your device. You can use any public relay or self-host your own.

## Features

- **Real-time location sharing** - Broadcast your location on a schedule or on-demand; subscribers see it live on their map
- **End-to-end encrypted** - All location data and messages use NIP-04 (AES-256-CBC) encryption; the relay never sees plaintext
- **SOS alerts** - One-tap emergency broadcast with your current coordinates, delivered as a high-priority notification
- **Geofencing** - Set circular zones on the map and receive alerts when a tracked person enters or leaves
- **Proximity alerts** - Get notified when a tracked person comes within a configurable distance of you
- **Encrypted messaging** - Fully private direct messages between peers, with delivery receipts and read receipts
- **Location history** - Browse past location data by day in a list or on an interactive OpenStreetMap view
- **Privacy controls** - Set retention windows; old location data and messages are automatically purged from peers' devices
- **Supervised mode** - Lock settings behind remote Nostr-based approval, ideal for parental controls

## How it works

### Identities
Each device generates a Nostr keypair on first launch. Your public key is your identity - no email, phone number, or account required.

### Connecting
Share your **Invite QR code** (found in Settings → My Profile). When someone scans it, their device saves your public key and starts subscribing to your encrypted location events.

### Location events
When broadcasting is enabled, the app sends a `HEARTBEAT` event (kind 1) encrypted with each subscriber's public key. Events are published to the configured Nostr relay. Subscribers decrypt and store heartbeats locally in a Room database.

### Supervised mode
Enable Supervised Mode to lock the Settings screen. Access requires the designated supervisor to approve a request in real time from their own device - no PIN stored anywhere.

## Custom Nostr event kinds

| Kind  | Name | Purpose |
|-------|------|---------|
| 1 | `HEARTBEAT` | Encrypted location ping |
| 4 | `ENCRYPTED_DM` | NIP-04 direct message |
| 10001 | `READ_RECEIPT` | Message read acknowledgement |
| 10002 | `TYPING` | Ephemeral typing indicator |
| 10003 | `PURGE_REQUEST` | Request subscriber to delete old heartbeats |
| 10004 | `MESSAGE_PURGE_REQUEST` | Request peer to delete old messages |
| 10005 | `DELIVERY_ACK` | Message delivery confirmation |
| 10006 | `SUPERVISED_UNLOCK_REQUEST` | Managed device requests settings access |
| 10007 | `SUPERVISED_UNLOCK_RESPONSE` | Supervisor approves or denies access |
| 30000 | `SOS_ALERT` | High-priority SOS with location |

## Architecture

| Layer | Technology |
|-------|-----------|
| UI | Jetpack Compose + Material 3 |
| DI | Hilt |
| Database | Room (SQLite) |
| Networking | WebSockets via OkHttp (`NostrRelayClient`) |
| Crypto | secp256k1 Schnorr signatures + NIP-04 encryption |
| Maps | OSMDroid (OpenStreetMap) |
| Location | Android FusedLocationProvider |
| Motion detection | Android Activity Recognition API |

### Key components

- **`HeartbeatService`** - Foreground service; fires location pings on a motion-adaptive schedule
- **`HeartbeatReceiver`** - Singleton that subscribes to all incoming Nostr events and routes them to the appropriate handlers
- **`NostrRelayClient`** - WebSocket client with built-in deduplication; emits a `SharedFlow<NostrEvent>`
- **`GeofenceEngine`** / **`ProximityEngine`** - Evaluate each incoming heartbeat against saved zones/thresholds and fire local notifications
- **`SupervisedModeManager`** - Manages the unlock request lifecycle (Idle → Requesting → Approved/Denied/TimedOut) on the managed device
- **`SupervisionApprovalManager`** - Holds the pending unlock request on the supervisor device until the user responds

## Building

**Requirements**
- Android Studio Hedgehog or later
- JDK 17
- Android SDK 34 (target) / SDK 26 (minimum)

```bash
git clone https://github.com/daygle/LocaPeer.git
cd LocaPeer
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

**Default relay**: `wss://relay.damus.io` - configurable in Settings → Relay.

## Privacy

- Your private key never leaves the device
- Location events are encrypted individually for each subscriber - only the intended recipient can decrypt them
- The Nostr relay is a transport only — it stores ciphertext and cannot read your location, messages, or any other content
- Retention settings automatically send purge requests to peers, deleting your data on their devices
- Supervised mode approval travels over the same encrypted channel - no credentials are stored or transmitted in plaintext

## Contributing

Bug reports and pull requests are welcome. Please open an issue first for anything beyond small fixes.

## License

MIT
