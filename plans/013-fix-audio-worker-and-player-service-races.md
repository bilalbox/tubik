# Plan 013: Fix `MusicRemovalAudioProcessor` worker overlap and player-service track-switch/lifecycle races

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f44e95979..HEAD -- app/src/main/java/com/github/libretube/player/MusicRemovalAudioProcessor.kt app/src/main/java/com/github/libretube/player/MusicRemovalEngine.kt app/src/main/java/com/github/libretube/services/AbstractPlayerService.kt app/src/main/java/com/github/libretube/services/OnlinePlayerService.kt app/src/main/java/com/github/libretube/services/OfflinePlayerService.kt`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `f44e95979`, 2026-11-28

## Why this matters

1. In `MusicRemovalAudioProcessor`, if `stopWorker()` times out after `500 ms` (e.g., while ONNX is loading or finishing a slow hop), `onReset()` drains `inputRing` on the caller thread while the old worker is still running, the next track spawns a second concurrent worker on the same rings and unsynchronized `SileroVadGate` singleton, and the old worker's `finally` block unconditionally calls `engine.close()` and `workerRunning.set(false)`, killing active **Music Removal**. Also `StageManager.close()` leaks loaded models if closed before any hop runs.
2. In `AbstractPlayerService` / `OnlinePlayerService`, `navigateVideo()` launches `startPlayback()` on an unmanaged `CoroutineScope(Dispatchers.IO)`, `fetchVideoInfoJob = null` clobbers newer jobs during rapid skips, `configurePlayer()` seeks using mutable `videoId`, and both `OnlinePlayerService.scope` and `OfflinePlayerService.scope` lack `SupervisorJob()` (with `OfflinePlayerService` never cancelling `scope` and double-calling `onDestroy()` in `onTaskRemoved()`, plus picking non-allowed downloads when shuffling under **Allowlist**).

## Current state

- `app/src/main/java/com/github/libretube/player/MusicRemovalAudioProcessor.kt:495-586`: `stopWorker()` waits up to 500 ms and returns; `runWorkerLoop()` `finally` calls `engine.close()` and `workerRunning.set(false)` without checking worker identity.
- `app/src/main/java/com/github/libretube/player/MusicRemovalEngine.kt:484-497`: `StageManager.close()` wraps `primaryModel?.close()`, `fallbackModel?.close()`, `resolvedGate?.close()` inside `if (hasProcessedHops)`.
- `app/src/main/java/com/github/libretube/services/AbstractPlayerService.kt:75, 317-327`: `private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`; `navigateVideo` uses `CoroutineScope(Dispatchers.IO).launch`.
- `app/src/main/java/com/github/libretube/services/OnlinePlayerService.kt:74, 185-189, 258-278`: plain `CoroutineScope(Dispatchers.Main)`, `fetchVideoInfoJob = null`, `configurePlayer` uses mutable `videoId`.
- `app/src/main/java/com/github/libretube/services/OfflinePlayerService.kt:55, 92-111, 281-284`: uncancelled plain `scope`, `runBlocking(Dispatchers.IO)` without Allowlist filter on shuffle, `onTaskRemoved` calls `onDestroy()`.
- ADR-0002 constraint: **Music Removal** must remain fail-closed (**Overload Mute** on failure; never play unprocessed audio).

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Fast gate | `./tools/quality/gate.sh fast` | exit 0 |
| Player tests | `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.player.MusicRemoval*" --tests "com.github.libretube.services.*"` | BUILD SUCCESSFUL, all tests pass |
| Full gate | `./tools/quality/gate.sh full` | exit 0 |

## Scope

**In scope** (the only files you should modify/create):
- `app/src/main/java/com/github/libretube/player/MusicRemovalAudioProcessor.kt`
- `app/src/main/java/com/github/libretube/player/MusicRemovalEngine.kt`
- `app/src/main/java/com/github/libretube/services/AbstractPlayerService.kt`
- `app/src/main/java/com/github/libretube/services/OnlinePlayerService.kt`
- `app/src/main/java/com/github/libretube/services/OfflinePlayerService.kt`
- `app/src/test/java/com/github/libretube/player/MusicRemovalAudioProcessorTest.kt`
- `app/src/test/java/com/github/libretube/player/MusicRemovalEngineTest.kt`
- `app/src/test/java/com/github/libretube/services/OfflinePlayerShuffleFilterTest.kt` (create)

**Out of scope** (do NOT touch):
- `PlayerFragment.kt`, `AudioPlayerFragment.kt`, `SileroVadGate.kt` (unless a thin `synchronized` guard is strictly needed; prefer serializing worker lifecycle in `MusicRemovalAudioProcessor` so `SileroVadGate` stays single-threaded)

## Git workflow

- Branch: `advisor/013-fix-audio-worker-and-player-service-races`
- Commit style: `fix(player): serialize audio worker lifecycle and eliminate player service races`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Make `MusicRemovalAudioProcessor` worker lifecycle single-owner and fix `StageManager.close()`

