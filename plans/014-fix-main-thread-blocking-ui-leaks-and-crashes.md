# Plan 014: Fix main-thread `runBlocking`, UI lifecycle leaks, `BackupDialog` threading, and crash guards

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f44e95979..HEAD -- app/src/main/java/com/github/libretube/ui/dialogs/ShareDialog.kt app/src/main/java/com/github/libretube/ui/dialogs/BackupDialog.kt app/src/main/java/com/github/libretube/ui/preferences/BackupRestoreSettings.kt app/src/main/java/com/github/libretube/ui/activities/MainActivity.kt app/src/main/java/com/github/libretube/services/PlaylistDownloadEnqueueService.kt app/src/main/java/com/github/libretube/ui/fragments/LibraryFragment.kt app/src/main/java/com/github/libretube/ui/fragments/PlaylistFragment.kt app/src/main/java/com/github/libretube/helpers/ImportHelper.kt`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: LOW
- **Depends on**: `plans/006-watch-position-direct-query-and-batching.md`, `plans/008-fix-local-playlist-removal-and-targeted-queries.md`
- **Category**: bug
- **Planned at**: commit `f44e95979`, 2026-11-28

## Why this matters

Several UI and service entry points share localized threading, lifecycle, and bounds bugs:
- `ShareDialog` runs an HTTP image download inside `runBlocking` on the main thread (ANR risk), `PlayerFragment.onViewCreated` runs a multi-table Room query inside `runBlocking`, and `DownloadsAdapter.deleteAllDownloads` runs `runBlocking` per item.
- `BackupDialog` calls `setFragmentResult` and `dialog?.dismiss()` from `Dispatchers.IO` and passes the entire serialized backup JSON in a Fragment result `Bundle` (`TransactionTooLargeException` risk), while `BackupRestoreSettings` and `MainActivity` (`exportPlaylistId!!`) lose picker state across process death.
- `PlaylistDownloadEnqueueService` never calls `stopSelf()` when **Allowlist** hides a playlist item and force-unwraps `intent!!`; `MainActivity.onKeyUp` reads uninitialized `searchItem` and drops search-bar timestamps; `PlaylistFragment`/`LibraryFragment` leak observers/coroutines and duplicate `MostRecent`; `ImportHelper` crashes on short channel URLs or truncated CSVs.

## Current state

- `ShareDialog.kt:106`: `val uri = runBlocking { generatePreviewUri(requireContext(), it) }`.
- `PlayerFragment.kt:509-514`: `runBlocking(Dispatchers.IO) { DatabaseHolder.Database.downloadDao().findById(videoId) }`.
- `DownloadsAdapter.kt:195-204`: `runBlocking(Dispatchers.IO)` inside `currentList.partition`.
- `BackupDialog.kt:144-158`: `lifecycleScope.launch(Dispatchers.IO) { ... setFragmentResult(...); dialog?.dismiss() }`.
- `BackupRestoreSettings.kt:36-37, 212-220`: unsaved `backupFile`/`importFormat` fields; decodes JSON from Fragment result `Bundle`.
- `MainActivity.kt:96-112, 420-425, 680-689, 758-767`: unsaved `exportPlaylistId!!`/`playlistExportFormat`; `onKeyUp` accesses `searchItem` without `::searchItem.isInitialized`; `navigationVideo` reads `this.intent` instead of the resolved `queryIntent`.
- `PlaylistDownloadEnqueueService.kt:69-87, 145-174`: `intent!!` + sticky `super.onStartCommand`, and `stopSelf()` only runs when `amountOfVideos == amountOfVideosDone`.
- `PlaylistFragment.kt:273-297`: registers fragment result listener with `(context as BaseActivity)` instead of `viewLifecycleOwner`.
- `LibraryFragment.kt:67-75, 127-133, 202-228`: un-unregistered `AdapterDataObserver`, duplicate `MostRecent`, `repeatOnLifecycle(CREATED)` in `fetchPlaylists()`.
- `ImportHelper.kt:229-231, 384`: `url.substring(url.length - 24)` and unguarded `lines.subList(startIndex, lines.size)`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Fast gate | `./tools/quality/gate.sh fast` | exit 0 |
| Import tests | `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.helpers.ImportHelperParserTest"` | BUILD SUCCESSFUL, all tests pass |
| Full gate | `./tools/quality/gate.sh full` | exit 0 |

## Scope

**In scope** (the only files you should modify/create):
- `app/src/main/java/com/github/libretube/ui/dialogs/ShareDialog.kt`
- `app/src/main/java/com/github/libretube/ui/fragments/PlayerFragment.kt` (lines 509–545 only)
- `app/src/main/java/com/github/libretube/ui/adapters/DownloadsAdapter.kt` (`deleteAllDownloads`)
- `app/src/main/java/com/github/libretube/ui/dialogs/BackupDialog.kt`
- `app/src/main/java/com/github/libretube/ui/preferences/BackupRestoreSettings.kt`
- `app/src/main/java/com/github/libretube/ui/activities/MainActivity.kt`
- `app/src/main/java/com/github/libretube/services/PlaylistDownloadEnqueueService.kt`
- `app/src/main/java/com/github/libretube/ui/fragments/PlaylistFragment.kt` (lines 273–297)
- `app/src/main/java/com/github/libretube/ui/fragments/LibraryFragment.kt`
- `app/src/main/java/com/github/libretube/helpers/ImportHelper.kt`
- `app/src/test/java/com/github/libretube/helpers/ImportHelperParserTest.kt` (create)

**Out of scope** (do NOT touch):
- `CustomExoPlayerView.kt`, `AudioPlayerFragment.kt`

## Git workflow

