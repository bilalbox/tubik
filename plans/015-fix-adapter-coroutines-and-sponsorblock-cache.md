# Plan 015: Fix uncancelled adapter coroutines and `SponsorBlockLabelHelper` negative caching

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f44e95979..HEAD -- app/src/main/java/com/github/libretube/api/SponsorBlockLabelHelper.kt app/src/main/java/com/github/libretube/ui/extensions/SetWatchProgressLength.kt app/src/main/java/com/github/libretube/ui/adapters/VideosAdapter.kt app/src/main/java/com/github/libretube/ui/adapters/VideoCardsAdapter.kt app/src/main/java/com/github/libretube/ui/adapters/SearchResultsAdapter.kt`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: MED
- **Depends on**: `plans/006-watch-position-direct-query-and-batching.md`
- **Category**: perf
- **Planned at**: commit `f44e95979`, 2026-11-28

## Why this matters

1. `SponsorBlockLabelHelper.getVideoLabels` calls `cache.put(videoId, it)` when `firstOrNull` returns `null`, throwing an `android.util.LruCache` `NullPointerException` (silently swallowed by `runCatching`). Consequently, negative results are never cached and every unlabelled video (>99% of videos) re-queries the SponsorBlock API on every scroll/rebind.
2. `View.setWatchProgressLength(videoId, duration)` and 7 RecyclerView adapters (`VideoCardsAdapter`, `VideosAdapter`, `SearchResultsAdapter`, `PlaylistAdapter`, `EpisodesAdapter`, `DownloadsAdapter`, `WatchHistoryAdapter`) launch unstructured `CoroutineScope(Dispatchers.IO).launch` jobs on every `onBindViewHolder` without cancelling prior jobs on rebind/recycle or checking that the ViewHolder is still bound to the same `videoId`.

## Current state

- `app/src/main/java/com/github/libretube/api/SponsorBlockLabelHelper.kt:11, 21-40`:
  ```kotlin
  private val cache = LruCache<String, VideoLabelData>(256)
  ...
  .firstOrNull { it.videoID == videoId }
  .also { cache.put(videoId, it) }
  ```
- `app/src/main/java/com/github/libretube/ui/extensions/SetWatchProgressLength.kt:25-35`:
  ```kotlin
  fun View.setWatchProgressLength(videoId: String, duration: Long) {
      isGone = true

      CoroutineScope(Dispatchers.IO).launch {
          val progress = DatabaseHelper.getWatchPosition(videoId)

          withContext(Dispatchers.Main) {
              setWatchProgressLength(progress, duration)
          }
      }
  }
  ```

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Fast gate | `./tools/quality/gate.sh fast` | exit 0 |
| Label tests | `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.api.SponsorBlockLabelHelperTest"` | BUILD SUCCESSFUL, all tests pass |
| Full gate | `./tools/quality/gate.sh full` | exit 0 |

## Scope

**In scope** (the only files you should modify/create):
- `app/src/main/java/com/github/libretube/api/SponsorBlockLabelHelper.kt`
- `app/src/main/java/com/github/libretube/ui/extensions/SetWatchProgressLength.kt`
- `app/src/main/java/com/github/libretube/ui/adapters/VideoCardsAdapter.kt`
- `app/src/main/java/com/github/libretube/ui/adapters/VideosAdapter.kt`
- `app/src/main/java/com/github/libretube/ui/adapters/SearchResultsAdapter.kt`
- `app/src/main/java/com/github/libretube/ui/adapters/PlaylistAdapter.kt`
- `app/src/main/java/com/github/libretube/ui/adapters/EpisodesAdapter.kt`
- `app/src/main/java/com/github/libretube/ui/adapters/DownloadsAdapter.kt`
- `app/src/main/java/com/github/libretube/ui/adapters/WatchHistoryAdapter.kt`
- `app/src/main/res/values/ids.xml` (create if needed for `View.setTag` IDs)
- `app/src/test/java/com/github/libretube/api/SponsorBlockLabelHelperTest.kt` (create)

**Out of scope** (do NOT touch):
- `ExternalApi.kt`, `DeArrowUtil.kt` (keep `DeArrowUtil` API unchanged; only cancel/guard the adapter job calling it)

## Git workflow

- Branch: `advisor/015-fix-adapter-coroutines-and-sponsorblock-cache`
- Commit style: `perf(ui): cache negative SponsorBlock label lookups and cancel recycled adapter jobs`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Fix `SponsorBlockLabelHelper` negative caching

