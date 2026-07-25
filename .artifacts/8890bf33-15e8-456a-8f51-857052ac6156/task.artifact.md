# Tasks - Fix RetentionEnforcementWorker Cancellation

- [x] Update `RetentionEnforcementWorker.kt`
    - [x] Add `isStopped` checks in per-peer loop
    - [x] Rethrow `CancellationException` in `doWork`
    - [x] Change `ExistingPeriodicWorkPolicy.UPDATE` to `KEEP`
- [x] Update `MissedHeartbeatWorker.kt`
    - [x] Add `isStopped` check in loop
- [x] Verify build and changes
- [x] Create walkthrough
