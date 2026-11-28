# Plan 010: Fix `DownloadService` auto-resume/leaked executor and `RawByteStreamDownloadProvider` HTTP 200 loop

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f44e95979..HEAD -- app/src/main/java/com/github/libretube/services/DownloadService.kt app/src/main/java/com/github/libretube/repo/RawByteStreamDownloadProvider.kt`
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

`DownloadService` has three reliability bugs: (1) `downloadQueue: SparseBooleanArray` uses `false` for queued, paused, and failed downloads alike, so `startNextEnqueueDownload()` immediately resumes any download the user just paused or that just exhausted `MAX_SEGMENT_RETRIES`; (2) `DownloadService` creates a single-thread `Executor` and a detached `SupervisorJob()` passed to `lifecycleScope.launch(coroutineContext)`, leaking an OS thread and leaving coroutines uncancelled on `onDestroy()`; and (3) `sink` is not closed in `try/finally`. Meanwhile, `RawByteStreamDownloadProvider` loops infinitely appending on HTTP `200 OK` responses without `Content-Range` (e.g. subtitles) and ignores `StreamTransportContext` (sending a hardcoded Firefox User-Agent that fails with 403 on `IOS`/`ANDROID_KIDS` URLs).

## Current state

- `app/src/main/java/com/github/libretube/services/DownloadService.kt:84-94, 293-377, 683-688`:
  - `val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()`
  - `val coroutineContext = dispatcher + SupervisorJob()`
  - `downloadQueue = SparseBooleanArray()`
  - `startNextEnqueueDownload()` iterates `downloadQueue.keyIterator()` where `!downloadQueue[id]` and calls `resume(id)`.
  - `onDestroy()` never cancels the `SupervisorJob` or closes `dispatcher` or clears `cachedStreamsInfo`.
- `app/src/main/java/com/github/libretube/repo/RawByteStreamDownloadProvider.kt:88-100, 121-169`:
  - Always sends `Range: bytes=...` with hardcoded Firefox `USER_AGENT` and never handles HTTP `200` (non-206) as a complete single-shot response.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Fast gate | `./tools/quality/gate.sh fast` | exit 0 |
| Download tests | `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.services.DownloadQueueStateTest" --tests "com.github.libretube.repo.RawByteStreamDownloadProviderTest"` | BUILD SUCCESSFUL, all tests pass |
| Full gate | `./tools/quality/gate.sh full` | exit 0 |

## Scope

**In scope** (the only files you should modify/create):
- `app/src/main/java/com/github/libretube/services/DownloadService.kt`
- `app/src/main/java/com/github/libretube/repo/RawByteStreamDownloadProvider.kt`
- `app/src/test/java/com/github/libretube/services/DownloadQueueStateTest.kt` (create)
- `app/src/test/java/com/github/libretube/repo/RawByteStreamDownloadProviderTest.kt` (create)

**Out of scope** (do NOT touch):
- `SabrDownloadProvider.kt` (covered in a separate plan)
- `DownloadHelper.kt`, `DownloadsFragment.kt`

## Git workflow

- Branch: `advisor/010-fix-download-service-and-raw-provider`
- Commit style: `fix(downloads): fix queue state auto-resume, service cleanup, and HTTP 200 handling`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Replace `SparseBooleanArray` with explicit `DownloadQueueState` and fix `DownloadService` lifecycle

In `DownloadService.kt`:
1. Create a small package/internal `DownloadQueueTracker` (with `enum class State { QUEUED, RUNNING, PAUSED, FAILED }`) so `nextQueuedId()` ONLY returns items in `State.QUEUED` (never `PAUSED` or `FAILED`).
   - Enqueueing marks `QUEUED`; starting `downloadFile` marks `RUNNING`; `pause(id)` marks `PAUSED`; hitting `MAX_SEGMENT_RETRIES` or exception marks `FAILED`; `resume(id)` marks `QUEUED`/`RUNNING`; `DownloadComplete` or `delete` removes the entry.