In `SponsorBlockLabelHelper.kt`:
- Define `private sealed interface CachedLabel { data class Present(val data: VideoLabelData) : CachedLabel; data object Absent : CachedLabel }`.
- Change `cache` to `LruCache<String, CachedLabel>(256)`.
- On hit: return `(cached as? CachedLabel.Present)?.data` (for `Absent`, return `null` immediately without network call!).
- On miss: store `CachedLabel.Present(label)` if non-null else `CachedLabel.Absent`.
- Add `@VisibleForTesting internal fun clearCache() = cache.evictAll()` and an optional `fetcher: suspend (String) -> List<VideoLabelData>` parameter on an internal helper so `SponsorBlockLabelHelperTest` can test without live HTTP.

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 2: Cancel-on-rebind & tag-check in `SetWatchProgressLength.kt`

In `SetWatchProgressLength.kt`:
- Define tag keys in `res/values/ids.xml` (`watch_progress_job_tag`, `watch_progress_video_id_tag`).
- On `View.setWatchProgressLength(videoId: String, duration: Long)`:
  - Cancel any prior `Job` stored at `getTag(R.id.watch_progress_job_tag)`.
  - Store `setTag(R.id.watch_progress_video_id_tag, videoId)`.
  - Launch on `findViewTreeLifecycleOwner()?.lifecycleScope` (or a view-scoped `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` cancelled on `OnAttachStateChangeListener.onViewDetachedFromWindow`), fetch `DatabaseHelper.getWatchPosition(videoId)` on `Dispatchers.IO`, and before calling `setWatchProgressLength(progress, duration)` verify `getTag(R.id.watch_progress_video_id_tag) == videoId`.

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 3: Cancel & guard per-row async jobs in `VideoCardsAdapter`, `VideosAdapter`, `SearchResultsAdapter`, and `onViewRecycled`

For each adapter spawning `CoroutineScope(Dispatchers.IO).launch` in `onBindViewHolder` (`VideoCardsAdapter`, `VideosAdapter`, `SearchResultsAdapter`, `PlaylistAdapter`, `EpisodesAdapter`, `DownloadsAdapter`, `WatchHistoryAdapter`):
- Track the ViewHolder's active `Job?` and bound `videoId: String?` (cancelling the previous `Job` at the start of `onBindViewHolder` and in `override fun onViewRecycled(holder)`).
- Reset async-populated views (badges, download indicator, watch progress) immediately at bind start so recycled views never flash stale data.
- Before applying `downloadDao().exists(videoId)`, `SponsorBlockLabelHelper.getVideoLabels(videoId)`, or `DeArrowUtil.deArrowVideoId(videoId)` results on `Dispatchers.Main`, assert `holder.boundVideoId == videoId`.

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 4: Add `SponsorBlockLabelHelperTest.kt` and run full gate

Create `SponsorBlockLabelHelperTest.kt` (Robolectric, since `android.util.LruCache` is used):
1. Verify a `null` / non-matching label response caches `Absent` and a second call for the same `videoId` does NOT invoke the fetcher again.
2. Verify a matching `VideoLabelData` caches `Present` and returns it on subsequent calls without invoking the fetcher.

**Verify**: `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.api.SponsorBlockLabelHelperTest"` → all pass; `./tools/quality/gate.sh full` → exit 0.

## Test plan

- `SponsorBlockLabelHelperTest.kt` (new).
- Verification: `./tools/quality/gate.sh full` → exit 0.

## Done criteria

- [ ] `SponsorBlockLabelHelper` caches negative lookups without `NullPointerException`.
- [ ] `SetWatchProgressLength` and all 7 adapters cancel prior jobs on rebind/recycle and verify `boundVideoId` before mutating views.
- [ ] `./tools/quality/gate.sh full` exits 0.
- [ ] `plans/README.md` status row updated.

## STOP conditions

Stop and report back (do not improvise) if:
- Any of the 7 adapters uses `ViewHolder` subclasses in a separate file where adding `boundJob`/`boundVideoId` requires modifying shared `viewholders/` files — add `viewholders/*.kt` only if trivial, otherwise stop and report.

## Maintenance notes

- Consider passing a pre-batched `Map<String, Long>` of watch positions from fragments into adapters in a follow-up to eliminate even the cancelled per-row DB lookups during fast flings.
