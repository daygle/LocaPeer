# Implementation Plan - Fix RetentionEnforcementWorker Cancellation Issue

The `RetentionEnforcementWorker` is experiencing `WorkerStoppedException` because it is not cooperative with WorkManager's cancellation/stop signals and it improperly catches and logs cancellation exceptions as errors.

## Proposed Changes

### [Component] Background Tasks

#### [MODIFY] [RetentionEnforcementWorker.kt](file:///C:/Users/glen/StudioProjects/LocaPeer/app/src/main/java/com/locapeer/subscriber/RetentionEnforcementWorker.kt)
- Add `isStopped` checks within the per-peer remote purge loop to ensure the worker exits gracefully if stopped by WorkManager.
- Update the `catch (e: Exception)` block to rethrow `CancellationException` so WorkManager can handle its own lifecycle correctly without logging it as a failure.
- Change `ExistingPeriodicWorkPolicy.UPDATE` to `ExistingPeriodicWorkPolicy.KEEP` in the `schedule` method to avoid interrupting a running worker during app restarts, as the battery constraint has already been adopted.

#### [MODIFY] [MissedHeartbeatWorker.kt](file:///C:/Users/glen/StudioProjects/LocaPeer/app/src/main/java/com/locapeer/subscriber/MissedHeartbeatWorker.kt)
- Add `isStopped` check within the `receiveContacts.forEach` loop for consistency and to ensure it also honors stop signals during heavy processing.

## Verification Plan

### Automated Tests
- Run existing unit tests to ensure no regressions in retention logic.
- Since this is a WorkManager lifecycle issue, manual verification and log observation are more effective.

### Manual Verification
- Deploy the app and monitor Logcat.
- Verify that `RetentionEnforcementWorker` no longer logs "Failed to send purge requests" when it is stopped (e.g., during app restarts or constraint changes).
- Verify that the worker eventually completes its task when constraints are met.
