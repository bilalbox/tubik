# Plan 009: Eliminate per-channel `feedItem` scans and only advance refresh timestamp on full refresh

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f44e95979..HEAD -- app/src/main/java/com/github/libretube/repo/LocalFeedRepository.kt app/src/test/java/com/github/libretube/repo/LocalFeedRepositoryTest.kt`
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

`LocalFeedRepository` has two coupled flaws in feed refresh: (1) `shouldFetchChannel` calls `feedStore.getFeedItems().any { it.uploaderUrl?.toID() == canonicalChannelId }` inside the per-channel `parallelMap` loop, executing a full `SELECT * FROM feedItem ORDER BY uploaded DESC` scan for every subscribed channel; and (2) `lastLocalFeedRefreshTimestampMillis` is updated to `nowMillis` even when `planFeedRefresh` only refreshed newly subscribed missing channels (`Refresh(missing)`), postponing automatic refreshes of all other channels for another 24 hours.

## Current state

- `app/src/main/java/com/github/libretube/repo/LocalFeedRepository.kt:115-127`:
  ```kotlin
  is FeedRefreshPlan.Refresh -> {
      if (
          refresh !is FeedRefresh.OnlySome &&
              plan.channelIds.size == subscribedChannelIds.size
      ) {
          feedStore.cleanUpOlderThan(minimumDateMillis)
      }
      val refreshFailure =
          refreshFeed(plan.channelIds, minimumDateMillis, onProgressUpdate)
      if (refreshFailure == null) {
          preferences.lastLocalFeedRefreshTimestampMillis = nowMillis
      }
  ```
- `app/src/main/java/com/github/libretube/repo/LocalFeedRepository.kt:282-283`:
  ```kotlin
  val hasCachedVideos =
      feedStore.getFeedItems().any { it.uploaderUrl?.toID() == canonicalChannelId }
  ```

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Fast gate | `./tools/quality/gate.sh fast` | exit 0 |
| Feed repo tests | `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.repo.LocalFeedRepositoryTest"` | BUILD SUCCESSFUL, all tests pass |
| Full gate | `./tools/quality/gate.sh full` | exit 0 |

## Scope

**In scope** (the only files you should modify):
- `app/src/main/java/com/github/libretube/repo/LocalFeedRepository.kt`
- `app/src/test/java/com/github/libretube/repo/LocalFeedRepositoryTest.kt`

**Out of scope** (do NOT touch):
- `SubscriptionsFeedDao.kt`, `SeriesAwareFeedRepository.kt`

## Git workflow

- Branch: `advisor/009-fix-local-feed-refresh-scan-and-timestamp`
- Commit style: `fix(feed): avoid per-channel feed scans and only advance refresh timestamp on full refresh`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Gate `lastLocalFeedRefreshTimestampMillis` on full refresh and precompute `cachedUploaderIds`

In `LocalFeedRepository.kt`:
1. Compute `val isFullRefresh = refresh !is FeedRefresh.OnlySome && plan.channelIds.size == subscribedChannelIds.size`.
2. Use `isFullRefresh` for both `cleanUpOlderThan(minimumDateMillis)` and `if (isFullRefresh && refreshFailure == null) { preferences.lastLocalFeedRefreshTimestampMillis = nowMillis }`.
3. Compute `val cachedUploaderIds: Set<String> = cachedFeed.mapNotNullTo(HashSet()) { it.uploaderUrl?.toID()?.takeIf(String::isNotEmpty) }` once before calling `refreshFeed`, pass it to `refreshFeed(...)` → `shouldFetchChannel(...)`, and replace `feedStore.getFeedItems().any { ... }` with `canonicalChannelId in cachedUploaderIds`.

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 2: Expand `LocalFeedRepositoryTest.kt` and run full gate

In `LocalFeedRepositoryTest.kt`, add tests verifying:
1. Subscribing to a new channel at hour 23 (`FeedRefresh.Automatically`, `Refresh(missing)`) fetches only the missing channel and does NOT advance `lastLocalFeedRefreshTimestampMillis`, so opening at hour 25 triggers a full refresh.
2. Multi-channel `refreshFeed` calls `feedStore.getFeedItems()` at most once (not N times).

**Verify**: `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.repo.LocalFeedRepositoryTest"` → all pass; `./tools/quality/gate.sh full` → exit 0.

## Test plan

- `app/src/test/java/com/github/libretube/repo/LocalFeedRepositoryTest.kt`.
- Verification: `./tools/quality/gate.sh full` → exit 0.

## Done criteria

- [ ] `shouldFetchChannel` checks precomputed `cachedUploaderIds` instead of calling `feedStore.getFeedItems()`.
- [ ] `lastLocalFeedRefreshTimestampMillis` only advances on full refreshes (`isFullRefresh && refreshFailure == null`).
- [ ] `./tools/quality/gate.sh full` exits 0.
- [ ] `plans/README.md` status row updated.

## STOP conditions

Stop and report back (do not improvise) if:
- Any existing test in `LocalFeedRepositoryTest.kt` intentionally asserts that `OnlySome` updates `lastLocalFeedRefreshTimestampMillis` (stop and report the conflict).

## Maintenance notes

- `cachedUploaderIds` assumes `cachedFeed` was loaded immediately prior in `getFeed`; if `refreshFeed` is ever called directly without `getFeed`, pass `feedStore.getFeedItems()` once at the entry point.
