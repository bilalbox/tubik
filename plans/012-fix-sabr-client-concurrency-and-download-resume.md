# Plan 012: Fix `SabrClient` concurrency, `DefaultSabrChunkSource` negative index, and `SabrDownloadProvider` truncation/resume

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f44e95979..HEAD -- app/src/main/java/com/github/libretube/player/parser/SabrClient.kt app/src/main/java/com/github/libretube/player/DefaultSabrChunkSource.kt app/src/main/java/com/github/libretube/repo/SabrDownloadProvider.kt app/src/test/java/com/github/libretube/player/parser/SabrClientTest.kt`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED
- **Depends on**: `plans/010-fix-download-service-and-raw-provider.md`, `plans/011-room-migration-26-to-27-dead-code-and-deps-cleanup.md`
- **Category**: bug
- **Planned at**: commit `f44e95979`, 2026-11-28

## Why this matters

1. `SabrClient.getNextSegment` reads `initializedFormats[itag]` and mutates `bufferedSegments.keys.retainAll(...)` *before* entering `withContext(dispatcher)`, while audio and video `DefaultSabrChunkSource` loaders call it concurrently from separate Media3 threads (`ConcurrentModificationException` hazard). `DefaultSabrChunkSource` also indexes `representationHolders[trackSelection.indexOf(chunk.trackFormat)]` without checking `-1`.
2. `SabrDownloadProvider` checks `downloadHandle.nextSegmentNumber < endSegmentNumber` after setting `nextSegmentNumber = segment.sequenceNumber + 1`, silently dropping the final 2–10 s segment of every SABR download. Worse, on pause/resume, a new `SabrDownloadProvider` starts with `initSegment == null` and `nextSegmentNumber = 0L` while `DownloadService` opens the file with `StandardOpenOption.APPEND`, writing a duplicate init segment and segment 1 into the middle of the partial file.

## Current state

- `app/src/main/java/com/github/libretube/player/parser/SabrClient.kt:321-355`:
  - `initializedFormats[itag]?.duration` (line 333) and `initializedFormats[itag]?.bufferedSegments?.keys?.retainAll(...)` (lines 346-349) run outside `withContext(dispatcher)` (line 353).
- `app/src/main/java/com/github/libretube/player/DefaultSabrChunkSource.kt:349-350, 379-380, 411-412`: calls `trackSelection.indexOf(chunk.trackFormat)` without guarding `< 0`.
- `app/src/main/java/com/github/libretube/repo/SabrDownloadProvider.kt:49-106`:
  - `if (downloadHandle.initSegment == null)` always fetches & writes `initSegment` to `sink`, and `downloadHandle.nextSegmentNumber = 0L` starts at segment 1 even on resume.
  - Line 99: `endSegmentNumber != null && downloadHandle.nextSegmentNumber < endSegmentNumber` returns `DownloadComplete` as soon as `nextSegmentNumber == endSegmentNumber` (skipping the last segment).
- Plan 011 added `DownloadItem.sabrNextSegmentNumber: Long? = null`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Fast gate | `./tools/quality/gate.sh fast` | exit 0 |
| SABR tests | `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.player.parser.SabrClientTest" --tests "com.github.libretube.repo.SabrDownloadProviderTest"` | BUILD SUCCESSFUL, all tests pass |
| Full gate | `./tools/quality/gate.sh full` | exit 0 |

## Scope

**In scope** (the only files you should modify/create):
- `app/src/main/java/com/github/libretube/player/parser/SabrClient.kt`
- `app/src/main/java/com/github/libretube/player/DefaultSabrChunkSource.kt`
- `app/src/main/java/com/github/libretube/repo/SabrDownloadProvider.kt`
- `app/src/test/java/com/github/libretube/player/parser/SabrClientTest.kt`
- `app/src/test/java/com/github/libretube/repo/SabrDownloadProviderTest.kt` (create)

**Out of scope** (do NOT touch):
- `DownloadItem.kt`, `AppDatabase.kt` (schema column `sabrNextSegmentNumber` is already added by Plan 011)
- `DownloadService.kt`

## Git workflow

- Branch: `advisor/012-fix-sabr-client-concurrency-and-download-resume`
- Commit style: `fix(sabr): serialize SabrClient state access and fix SABR last-segment/resume bugs`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Serialize `SabrClient` map access and guard `DefaultSabrChunkSource` track index