2. Replace detached `SupervisorJob` + `lifecycleScope.launch(coroutineContext)` with:
   ```kotlin
   private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
   private val serviceScope = CoroutineScope(SupervisorJob() + dispatcher)
   ```
   Use `serviceScope.launch { ... }` for download tasks, and in `onDestroy()`: call `serviceScope.cancel()`, `dispatcher.close()`, and `cachedStreamsInfo.clear()`.
3. In `downloadFile`: wrap `sink` in `try { ... } finally { withContext(NonCancellable + Dispatchers.IO) { runCatching { sink.flush() }; runCatching { sink.close() } } }` so cleanup always runs before `startNextEnqueueDownload()` and `stopServiceIfDone()`.
4. Pass `transportContext` into `RawByteStreamDownloadProvider` at `DownloadService.kt:253, 268-270`.

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 2: Handle HTTP 200 non-Range responses and `StreamTransportContext` in `RawByteStreamDownloadProvider`

In `RawByteStreamDownloadProvider.kt`:
1. Add optional `private val transportContext: StreamTransportContext? = null` (and optional `httpClient` injection for unit testing). When `transportContext != null`, apply `transportContext.resolveUserAgent(url.toString())` (or its client/headers) instead of the hardcoded Firefox UA.
2. In `startConnection` / `downloadNextChunk`:
   - If `response.code == 200` (full body, NOT `206 Partial Content`):
     - If `alreadyRead > 0L`, signal that the partial file must be overwritten/restarted from offset 0 (or truncate `item.path` before writing so bytes are not appended after partial data).
     - Record `response.body.contentLength()` (if `> 0L`) into `item.downloadSize` (and persist via `downloadDao()`).
     - After streaming the `200` body to EOF, return `DownloadProgressResult.DownloadComplete` even if `item.downloadSize <= 0L` (chunked transfer).

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 3: Add unit tests and run full gate

Create:
- `DownloadQueueStateTest.kt`: verify `PAUSED` and `FAILED` items are never returned by `nextQueuedId()`, while `QUEUED` items are promoted in order.
- `RawByteStreamDownloadProviderTest.kt` (using OkHttp `Interceptor` stub, modeled after `AppUpdaterTest.kt`): verify HTTP `200` without `Content-Range` returns `DownloadComplete` on the first pass (no infinite loop), HTTP `206` returns `Progressed` until `downloadSize`, and `StreamTransportContext` User-Agent is sent.

**Verify**: `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.services.DownloadQueueStateTest" --tests "com.github.libretube.repo.RawByteStreamDownloadProviderTest"` → all pass; `./tools/quality/gate.sh full` → exit 0.

## Test plan

- `DownloadQueueStateTest.kt` and `RawByteStreamDownloadProviderTest.kt` (new).
- Verification: `./tools/quality/gate.sh full` → exit 0.

## Done criteria

- [ ] Pausing or failing a download in `DownloadService` never auto-resumes it via `startNextEnqueueDownload()`.
- [ ] `DownloadService.onDestroy()` cancels `serviceScope`, closes `dispatcher`, and clears `cachedStreamsInfo`.
- [ ] `sink` is closed in `try/finally` with `NonCancellable`.
- [ ] `RawByteStreamDownloadProvider` terminates cleanly on HTTP `200` and propagates `StreamTransportContext`.
- [ ] `./tools/quality/gate.sh full` exits 0.
- [ ] `plans/README.md` status row updated.

## STOP conditions

Stop and report back (do not improvise) if:
- `DownloadService` callers outside `DownloadService.kt` access `downloadQueue` directly (check with grep; none expected as it is `private`).

## Maintenance notes

- `DownloadQueueTracker` is intentionally a pure Kotlin state machine so future queue-priority or concurrency-limit changes can be unit-tested without Android `Service` scaffolding.