1. In `MusicRemovalEngine.kt:484-497`: remove the `if (hasProcessedHops)` guard around closing `primaryModel`, `fallbackModel`, and `resolvedGate` in `StageManager.close()` (always `runCatching { ...?.close() }` for any non-null reference, and reset `hasProcessedHops = false`).
2. In `MusicRemovalAudioProcessor.kt`:
   - Give each worker thread a monotonic `workerId` (`AtomicLong`), stored in `activeWorkerId: AtomicLong`.
   - In `runWorkerLoop()`: serialize access to `engine` (or ensure `stopWorker()` / `ensureWorkerStarted()` waits on `previousThread.join()` / a `workerLock` before `ensureWorkerStarted()` starts a new thread on `engine` and the rings, and in `finally`, ONLY update `workerRunning`, `workerThreadRef`, and `engine.close()` if `activeWorkerId.get() == myWorkerId` — or close `engine` only when no active worker owns it).
   - Ensure `onReset()` does not drain `inputRing` concurrently while an old worker thread is still alive (if `stopWorker()` timed out, wait or mark generation invalid and let the exiting worker drain/exit before the new worker starts).

**Verify**: `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.player.MusicRemoval*"` → all pass.

### Step 2: Consolidate `serviceScope` and fix `OnlinePlayerService` / `OfflinePlayerService` races

1. In `AbstractPlayerService.kt`:
   - Change `private val serviceScope` (line 75) to `protected val serviceScope`.
   - Track `private var transitionJob: Job? = null` and in `navigateVideo(videoId)` (lines 317-327), cancel `transitionJob` and launch `transitionJob = serviceScope.launch(Dispatchers.IO) { startPlayback() }`.
2. In `OnlinePlayerService.kt`:
   - Remove `private val scope = CoroutineScope(Dispatchers.Main)` (line 74) and use `serviceScope` everywhere (removing duplicate cancellation if `AbstractPlayerService.onDestroy()` already cancels `serviceScope`).
   - In `startPlayback()`: capture `val job = serviceScope.launch { ... }; fetchVideoInfoJob = job; job.join(); if (fetchVideoInfoJob === job) fetchVideoInfoJob = null`.
   - In `configurePlayer()`: capture `val targetVideoId = videoId` before launching the watch-position lookup, and only call `exoPlayer?.seekTo(it)` if `videoId == targetVideoId`.
3. In `OfflinePlayerService.kt`:
   - Remove `private val scope = CoroutineScope(Dispatchers.Main)` (line 55) and use `serviceScope`.
   - Replace `runBlocking(Dispatchers.IO)` in `onServiceCreated` (line 94) with `withContext(Dispatchers.IO)`, and filter shuffle candidates using `AccessControl.instance.snapshot().isChannelAllowed(it.uploaderUrl)` (extract a pure `internal fun selectAllowedShuffleDownloadId(...)` helper for unit testing).
   - In `onTaskRemoved()` (lines 281-284): remove the direct `onDestroy()` call (`super.onTaskRemoved(rootIntent)` + `stopSelf()` if needed, never calling `onDestroy()` directly).

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 3: Add regression tests and run full gate

- `MusicRemovalEngineTest.kt`: test `ensureGeometry` (loading model) followed by `close()` without processing any hops still closes the model.
- `MusicRemovalAudioProcessorTest.kt`: test that a slow/delayed worker exiting after `onReset()` + restart does NOT close the new worker's engine or clear `workerRunning`.
- `OfflinePlayerShuffleFilterTest.kt`: test `selectAllowedShuffleDownloadId` skips non-allowed downloads when **Allowlist** is on and returns an **Allowed Channel** download.

**Verify**: `./tools/quality/gate.sh full` → exit 0.

## Test plan

- `MusicRemovalEngineTest.kt`, `MusicRemovalAudioProcessorTest.kt`, and `OfflinePlayerShuffleFilterTest.kt`.
- Verification: `./tools/quality/gate.sh full` → exit 0.

## Done criteria

- [ ] Exiting worker in `MusicRemovalAudioProcessor` cannot close a replacement worker's `engine` or corrupt shared rings.
- [ ] `StageManager.close()` closes loaded models/gates even when zero hops were processed.
- [ ] `OnlinePlayerService` and `OfflinePlayerService` share `AbstractPlayerService.serviceScope` (`SupervisorJob`), `navigateVideo` cancels prior transitions, and `OfflinePlayerService` shuffle respects **Allowlist**.
- [ ] `./tools/quality/gate.sh full` exits 0.
- [ ] `plans/README.md` status row updated.

## STOP conditions

Stop and report back (do not improvise) if:
- `MusicRemovalAudioProcessorTest` fails with a deadlock if `onReset()` blocks on `thread.join()` — remember ExoPlayer tests may call `onReset()` from the test thread; keep `join` bounded or serialize worker handoff cleanly without deadlocking.

## Maintenance notes

- All `AbstractPlayerService` subclasses must use `serviceScope` so `onDestroy()` cancels every service coroutine uniformly.
