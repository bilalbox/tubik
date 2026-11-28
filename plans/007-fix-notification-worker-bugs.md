# Plan 007: Fix `NotificationWorker` `DATA_SAVER_MODE` crash, first-run deadlock, and null `uploaderUrl`

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f44e95979..HEAD -- app/src/main/java/com/github/libretube/workers/NotificationWorker.kt`
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

`NotificationWorker` has three bugs that break new-video notifications: (1) `downloadImage` reads `PreferenceKeys.DATA_SAVER_MODE` as a `Boolean` even though it is a `ListPreference` storing `"disabled"`/`"enabled"`/`"metered"` — throwing `ClassCastException` once changed and inverting the condition before it's written; (2) on first run (`lastFeedCheckMillis == 0L`), `doWork` returns `true` without seeding `LAST_REFRESHED_FEED_TIME`, so notifications never fire until the user manually opens Subscriptions; and (3) `it.uploaderUrl!!.toID()` crashes with `NullPointerException` if a feed or Series item has a null `uploaderUrl`.

## Current state

- `app/src/main/java/com/github/libretube/workers/NotificationWorker.kt:88-115`:
  ```kotlin
  val lastFeedCheckMillis = PreferenceHelper.getLastCheckedFeedTime(seenByUser = false)

  // first time notifications are enabled or no new video available
  if (lastFeedCheckMillis == 0L || videoFeed.none { it.uploaded > lastFeedCheckMillis })
      return true
  ...
  val channelGroups =
      videoFeed
          .asSequence()
          .filter { it.uploaded > lastFeedCheckMillis }
          .filter { enableShortsNotification || !it.isShort }
          .filter { it.uploaderUrl!!.toID() !in channelsToIgnore }
          .groupBy { it.uploaderUrl!!.toID() }
  ```
- `app/src/main/java/com/github/libretube/workers/NotificationWorker.kt:231-237`:
  ```kotlin
  private suspend fun downloadImage(url: String?): Bitmap? {
      return if (PreferenceHelper.getBoolean(PreferenceKeys.DATA_SAVER_MODE, false)) {
          ImageHelper.getImage(applicationContext, url)
      } else {
          null
      }
  }
  ```
- `app/src/main/java/com/github/libretube/util/DataSaverMode.kt:9-17` exposes `DataSaverMode.isEnabled(context)`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Fast gate | `./tools/quality/gate.sh fast` | exit 0 |
| Worker tests | `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.workers.NotificationWorkerTest"` | BUILD SUCCESSFUL, all tests pass |
| Full gate | `./tools/quality/gate.sh full` | exit 0 |

## Scope

**In scope** (the only files you should modify/create):
- `app/src/main/java/com/github/libretube/workers/NotificationWorker.kt`
- `app/src/test/java/com/github/libretube/workers/NotificationWorkerTest.kt` (create)

**Out of scope** (do NOT touch):
- `DataSaverMode.kt`, `PreferenceHelper.kt`, `general_settings.xml`

## Git workflow

- Branch: `advisor/007-fix-notification-worker-bugs`
- Commit style: `fix(notifications): fix data saver cast crash, first-run seed, and null uploaderUrl`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Fix `downloadImage`, first-run baseline seeding, and null `uploaderUrl`

In `NotificationWorker.kt`:
1. In `downloadImage(url: String?)`: replace `PreferenceHelper.getBoolean(PreferenceKeys.DATA_SAVER_MODE, false)` with `!DataSaverMode.isEnabled(applicationContext)`.
2. At lines 90–92: when `lastFeedCheckMillis == 0L`, seed the baseline before returning `true`:
   ```kotlin
   if (lastFeedCheckMillis == 0L) {
       videoFeed.firstOrNull()?.uploaded?.let { latest ->
           PreferenceHelper.updateLastFeedWatchedTime(latest, seenByUser = false)
       }
       return true
   }
   if (videoFeed.none { it.uploaded > lastFeedCheckMillis }) return true
   ```
3. Extract a pure `internal fun groupNewStreamsForNotifications(...)` helper that safely handles null/blank `uploaderUrl` (`val channelId = item.uploaderUrl?.toID()?.takeIf { it.isNotEmpty() } ?: continue` or skips items without a channel ID) and also guard `stream.url?.toID()?.takeIf { it.isNotEmpty() }` in `createStreamNotification`.

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 2: Add `NotificationWorkerTest.kt` and run full gate

Create `app/src/test/java/com/github/libretube/workers/NotificationWorkerTest.kt` (Robolectric, modeled after `AboutActivityTest.kt`):
1. Set `PreferenceKeys.DATA_SAVER_MODE` to `"disabled"` and `"enabled"` (string values!) and verify no `ClassCastException` occurs and image-fetch gate returns `true` for `"disabled"` and `false` for `"enabled"`.
2. Test `groupNewStreamsForNotifications` with an item whose `uploaderUrl == null` alongside valid items; verify no NPE and valid items remain grouped.
3. Test first-run seeding helper/behavior so `lastFeedCheckMillis == 0L` records `videoFeed.first().uploaded` without notifying on historical items.

**Verify**: `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.workers.NotificationWorkerTest"` → all pass; `./tools/quality/gate.sh full` → exit 0.

## Test plan

- `app/src/test/java/com/github/libretube/workers/NotificationWorkerTest.kt` (new).
- Verification: `./tools/quality/gate.sh full` → exit 0.

## Done criteria

- [ ] `NotificationWorker.downloadImage` uses `!DataSaverMode.isEnabled(applicationContext)` (no `getBoolean(DATA_SAVER_MODE)`).
- [ ] First run (`lastFeedCheckMillis == 0L`) seeds `LAST_REFRESHED_FEED_TIME` when `videoFeed` is non-empty.
- [ ] Null `uploaderUrl` items never throw `NullPointerException`.
- [ ] `./tools/quality/gate.sh full` exits 0.
- [ ] `plans/README.md` status row updated.

## STOP conditions

Stop and report back (do not improvise) if:
- `NotificationWorker.kt` lines 88–237 differ from the excerpts above.

## Maintenance notes

- Always use `DataSaverMode.isEnabled(context)` rather than reading `PreferenceKeys.DATA_SAVER_MODE` directly.