1. In `SabrClient.kt`: move `fatalError` check, `initializedFormats[itag]?.duration` assertion, and `initializedFormats[itag]?.bufferedSegments?.keys?.retainAll(...)` INSIDE `withContext(dispatcher)` in `getNextSegment`. Also wrap `getEndSegmentNumber` in `runBlocking { withContext(dispatcher) { ... } }` or use `ConcurrentHashMap` for `initializedFormats` so reads never race with `retainAll`.
2. In `DefaultSabrChunkSource.kt`: at `onChunkLoadCompleted` (line 349), `onChunkLoadError` (line 379), and `FALLBACK_TYPE_TRACK` (line 411), bind `val trackIndex = trackSelection.indexOf(chunk.trackFormat)` and return early (`return` / `return false`) if `trackIndex < 0`.

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 2: Fix `SabrDownloadProvider` last-segment off-by-one and pause/resume corruption

In `SabrDownloadProvider.kt`:
1. Completion boundary (line 99): change `downloadHandle.nextSegmentNumber < endSegmentNumber` to `downloadHandle.nextSegmentNumber <= endSegmentNumber`.
2. Resume handling:
   - Track whether this `SabrDownloadProvider` has already handled resume initialization (`private var resumedFromPartial = false`).
   - On the first call when `downloadHandle.initSegment == null`:
     - Check `val hasPartialBytes = runCatching { item.path.fileSize() > 0L }.getOrDefault(false)`.
     - Fetch `initSegment` from `sabrClient.getNextSegment(initRequest)` (needed to populate `initializedFormats` and `endSegmentNumber` in `SabrClient`).
     - If `hasPartialBytes && item.sabrNextSegmentNumber != null && item.sabrNextSegmentNumber!! > initSegment.sequenceNumber`: do **NOT** write `initSegment.data` to `sink` again; set `downloadHandle.nextSegmentNumber = item.sabrNextSegmentNumber!!`.
     - Else: if `hasPartialBytes` (legacy partial file with unknown segment number), truncate `item.path` (or reset `sink` / `Files.write(item.path, ByteArray(0))` if empty/unknown — note `DownloadService` holds `sink` in `APPEND` mode; better: only skip writing `initSegment` when `sabrNextSegmentNumber != null`, and for `sabrNextSegmentNumber == null` with `hasPartialBytes` write `initSegment` only after resetting `item.currentDownloadPositionMillis = 0L` and truncating, OR if `DownloadService` opens `sink`, handle `sabrNextSegmentNumber` cleanly).
   - After writing each media segment (`line 86`), update BOTH `item.currentDownloadPositionMillis = currentPositionMillis` AND `item.sabrNextSegmentNumber = downloadHandle.nextSegmentNumber` before `downloadDao().updateDownloadItem(item)`.

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 3: Add tests and run full gate

- In `SabrClientTest.kt`: add a concurrent test calling `getNextSegment` from two coroutines on `Dispatchers.Default` (simulating audio + video loaders) to verify no `ConcurrentModificationException`.
- Create `SabrDownloadProviderTest.kt`: test (1) a 3-segment SABR stream (`endSegmentNumber = 3`) downloads segment 1, 2, AND 3 before returning `DownloadComplete`; (2) pausing after segment 1 (`sabrNextSegmentNumber = 2`) and resuming with a new `SabrDownloadProvider` does NOT re-write `initSegment` bytes and resumes at segment 2.

**Verify**: `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.player.parser.SabrClientTest" --tests "com.github.libretube.repo.SabrDownloadProviderTest"` → all pass; `./tools/quality/gate.sh full` → exit 0.

## Test plan

- `SabrClientTest.kt` and `SabrDownloadProviderTest.kt`.
- Verification: `./tools/quality/gate.sh full` → exit 0.

## Done criteria

- [ ] `SabrClient` reads/mutates `initializedFormats` and `bufferedSegments` exclusively inside `dispatcher`.
- [ ] `DefaultSabrChunkSource` guards `trackIndex < 0`.
- [ ] `SabrDownloadProvider` downloads the final `endSegmentNumber` segment and resumes without duplicating init/segment-1 bytes.
- [ ] `./tools/quality/gate.sh full` exits 0.
- [ ] `plans/README.md` status row updated.

## STOP conditions

Stop and report back (do not improvise) if:
- `DownloadItem.sabrNextSegmentNumber` does not exist (Plan 011 has not landed yet).
- `SabrDownloadProvider` requires `DownloadService` to close/reopen `sink` for legacy partial truncation — stop and report if truncation via `sink` is blocked by `APPEND` mode.

## Maintenance notes

- Keep `SabrDownloadProvider` unit-testable by allowing `SabrClient` / `DownloadDao` persistence hooks to be injected or faked in tests.
