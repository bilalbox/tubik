# Plan 008: Fix local playlist removal by `videoId` and replace full-table playlist loads

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f44e95979..HEAD -- app/src/main/java/com/github/libretube/db/dao/LocalPlaylistsDao.kt app/src/main/java/com/github/libretube/repo/LocalUserDataRepository.kt app/src/main/java/com/github/libretube/helpers/ImportHelper.kt app/src/test/java/com/github/libretube/repo/LocalUserDataRepositoryTest.kt`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `f44e95979`, 2026-11-28

## Why this matters

When the **Allowlist** is on, `LocalUserDataRepository.getPlaylist` returns a filtered list of videos from **Allowed Channels**, so the UI's `index` is a filtered index. Yet `LocalUserDataRepository.removeFromPlaylist(playlistId, videoId, index)` ignores `videoId` and deletes `transaction.videos[index]` from the unfiltered Room list — silently deleting a hidden non-allowed video (or throwing `IndexOutOfBoundsException`) while leaving the intended video in the playlist. Additionally, five playlist methods call `localPlaylistsDao().getAll().first { ... }`, loading every playlist and item in the database for a single-playlist edit, `addToPlaylist` loops without a Room transaction, and `ImportHelper` inserts Takeout watch history items one by one instead of using `insertAll`.

## Current state

- `app/src/main/java/com/github/libretube/db/dao/LocalPlaylistsDao.kt:14-18` has `getAll()` and no `getById(playlistId: Int)`.
- `app/src/main/java/com/github/libretube/repo/LocalUserDataRepository.kt:69-81, 97-175`:
  - `getPlaylist`, `addToPlaylist`, `renamePlaylist`, `changePlaylistDescription`, and `removeFromPlaylist` all call `Database.localPlaylistsDao().getAll().first { it.playlist.id.toString() == playlistId }` (or `localDataSource.getLocalPlaylists().first { ... }`).
  - `removeFromPlaylist(playlistId, videoId, index)` (`lines 156-175`):
    ```kotlin
    val transaction =
        Database.localPlaylistsDao().getAll().first { it.playlist.id.toString() == playlistId }
    Database.localPlaylistsDao()
        .removePlaylistVideo(
            transaction.videos[index],
        )
    if (index == 0) {
        transaction.playlist.thumbnailUrl =
            transaction.videos.getOrNull(1)?.thumbnailUrl.orEmpty()
    }
    Database.localPlaylistsDao().updatePlaylist(transaction.playlist)
    ```
- `app/src/main/java/com/github/libretube/helpers/ImportHelper.kt:395-397`:
  ```kotlin
  for (video in videos) {
      DatabaseHolder.Database.watchHistoryDao().insert(video)
  }
  ```

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Fast gate | `./tools/quality/gate.sh fast` | exit 0 |
| Repo tests | `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.repo.LocalUserDataRepositoryTest"` | BUILD SUCCESSFUL, all tests pass |
| Full gate | `./tools/quality/gate.sh full` | exit 0 |

## Scope

**In scope** (the only files you should modify):
- `app/src/main/java/com/github/libretube/db/dao/LocalPlaylistsDao.kt`
- `app/src/main/java/com/github/libretube/repo/LocalUserDataRepository.kt` (and `LocalUserDataSource` / `RoomLocalUserDataSource` in the same package/file if `getPlaylist` uses it)
- `app/src/main/java/com/github/libretube/helpers/ImportHelper.kt`
- `app/src/test/java/com/github/libretube/repo/LocalUserDataRepositoryTest.kt`

**Out of scope** (do NOT touch):
- `AppDatabase.kt` (DAO query additions do NOT change Room schema)
- `PlaylistFragment.kt`

## Git workflow

- Branch: `advisor/008-fix-local-playlist-removal-and-targeted-queries`
- Commit style: `fix(repo): remove playlist videos by videoId and use targeted playlist queries`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Add `getById(playlistId: Int)` to `LocalPlaylistsDao` and `LocalUserDataSource`

