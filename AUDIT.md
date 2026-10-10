# LocaPeer audit — 2026-10-10

## Scope and acceptance criteria

Repository-wide static review of Android configuration, Gradle/dependencies, Room
entities/DAOs/migrations, cryptography, relay transport, invitations/control messages,
supervision, messaging/media, location, schedules, history, UI lifecycle, and workers.
Deeper review focused on trust boundaries and persistence/concurrency. This is not a
penetration-test certification or a claim that all defects have been eliminated.

Preserve legitimate Nostr envelopes, Room schema v11 and migration history, existing
privacy controls and Android features. No new service, database migration, broad
framework rewrite, commit, or deployment. New safety ceilings: 1 MiB UTF-8 NIP-44
plaintext, 2 MiB relay message characters, 16 MiB backup input. Current generated media
fits these budgets; unusually large backups now fail rather than exhaust memory.
Incoming circles must use the UUID namespace already used by locally created circles.

## Fixed findings

- **High — Location-sharing consent:** a receive-only contact could trigger automatic
  promotion to reciprocal tracking. Require review/accept; unknown senders cannot evade
  the pending-request cap by claiming a role change. Serialize track-request handling.
- **High — Thread isolation:** a remote circle ID could equal a peer ID and mix group
  and private thread queries. Validate UUID IDs and member/creator key formats.
- **High — Resource exhaustion:** bound NIP-44/base64 input before decoding and stream
  backup input with a one-byte overflow probe. Parse backups off the UI thread.
- **Medium — NIP-44 correctness:** fix the 65,535/65,536 extended-header boundary;
  validate exact padding buckets and canonical extended lengths. Allocate padding once
  rather than copying a second padded buffer. Clear PBKDF2 password spec after use.
- **Medium — Dedup memory:** globally evicted single-event senders left empty buckets,
  enabling growth under rotating keys. Remove empty buckets; validate capacities.
- **Medium — Relay lifecycle:** acknowledge close frames, reconnect after relay normal
  closes, permit synchronous failure retries, ignore superseded socket messages.
  Serialize per-relay outbox flushes and stop at first send failure. Remove redundant
  custom platform hostname verifier; retain OkHttp's secure default verification.
- **Medium — Deletion privacy:** clear staged attachments/audio and thumbnail cache on
  remote message purge, circle leave, and NIP-09 deletion. Correct misleading comments
  describing Room rows as encrypted at rest.
- **Medium — Heartbeat batching:** retain dedup keys through commit and failed rows for
  retry. Coordinate remote deletion with buffered/in-flight inserts and serialize
  destructive control processing. Geofence transitions use the latest buffered position.
- **Medium — Time handling:** clamp heartbeat catch-up cursor to current epoch; avoid
  overflowing live-view freshness arithmetic. App-lock grace uses monotonic time.
- **Medium — Retention:** use Long arithmetic from the first day multiplication;
  local cleanup no longer requires a working Keystore.
- **Medium — Location validation:** reject invalid/nonfinite coordinates and accuracy;
  prevent sharper out-of-order fixes from replacing the current selection.
- **Low — Media bounds:** refuse compressed image output still above the advertised cap.
- **Low — Input/backup hardening:** ASCII-only invite key digits, explicit Nostr event
  lengths/kind/timestamp limits, root-domain exclusion in legacy backup rules.
- **Maintenance:** remove unused CameraX dependencies (scanner uses JourneyApps).
  Align provider/PKIX/util and forced build dependencies at Bouncy Castle 1.86,
  documented upstream as a security-fix release. This does not establish exploitability
  of those fixed algorithms through this app's SHA/HMAC/HKDF/ChaCha use.
- **Documentation:** explain sandbox-only Room protection and explicit identity export;
  correct the reliability claim from relay acknowledgement to socket acceptance.

## Regression tests

NIP-44 header/bucket boundary vectors, UTF-8 byte lengths, malformed padding/resource
limits; malformed event fields; group/private-thread isolation; invalid/out-of-order
location; exact/oversized streams; tracking consent and stranger role changes;
rotating-sender dedup bounds. 151 unit tests pass, none skipped.

## Remaining risks and follow-up

1. Room stores plaintext history/messages in app-private storage. Encrypted-at-rest
   storage requires a deliberate key-lifecycle design and tested migration. App lock is
   a UI gate, not database encryption.
2. Queued rows are deleted on socket send acceptance, not relay `OK true`. A disconnect
   can still lose a copy. A durable acknowledged outbox needs event-ID tracking,
   resend/expiry semantics and protocol integration tests.
3. Relay size checks run after OkHttp assembles a text frame; they bound parsing/crypto
   work, not all transport receive allocations or signed-event flood rates.
4. Group ownership is trust-on-first-use. Delayed signed membership payloads can roll
   back membership without a versioned group-control protocol.
5. Local removal/retention are separate from the receiver's buffer; remote deletion
   coordination is improved, but comprehensive cross-producer race tests remain needed.
6. Broad exception handling and background state machines need lifecycle/cancellation
   tests. UI-only supervision guards are not a hardware security boundary.
7. Conversation/history queries, indexes and rendering require representative device
   profiling. No runtime speedup is claimed; redundant padding allocation was removed.
8. osmdroid 6.1.20 is archived. No upstream published security advisory was found;
   this is not proof of vulnerability absence. Map-stack replacement is separate work.
9. Device tests remain necessary: Keystore/biometrics, permission revocation, foreground
   services/Doze, camera, media playback, migration instrumentation, two-device relay
   delivery, and the receive-only consent/reject/accept flow. None performed here.

## References

- https://github.com/nostr-protocol/nips/blob/master/44.md
- https://github.com/nostr-protocol/nips/blob/master/01.md
- https://www.bouncycastle.org/resources/new-release-bouncy-castle-java-1-86/
- https://github.com/osmdroid/osmdroid/security

## Verification

Workspace-local JDK 21, configured Java 17 compilation toolchain, Android SDK 37.0.
Toolchain/logs excluded from Git under `.audit-tools/`. Gradle's default 6 GiB heap
exceeded VM capacity; checks use one worker and smaller heap/metaspace budgets.

- `testDebugUnitTest`: **PASS**, 151 tests / 21 suites, zero failures/errors/skips.
  Includes Kotlin/Java compilation and Hilt processing for the final sources.
- `git diff --check`: **PASS**.
- `lintDebug`: **NOT COMPLETED**. Multiple full-analysis attempts exceeded the
  tool/VM resource budget; lower heaps caused sustained GC pressure and larger heaps
  were terminated. No lint rules were suppressed to make this audit pass. Logs:
  `.audit-tools/lint*.log`.
- `assembleDebug`: **NOT COMPLETED**. Compilation/resource steps passed; full APK
  packaging stalled at `mergeExtDexDebug` with heap pressure and the command timed out.
  No APK artifact was produced. Log: `.audit-tools/apk.log`.
- Earlier bootstrap attempts were killed by memory exhaustion; a 256 MiB metaspace
  attempt failed explicitly at KSP. Successful unit verification used one worker,
  512 MiB heap, 384 MiB metaspace, SerialGC, two active processors and in-process Kotlin.
  These are invocation overrides only; repository build settings were preserved.
- Android instrumentation and interactive app verification: **NOT RUN**, no device.
