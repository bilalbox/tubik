# Plan 018: Decompose player god classes (`PlayerHelper`, `CustomExoPlayerView`, `PlayerFragment`) and deduplicate mini-player shell

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f44e95979..HEAD -- app/src/main/java/com/github/libretube/helpers/PlayerHelper.kt app/src/main/java/com/github/libretube/ui/fragments/PlayerFragment.kt app/src/main/java/com/github/libretube/ui/fragments/AudioPlayerFragment.kt app/src/main/java/com/github/libretube/ui/views/CustomExoPlayerView.kt app/detekt-baseline.xml`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P3
- **Effort**: L
- **Risk**: MED
- **Depends on**: `plans/013-fix-audio-worker-and-player-service-races.md`, `plans/014-fix-main-thread-blocking-ui-leaks-and-crashes.md`, `plans/016-consolidate-user-data-repo-and-warnings-as-errors.md`
- **Category**: tech-debt
- **Planned at**: commit `f44e95979`, 2026-11-28

## Why this matters

Three player files total 4,253 lines (`CustomExoPlayerView.kt` 1,625 L, `PlayerFragment.kt` 1,589 L, `PlayerHelper.kt` 1,039 L) and account for the majority of `LargeClass`, `LongMethod`, `TooManyFunctions`, and `CyclomaticComplexMethod` suppressions in `app/detekt-baseline.xml`. `PlayerFragment` and `AudioPlayerFragment` also duplicate MotionLayout mini-player transition management and predictive-back `OnBackPressedCallback` logic, forcing parallel edits for **Screen Pin**, **Music Removal**, and mini-player state changes.

## Current state

- `app/src/main/java/com/github/libretube/helpers/PlayerHelper.kt` (1,039 L): mixes ExoPlayer construction, SimpleCache, PiP `RemoteAction`s, track-format helpers, SponsorBlock prefs, and watch-position persistence.
- `app/src/main/java/com/github/libretube/ui/fragments/PlayerFragment.kt:555-575, 649-714` and `AudioPlayerFragment.kt:262-291, 344-400`: duplicate MotionLayout transition listeners and predictive-back `OnBackPressedCallback` handling.
- `app/src/main/java/com/github/libretube/ui/views/CustomExoPlayerView.kt` (1,625 L): mixes touch gestures, brightness/volume HUD, captions styling, quality/audio/subtitle/speed dialogs, SponsorBlock UI, **Screen Pin**, and queue controls.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Fast gate | `./tools/quality/gate.sh fast` | exit 0 |
| Targeted tests | `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.helpers.*" --tests "com.github.libretube.ui.*"` | BUILD SUCCESSFUL, all tests pass |
| Full gate | `./tools/quality/gate.sh full` | exit 0 |

## Scope

**In scope** (the only files you should modify/create):
- `app/src/main/java/com/github/libretube/helpers/PlayerHelper.kt`
- `app/src/main/java/com/github/libretube/helpers/PlayerTrackUtils.kt` (create)
- `app/src/main/java/com/github/libretube/helpers/PlayerPipActions.kt` (create)
- `app/src/main/java/com/github/libretube/helpers/PlayerFactory.kt` (create)
- `app/src/main/java/com/github/libretube/ui/fragments/PlayerFragment.kt`
- `app/src/main/java/com/github/libretube/ui/fragments/AudioPlayerFragment.kt`
- `app/src/main/java/com/github/libretube/ui/controllers/MiniPlayerMotionController.kt` (create)
- `app/src/main/java/com/github/libretube/ui/views/CustomExoPlayerView.kt`
- `app/src/main/java/com/github/libretube/ui/controllers/PlayerGestureController.kt` (create)
- `app/src/main/java/com/github/libretube/ui/controllers/PlayerOptionDialogs.kt` (create)
- `app/detekt-baseline.xml` (prune resolved entries ONLY — never add entries)
- `baselines/file-size.json` (update downward if `run-file-size-ratchet.sh` supports `--prune` or baseline refresh)
- `app/src/test/java/com/github/libretube/helpers/PlayerTrackUtilsTest.kt` (create)
- `app/src/test/java/com/github/libretube/ui/controllers/MiniPlayerMotionControllerTest.kt` (create)