- Branch: `advisor/014-fix-main-thread-blocking-ui-leaks-and-crashes`
- Commit style: `fix(ui): remove main-thread runBlocking, fix lifecycle leaks, and guard crash paths`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Remove main-thread `runBlocking` in `ShareDialog`, `PlayerFragment`, and `DownloadsAdapter`

1. `ShareDialog.kt:98-116`: replace positive button `runBlocking` with a non-blocking flow: capture `appContext = requireContext().applicationContext`, launch on `viewLifecycleOwner.lifecycleScope` (or parent fragment/activity scope if dialog dismisses), download preview on `Dispatchers.IO`, and call `startActivity(shareIntent)` on `Dispatchers.Main` (using `Context` safely).
2. `PlayerFragment.kt:509-545`: wrap `DatabaseHolder.Database.downloadDao().findById(videoId)` in `viewLifecycleOwner.lifecycleScope.launch` with `withContext(Dispatchers.IO)` and check `isAdded` / `_binding != null` before showing `PlayOfflineDialog`.
3. `DownloadsAdapter.kt:194-215`: launch `CoroutineScope(Dispatchers.IO).launch` (or pass scope) ONCE, fetch `DatabaseHelper.getWatchPositions(...)` in one batch, partition `currentList` in memory, delete items, and `submitList(toKeep)` on `Dispatchers.Main`.

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 2: Fix `BackupDialog` threading/bundle size and persist picker state

1. `BackupDialog.kt:141-160`: return only `BooleanArray` of `selected` indices (e.g. `bundleOf(IntentData.backupFile to selected)`) via `setFragmentResult` on `Dispatchers.Main`, then call `dialog?.dismiss()`.
2. `BackupRestoreSettings.kt`: receive `selected` flags in `BACKUP_DIALOG_REQUEST_KEY`, save `selectedBackupIndices` & `importFormat` in `onSaveInstanceState` / restore in `onCreatePreferences`, and build `BackupFile` inside `createBackupFile`'s `lifecycleScope.launch(Dispatchers.IO)` callback only after the user picks a URI.
3. `MainActivity.kt:96-112`: save/restore `exportPlaylistId` and `playlistExportFormat` in `onSaveInstanceState` / `onCreate`, and guard `val playlistId = exportPlaylistId ?: return@registerForActivityResult` instead of `!!`.

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 3: Fix `PlaylistDownloadEnqueueService`, `MainActivity`, `PlaylistFragment`, `LibraryFragment`, and `ImportHelper`

1. `PlaylistDownloadEnqueueService.kt:66-88, 101-174`: return `START_NOT_STICKY`, guard null `intent`/extras (`if (intent == null) { stopSelf(); return START_NOT_STICKY }`), and wrap `enqueuePrivatePlaylist()`/`enqueuePublicPlaylist()` in `try { ... } finally { stopSelf() }`.
2. `MainActivity.kt`:
   - `onKeyUp` (line 760): `if (::searchItem.isInitialized && searchItem.isActionViewExpanded) return false`.
   - `navigateToMediaByIntent` (lines 652-689): invoke `actionBefore()` before navigating to video, and pass `intent.getLongExtra(IntentData.timeStamp, 0L)` into `navigationVideo(it, timestamp)`.
3. `PlaylistFragment.kt:276`: use `viewLifecycleOwner` instead of `(context as BaseActivity)` and guard `_binding` inside the result callback.
4. `LibraryFragment.kt`: store the `AdapterDataObserver` in a nullable field and unregister it in `onDestroyView()`, change duplicate `MostRecent` (line 129) to `LeastRecent`, and replace `lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.CREATED) { ... } }` in `fetchPlaylists()` with `viewLifecycleOwner.lifecycleScope.launch { ... }`.
5. `ImportHelper.kt`: at line 384 replace `url.substring(url.length - 24)` with `url.toID().takeIf { it.isNotBlank() }`; at lines 229-231 return early if `startIndex >= lines.size`.

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 4: Add `ImportHelperParserTest.kt` and run full gate

Create `ImportHelperParserTest.kt` testing:
1. YouTube Takeout watch history JSON with a short `subtitles[0].url` (e.g. `"https://yt.be/c/1"`, length < 24) does not throw `StringIndexOutOfBoundsException`.
2. Truncated YouTube CSV playlist with blank separator and < 2 trailing lines does not throw `IllegalArgumentException`.

**Verify**: `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.helpers.ImportHelperParserTest"` → all pass; `./tools/quality/gate.sh full` → exit 0.

## Test plan

- `ImportHelperParserTest.kt` (new) + full Robolectric/unit suite via `./tools/quality/gate.sh full`.

## Done criteria

- [ ] Zero `runBlocking` calls remain in `ShareDialog`, `PlayerFragment`, or `DownloadsAdapter`.
- [ ] `BackupDialog` calls `setFragmentResult` and `dismiss()` on `Dispatchers.Main` without passing serialized backup JSON in a `Bundle`.
- [ ] `PlaylistDownloadEnqueueService` always calls `stopSelf()` when pagination finishes and returns `START_NOT_STICKY`.
- [ ] `MainActivity.onKeyUp`, `LibraryFragment`, `PlaylistFragment`, and `ImportHelper` fixes are in place and `./tools/quality/gate.sh full` exits 0.
- [ ] `plans/README.md` status row updated.

## STOP conditions

Stop and report back (do not improvise) if:
- `BackupDialog` is invoked from any caller other than `BackupRestoreSettings` (check with grep for `BACKUP_DIALOG_REQUEST_KEY` first).

## Maintenance notes

- Do not pass large serialized payloads through Fragment result `Bundle`s (Android Binder limit is ~1 MB); pass only small selection flags and build heavy payloads after URI selection.
