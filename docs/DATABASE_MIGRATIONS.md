# Database Migration Policy

User data — connections, Kapowarr profiles, pull list, reminders, read marks — must
**never** be destroyed by an app update. `cupcake.db` therefore has no destructive
migration fallback, in any build type. A missing migration fails loudly at open time
in development instead of silently wiping a beta install.

## Rules for any change to a Room entity or DAO schema

1. Bump `version` in `CupcakeDatabase`.
2. Add a `Migration(old, new)` in `app/src/main/java/com/cupcakecomics/data/Migrations.kt`
   and register it in `CupcakeMigrations.ALL`.
   - `ALTER TABLE ... ADD COLUMN ... NOT NULL DEFAULT x` must be mirrored with
     `@ColumnInfo(defaultValue = "x")` on the entity field, or Room's validation fails.
   - New tables: copy the `CREATE TABLE` DDL exactly as Room generates it (check the
     exported schema JSON after building).
3. Build once so ksp writes the new schema JSON under
   `app/schemas/com.cupcakecomics.data.CupcakeDatabase/`, and commit it.
4. Add or update migration tests:
   - Robolectric: `app/src/test/java/com/cupcakecomics/data/CupcakeMigrationTest.kt`
     seeds a real old-version database and opens it through Room.
   - Instrumented: `MigrationPreservationTest` (uses `MigrationTestHelper` with the
     committed schema JSONs as assets).
5. Do **not** reintroduce `fallbackToDestructiveMigration()` or
   `fallbackToDestructiveMigrationOnDowngrade()`.

## Other stores

- SharedPreferences (`cupcake_settings`, `cupcake_reader_settings`, etc.) and the
  EncryptedSharedPreferences credential store survive updates on their own.
- The legacy Bubble2 `comics.db` has a non-destructive `onUpgrade` in
  `com.nkanaev.comics.model.Storage.ComicDbHelper`; keep it that way — add columns
  with `ALTER TABLE`, never drop/recreate.
