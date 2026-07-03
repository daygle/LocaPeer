# Room schema snapshots

Room's compiler writes a JSON snapshot of every database schema version into
this directory (`com.locapeer.data.AppDatabase/<version>.json`) whenever the
app is built.

**Commit these files.** They are the historical record of each shipped schema:
future migrations are written against them, and Room's `MigrationTestHelper`
replays them to verify that migrations produce exactly the schema the entities
declare. Never edit them by hand.