**Out of scope** (do NOT touch):
- `MainActivity.kt`, `OnlinePlayerService.kt`, `OfflinePlayerService.kt`, `MusicRemovalEngine.kt`
- Any behavioral change to **Music Removal** 1.5× speed cap, **Preferred Speed**, or **Screen Pin**

## Git workflow

- Branch: `advisor/018-decompose-player-god-classes`
- Commit style: `refactor(player): extract PlayerTrackUtils, MiniPlayerMotionController, and gesture/dialog controllers`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Write characterization tests for `PlayerHelper` pure helpers, then split `PlayerHelper.kt`

1. Read `PlayerHelper.kt` completely and write `PlayerTrackUtilsTest.kt` covering pure track-format/audio-language/quality/speed-cap helpers (`getPreferredAudioQuality`, `getAudioRole`, `clampSpeed`, etc.).
2. Extract `PlayerTrackUtils`, `PlayerPipActions`, and `PlayerFactory` into focused files (keeping thin delegating methods on `PlayerHelper` only if external callers are numerous, or updating callers cleanly).

**Verify**: `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.helpers.PlayerTrackUtilsTest"` → all pass; `./tools/quality/gate.sh fast` → exit 0.

### Step 2: Deduplicate mini-player MotionLayout & predictive-back shell

1. Read `PlayerFragment.kt:550-720` and `AudioPlayerFragment.kt:260-405` side by side.
2. Extract `MiniPlayerMotionController` owning the `OnBackPressedCallback` collapse/minimize logic and `MotionLayout.TransitionListener` progress/state callbacks.
3. Add `MiniPlayerMotionControllerTest.kt` (Robolectric) verifying back-press behavior when expanded vs collapsed, then wire both `PlayerFragment` and `AudioPlayerFragment` to `MiniPlayerMotionController`.

**Verify**: `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.ui.controllers.MiniPlayerMotionControllerTest"` → all pass; `./tools/quality/gate.sh fast` → exit 0.

### Step 3: Extract `PlayerGestureController` and `PlayerOptionDialogs` from `CustomExoPlayerView.kt`

1. Move touch gesture detection + brightness/volume HUD state into `PlayerGestureController`.
2. Move quality/audio/subtitle/playback-speed dialog builders into `PlayerOptionDialogs` (preserving **Music Removal** 1.5× cap and **Preferred Speed** behavior).

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 4: Prune resolved detekt/file-size baselines and run full gate

Remove resolved `LargeClass`/`LongMethod` entries for the refactored files from `app/detekt-baseline.xml` (never add new entries) and run `./tools/quality/gate.sh full`.

**Verify**: `./tools/quality/gate.sh full` → exit 0.

## Test plan

- `PlayerTrackUtilsTest.kt` and `MiniPlayerMotionControllerTest.kt` (new) + full suite via `./tools/quality/gate.sh full`.

## Done criteria

- [ ] `PlayerHelper.kt`, `CustomExoPlayerView.kt`, and `PlayerFragment.kt` are each reduced significantly in size and detekt baseline entries are pruned (never added).
- [ ] `PlayerFragment` and `AudioPlayerFragment` share `MiniPlayerMotionController`.
- [ ] `PlayerTrackUtilsTest` and `MiniPlayerMotionControllerTest` pass.
- [ ] `./tools/quality/gate.sh full` exits 0.
- [ ] `plans/README.md` status row updated.

## STOP conditions

Stop and report back (do not improvise) if:
- Extracting a helper introduces a new detekt violation that cannot be resolved cleanly without adding to `detekt-baseline.xml`.
- `PlayerFragment` and `AudioPlayerFragment` mini-player state machines diverge in a way that cannot be parameterized cleanly.

## Maintenance notes

- New player controls or **Screen Pin** / **Music Removal** UI options belong in the extracted controllers (`PlayerOptionDialogs`, `PlayerGestureController`) rather than re-bloating `CustomExoPlayerView.kt`.
