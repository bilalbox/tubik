# Plan 006: Query `watchPosition` directly, fix `LIKE` scan, and batch N+1 watch-status lookups

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f44e95979..HEAD -- app/src/main/java/com/github/libretube/db/dao/WatchPositionDao.kt app/src/main/java/com/github/libretube/db/DatabaseHelper.kt app/src/main/java/com/github/libretube/repo/UserDataRepository.kt app/src/main/java/com/github/libretube/repo/LocalUserDataRepository.kt app/src/main/java/com/github/libretube/repo/SeriesRepository.kt`
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

`DatabaseHelper.getWatchPosition(videoId)` currently fetches watch position through `UserDataRepository.getFromWatchHistory(videoId)`, which runs a `LEFT JOIN` starting from `watchHistoryItem`. Whenever Watch History is disabled (with Watch Positions still on) or cleared, `getFromWatchHistory` returns `null`, breaking playback resume, Hide Watched, Episodes unwatched filtering, Series unwatched counts, and Mark as Watched/Unwatched. At the same time, `WatchPositionDao.findById` uses `LIKE` on the primary key (defeating the B-tree index and treating `_` in YouTube video IDs as a wildcard), and `filterUnwatched`, `SeriesRepository.summaries`, `PlaylistFragment`, `DownloadsAdapter`, and `PlayingQueueSheet` query SQLite once per video in a loop (N+1).

## Current state

- `app/src/main/java/com/github/libretube/db/dao/WatchPositionDao.kt:10-15`:
  ```kotlin
  @Dao
  interface WatchPositionDao {
      @Query("SELECT * FROM watchPosition") suspend fun getAll(): List<WatchPosition>

      @Query("SELECT * FROM watchPosition WHERE videoId LIKE :videoId LIMIT 1")
      suspend fun findById(videoId: String): WatchPosition?
  ```
- `app/src/main/java/com/github/libretube/db/DatabaseHelper.kt:25-37, 57-61`:
  ```kotlin
  suspend fun getWatchPosition(videoId: String) = runCatching {
      UserDataRepositoryHelper.userDataRepository.getFromWatchHistory(videoId)
  }
      .getOrNull()
      ?.metadata
      ?.positionMillis
  ...
  suspend fun filterUnwatched(streams: List<StreamItem>): List<StreamItem> {
      return streams.filter {
          !isVideoWatched(it.url.orEmpty().toID(), it.duration ?: 0)
      }
  }
  ```
- `app/src/main/java/com/github/libretube/services/OnlinePlayerService.kt:267-277` and `VideoOptionsBottomSheet.kt:221` also read position via `getFromWatchHistory(videoId)?.metadata?.positionMillis`.
- `app/src/main/java/com/github/libretube/ui/fragments/LibraryFragment.kt:178-180` passes `DatabaseHelper.isVideoWatched` per-video into `SeriesRepository.instance.summaries { ... }`.
- `app/src/main/java/com/github/libretube/ui/fragments/PlaylistFragment.kt:401-405`, `DownloadsAdapter.kt:195-204`, and `PlayingQueueSheet.kt:215` call `DatabaseHelper.isVideoWatched` in loops.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Fast gate | `./tools/quality/gate.sh fast` | exit 0 |
| DB/Repo tests | `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.db.*" --tests "com.github.libretube.repo.*"` | BUILD SUCCESSFUL, all tests pass |
| Full gate | `./tools/quality/gate.sh full` | exit 0 |

## Scope

**In scope** (the only files you should modify/create):
- `app/src/main/java/com/github/libretube/db/dao/WatchPositionDao.kt`
- `app/src/main/java/com/github/libretube/repo/UserDataRepository.kt`
- `app/src/main/java/com/github/libretube/repo/LocalUserDataRepository.kt`
- `app/src/main/java/com/github/libretube/db/DatabaseHelper.kt`
- `app/src/main/java/com/github/libretube/repo/SeriesRepository.kt`
- `app/src/main/java/com/github/libretube/ui/fragments/LibraryFragment.kt`
- `app/src/main/java/com/github/libretube/ui/fragments/PlaylistFragment.kt`
- `app/src/main/java/com/github/libretube/ui/adapters/DownloadsAdapter.kt`
- `app/src/main/java/com/github/libretube/ui/sheets/VideoOptionsBottomSheet.kt`
- `app/src/main/java/com/github/libretube/ui/sheets/PlayingQueueSheet.kt`
- `app/src/main/java/com/github/libretube/services/OnlinePlayerService.kt`
- `app/src/test/java/com/github/libretube/db/WatchPositionLookupTest.kt` (create)
- `app/src/test/java/com/github/libretube/repo/LocalUserDataRepositoryTest.kt`

**Out of scope** (do NOT touch):
- `WatchHistoryDao.kt` (its `LIKE`-free SQL `WatchHistoryDaoWatchedRuleTest` is separate)
- `AppDatabase.kt` (DAO query change `LIKE` -> `=` and adding `findByIds` do NOT change Room schema)

## Git workflow

- Branch: `advisor/006-watch-position-direct-query-and-batching`
- Commit style: `fix(db): query watch positions directly and batch unwatched lookups`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Fix `WatchPositionDao` and add batched `findByIds`

In `WatchPositionDao.kt`:
- Change `WHERE videoId LIKE :videoId LIMIT 1` to `WHERE videoId = :videoId LIMIT 1`.
- Add:
  ```kotlin
  @Query("SELECT * FROM watchPosition WHERE videoId IN (:videoIds)")
  suspend fun findByIds(videoIds: List<String>): List<WatchPosition>
  ```

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 2: Add `getWatchPosition` and `getWatchPositions` to `UserDataRepository` & `LocalUserDataRepository`

In `UserDataRepository.kt`, add:
```kotlin
suspend fun getWatchPosition(videoId: String): Long? = null

suspend fun getWatchPositions(videoIds: Collection<String>): Map<String, Long> = emptyMap()
```
In `LocalUserDataRepository.kt`, implement both directly via `Database.watchPositionDao()` (never `watchHistoryDao()`):
- `getWatchPosition(videoId)` → `Database.watchPositionDao().findById(videoId)?.position`
- `getWatchPositions(videoIds)` → if empty return `emptyMap()`, otherwise `videoIds.distinct().chunked(900)` querying `findByIds(chunk)` and associating `it.videoId to it.position`.

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 3: Batch `DatabaseHelper.filterUnwatched` and update direct position callers

- In `DatabaseHelper.kt`:
  - Update `getWatchPosition(videoId)` to call `UserDataRepositoryHelper.userDataRepository.getWatchPosition(videoId)`.
  - Add `suspend fun getWatchPositions(videoIds: Collection<String>): Map<String, Long>` wrapping `UserDataRepositoryHelper.userDataRepository.getWatchPositions(videoIds)` in `withContext(Dispatchers.IO)` + `runCatching { ... }.getOrDefault(emptyMap())`.
  - Rewrite `filterUnwatched(streams)`:
    ```kotlin
    suspend fun filterUnwatched(streams: List<StreamItem>): List<StreamItem> {
        if (streams.isEmpty()) return emptyList()
        val positions = getWatchPositions(streams.mapNotNull { it.url?.toID()?.takeIf(String::isNotEmpty) })
        return streams.filter { stream ->
            val videoId = stream.url.orEmpty().toID()
            val position = positions[videoId] ?: return@filter true
            !isVideoWatched(position, stream.duration ?: 0)
        }
    }
    ```
- Update `OnlinePlayerService.configurePlayer` (`line 268`) and `VideoOptionsBottomSheet.getOptionsForNotActivePlayback` (`line 221`) to call `DatabaseHelper.getWatchPosition(videoId)` instead of `getFromWatchHistory(videoId)?.metadata?.positionMillis`.
- Update `PlaylistFragment.startVideoItemPlayback`, `DownloadsAdapter.deleteAllDownloads` (note: keep `runBlocking` only if not yet refactored, or resolve positions once before `partition`), `PlayingQueueSheet`, and `LibraryFragment.initBookmarks` / `SeriesRepository.summaries` to batch-fetch positions via `DatabaseHelper.getWatchPositions(...)` once per list instead of querying per video.

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 4: Add `WatchPositionLookupTest.kt` and run full gate

Create `app/src/test/java/com/github/libretube/db/WatchPositionLookupTest.kt` (modeled after `app/src/test/java/com/github/libretube/db/SeriesDatabaseTest.kt` with Robolectric + in-memory `AppDatabase`):
1. Insert a `WatchPosition("vid_1", 45_000L)` with NO `WatchHistoryItem` row; verify `watchPositionDao().findById("vid_1")` returns `45_000L` and `findById("vidA1")` returns `null` (proving `_` is not a SQL `LIKE` wildcard).
2. Insert >950 `WatchPosition` rows and verify chunked `findByIds` returns all matching positions.
3. Verify `DatabaseHelper.isVideoWatched` / `filterUnwatched` semantics when `watchHistoryItem` is absent.

**Verify**: `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.db.WatchPositionLookupTest"` → all pass; `./tools/quality/gate.sh full` → exit 0.

## Test plan

- `app/src/test/java/com/github/libretube/db/WatchPositionLookupTest.kt` (new) + `LocalUserDataRepositoryTest.kt` (verify `getWatchPosition`/`getWatchPositions` with empty history).
- Verification: `./tools/quality/gate.sh full` → exit 0.

## Done criteria

- [ ] `WatchPositionDao.findById` uses `=` instead of `LIKE`, and `findByIds` exists.
- [ ] `DatabaseHelper.getWatchPosition`, `OnlinePlayerService`, and `VideoOptionsBottomSheet` read `watchPosition` directly without requiring a `watchHistoryItem` row.
- [ ] `DatabaseHelper.filterUnwatched`, `SeriesRepository.summaries`, `PlaylistFragment`, `DownloadsAdapter`, and `PlayingQueueSheet` batch position lookups instead of querying per video.
- [ ] `./tools/quality/gate.sh full` exits 0.
- [ ] `plans/README.md` status row updated.

## STOP conditions

Stop and report back (do not improvise) if:
- Any caller of `getFromWatchHistory` actually needs `WatchHistoryItem` metadata (title/uploader) rather than `positionMillis` — leave those callers on `getFromWatchHistory`.
- Room compilation reports a schema change (it should not; if it does, stop and report).

## Maintenance notes

- `WatchHistoryDao` still repeats `isVideoWatched` in SQL for the paginated Watch History screen; keep both in sync as documented in `DatabaseHelper.kt:40-44`.
