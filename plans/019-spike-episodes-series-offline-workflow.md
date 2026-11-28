# Plan 019: Spike — Complete Episodes & Series offline workflow (long-press actions + auto-download design)

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f44e95979..HEAD -- app/src/main/java/com/github/libretube/ui/adapters/EpisodesAdapter.kt app/src/main/java/com/github/libretube/ui/fragments/SubscriptionsFragment.kt`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: MED
- **Depends on**: `plans/006-watch-position-direct-query-and-batching.md`, `plans/015-fix-adapter-coroutines-and-sponsorblock-cache.md`
- **Category**: direction
- **Planned at**: commit `f44e95979`, 2026-11-28

## Why this matters

Tubik v1.3.0 introduced **Series** (followed playlists) and **Episodes** (unwatched podcast-style list with **Listen** audio-only button and swipe-to-mark-watched). However, `EpisodesAdapter` only handles tap and play — unlike `VideoCardsAdapter`, Episodes rows lack a long-press `VideoOptionsBottomSheet` (Download, Play Next, Add to Queue, Share, Mark Watched). Meanwhile, `NotificationWorker` and `PlaylistDownloadEnqueueService` already discover new Series episodes and enqueue downloads, so completing long-press actions and designing opt-in per-Series auto-download is the natural next step for offline listening.

## Current state

- `app/src/main/java/com/github/libretube/ui/adapters/EpisodesAdapter.kt:20-45`:
  ```kotlin
  class EpisodesAdapter(
      private val onOpen: (StreamItem) -> Unit,
      private val onListen: (StreamItem) -> Unit,
  ) : ... {
      ...
      root.setOnClickListener { onOpen(episode) }
      episodePlay.setOnClickListener { onListen(episode) }
  ```
- `SubscriptionsFragment.kt` instantiates `EpisodesAdapter` and already hosts `VideoCardsAdapter` (which opens `VideoOptionsBottomSheet` on long press).
- `Series.kt` (`tableName = "playlistBookmark"`): stores `playlistId`, `seriesOrder`, `checkedVideoCount`, `lastOpened`.
- ADR-0003 terminology (`GLOSSARY.md`): always say **Series** (never "playlist bookmark", "followed playlist", or "podcast" in code/UI labels).

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Fast gate | `./tools/quality/gate.sh fast` | exit 0 |
| Full gate | `./tools/quality/gate.sh full` | exit 0 |

## Scope

**In scope** (the only files you should modify/create):
- `app/src/main/java/com/github/libretube/ui/adapters/EpisodesAdapter.kt`
- `app/src/main/java/com/github/libretube/ui/fragments/SubscriptionsFragment.kt` (EpisodesAdapter long-press wiring only)
- `app/src/test/java/com/github/libretube/ui/adapters/EpisodesAdapterTest.kt` (create)
- `docs/design/series-auto-download.md` (create — Stage 2 design spike doc)

**Out of scope** (do NOT touch):
- `Series.kt`, `AppDatabase.kt`, `NotificationWorker.kt`, `DownloadService.kt` (do NOT implement background auto-download in this spike — design it in `docs/design/series-auto-download.md` for maintainer sign-off first!)

## Git workflow

- Branch: `advisor/019-spike-episodes-series-offline-workflow`
- Commit style: `feat(episodes): add long-press options on Episodes rows and Series auto-download design spike`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Wire `VideoOptionsBottomSheet` long-press onto `EpisodesAdapter` & `SubscriptionsFragment`

1. Add `private val onLongClick: (StreamItem) -> Unit = {}` (with default no-op so existing callers/tests compile) to `EpisodesAdapter`, and bind `root.setOnLongClickListener { onLongClick(episode); true }`.
2. In `SubscriptionsFragment.kt`, pass `onLongClick` opening `VideoOptionsBottomSheet` (matching the existing `VideoCardsAdapter` long-press pattern in `SubscriptionsFragment.kt` so Download, Play Next, Add to Queue, Share, and Mark Watched all work while respecting **Allowlist** and **Parent PIN**).
3. Add `EpisodesAdapterTest.kt` (Robolectric) verifying `onOpen`, `onListen`, and `onLongClick` callbacks fire for bound episodes.

**Verify**: `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.ui.adapters.EpisodesAdapterTest"` → all pass; `./tools/quality/gate.sh fast` → exit 0.

### Step 2: Author the Series auto-download design spike (`docs/design/series-auto-download.md`)

Write `docs/design/series-auto-download.md` covering:
1. **Data model**: proposed `autoDownload: Boolean` + `autoDownloadLimit: Int` on `Series` (Room migration impact).
2. **Trigger & constraints**: how `NotificationWorker` (already checking Series on daily refresh per ADR-0003) hands new episodes to `DownloadService` under `NetworkType.UNMETERED` (Wi-Fi only) and battery-not-low constraints.
3. **Audio/Video & Allowlist rules**: respecting `ChannelSettingSet.audioOnlyDefault`, skipping hidden non-**Allowed Channel** Series when **Allowlist** is on, and whether **Parent PIN** gates enabling/disabling auto-download.
4. **Retention & cleanup**: keeping latest N unwatched episodes, optional auto-delete when marked watched (`DatabaseHelper.isVideoWatched` 5% rule).
5. **Open product questions** for the maintainer (default retention cap, metered-network override, interaction with pre-processed **Music Removal** downloads).

**Verify**: `test -f docs/design/series-auto-download.md && ./tools/quality/gate.sh full` → exit 0.

## Test plan

- `EpisodesAdapterTest.kt` (new) + `./tools/quality/gate.sh full`.

## Done criteria

- [ ] Long-pressing an episode row in **Episodes** opens `VideoOptionsBottomSheet`.
- [ ] `EpisodesAdapterTest` exists and passes.
- [ ] `docs/design/series-auto-download.md` documents architecture, constraints, and open questions using `GLOSSARY.md` terms (**Series**, **Allowlist**, **Allowed Channel**, **Parent PIN**).
- [ ] `./tools/quality/gate.sh full` exits 0.
- [ ] `plans/README.md` status row updated.

## STOP conditions

Stop and report back (do not improvise) if:
- `SubscriptionsFragment.kt` uses a `FragmentResultListener` for `VideoOptionsBottomSheet` that behaves differently in Episodes mode (e.g., swipe-refresh or watched-list removal) — check and wire cleanly, or stop if unexpected.

## Maintenance notes

- Do not start implementing Stage 2 (`Series` auto-download) until the maintainer reviews the open questions in `docs/design/series-auto-download.md`.