In `LocalPlaylistsDao.kt`:
```kotlin
@Transaction
@Query("SELECT * FROM LocalPlaylist WHERE id = :playlistId LIMIT 1")
suspend fun getById(playlistId: Int): LocalPlaylistWithVideos?
```
If `LocalUserDataSource` exposes `getLocalPlaylists()`, add `suspend fun getLocalPlaylist(playlistId: String): LocalPlaylistWithVideos?` backed by `playlistId.toIntOrNull()?.let { Database.localPlaylistsDao().getById(it) }` in `RoomLocalUserDataSource`.

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 2: Fix `removeFromPlaylist`, wrap `addToPlaylist` in a transaction, and switch to `getById`

In `LocalUserDataRepository.kt`:
1. Replace all 5 `getAll().first { ... }` lookups in `getPlaylist`, `addToPlaylist`, `renamePlaylist`, `changePlaylistDescription`, and `removeFromPlaylist` with `getById` (throwing `NoSuchElementException` or returning `false` cleanly if missing, matching existing contract).
2. In `removeFromPlaylist(playlistId, videoId, index)`:
   - Find the target `LocalPlaylistItem`: `val target = transaction.videos.firstOrNull { it.videoId == videoId } ?: transaction.videos.getOrNull(index) ?: return false`.
   - Delete `target` via `removePlaylistVideo(target)`.
   - Compute `remaining = transaction.videos.filter { it.id != target.id }`; if `transaction.playlist.thumbnailUrl == target.thumbnailUrl` (or `transaction.playlist.thumbnailUrl.isEmpty()`), update `transaction.playlist.thumbnailUrl = remaining.firstOrNull()?.thumbnailUrl.orEmpty()` and call `updatePlaylist(transaction.playlist)`.
3. Wrap `addToPlaylist`'s loop in `Database.withTransaction { ... }`.
4. In `ImportHelper.kt:395-397`: replace the per-row `for (video in videos) watchHistoryDao().insert(video)` loop with `DatabaseHolder.Database.watchHistoryDao().insertAll(videos)`.

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 3: Expand `LocalUserDataRepositoryTest.kt` and run full gate

In `LocalUserDataRepositoryTest.kt`, add a test with **Allowlist** enabled where a local playlist contains a hidden non-allowed video at raw index 0 and an allowed video (`"allowed_vid"`) at raw index 1:
- Call `getPlaylist(id)` → returns only `"allowed_vid"` (at filtered index 0).
- Call `removeFromPlaylist(id, "allowed_vid", 0)` → verify `"allowed_vid"` is removed and the hidden non-allowed item remains intact in the store.

**Verify**: `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.repo.LocalUserDataRepositoryTest"` → all pass; `./tools/quality/gate.sh full` → exit 0.

## Test plan

- `app/src/test/java/com/github/libretube/repo/LocalUserDataRepositoryTest.kt` (add Allowlist-filtered `removeFromPlaylist` test).
- Verification: `./tools/quality/gate.sh full` → exit 0.

## Done criteria

- [ ] `LocalUserDataRepository.removeFromPlaylist` deletes by `videoId` rather than unfiltered `videos[index]`.
- [ ] All 5 single-playlist operations use `getById` instead of `getAll().first { ... }`.
- [ ] `ImportHelper` uses `watchHistoryDao().insertAll(videos)`.
- [ ] `./tools/quality/gate.sh full` exits 0.
- [ ] `plans/README.md` status row updated.

## STOP conditions

Stop and report back (do not improvise) if:
- `LocalUserDataRepositoryTest` uses a fake `LocalUserDataSource` that lacks `removeFromPlaylist` wiring — check how `LocalUserDataRepository` routes `removeFromPlaylist` through `localDataSource` vs `DatabaseHolder.Database` and keep tests consistent.

## Maintenance notes

- If duplicate `videoId` entries in a single local playlist are ever supported, pass `LocalPlaylistItem.id` instead of matching `firstOrNull { it.videoId == videoId }`.
