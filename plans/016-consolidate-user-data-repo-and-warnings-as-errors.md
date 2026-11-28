# Plan 016: Consolidate `UserDataRepositoryHelper`, fix `StringFormat*` lint bugs, and enable `allWarningsAsErrors=true`

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f44e95979..HEAD -- app/src/main/java/com/github/libretube/repo/UserDataRepositoryHelper.kt app/src/main/java/com/github/libretube/repo/UserDataRepository.kt app/src/main/java/com/github/libretube/repo/FeedRepository.kt gradle.properties app/lint-baseline.xml`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: LOW
- **Depends on**: `plans/006-watch-position-direct-query-and-batching.md`, `plans/008-fix-local-playlist-removal-and-targeted-queries.md`, `plans/011-room-migration-26-to-27-dead-code-and-deps-cleanup.md`
- **Category**: tech-debt
- **Planned at**: commit `f44e95979`, 2026-11-28

## Why this matters

`UserDataRepositoryHelper` marks `userDataRepository` and `feedRepository` `@Deprecated` while allocating fresh `LocalUserDataRepository()` / `SeriesAwareFeedRepository()` instances on every access across 16+ callers — generating dozens of Kotlin deprecation warnings. Because `gradle.properties:32` sets `qualityKit.allWarningsAsErrors=false` (to tolerate 172 legacy Kotlin warnings), newly introduced compiler warnings pass `gate.sh fast` and CI silently. Meanwhile, `app/lint-baseline.xml` suppresses 8 `StringFormatInvalid` and 12 `StringFormatCount` bugs in locale `strings.xml` files that can throw `IllegalFormatException` at runtime.

## Current state

- `app/src/main/java/com/github/libretube/repo/UserDataRepositoryHelper.kt:3-13`:
  ```kotlin
  object UserDataRepositoryHelper {
      @Deprecated("DO NOT use this directly, use the wrappers from PlaylistHelper and SubscriptionHelper instead!")
      val userDataRepository: UserDataRepository
          get() = LocalUserDataRepository()

      @Deprecated("DO NOT use this directly, use the wrappers from SubscriptionHelper instead!")
      val feedRepository: FeedRepository
          get() = SeriesAwareFeedRepository()
  }
  ```
- Direct callers (~16 files): `VideoOptionsBottomSheet.kt`, `AddChannelToGroupSheet.kt`, `PlayingQueueSheet.kt`, `EditChannelGroupSheet.kt`, `WatchHistoryModel.kt`, `ShareDialog.kt`, `SubscriptionGroupsAdapter.kt`, `SubscriptionsFragment.kt`, `WatchHistoryFragment.kt`, `LibraryFragment.kt`, `OfflinePlayerService.kt`, `OnlinePlayerService.kt`, `PlayerHelper.kt`, `PlaylistsHelper.kt`, `SubscriptionHelper.kt`, `DatabaseHelper.kt`, `Episodes.kt`.
- `gradle.properties:31-32`: `qualityKit.allWarningsAsErrors=false`.
- `AGENTS.md:27` already documents `qualityFast` as compiling "with warnings as errors".

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Compile warnings check | `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --rerun-tasks` | 0 warnings |
| Fast gate | `./tools/quality/gate.sh fast` | exit 0 |
| Full gate | `./tools/quality/gate.sh full` | exit 0 |

## Scope

**In scope** (the only files you should modify/delete):
- `app/src/main/java/com/github/libretube/repo/UserDataRepository.kt`
- `app/src/main/java/com/github/libretube/repo/FeedRepository.kt`
- `app/src/main/java/com/github/libretube/repo/UserDataRepositoryHelper.kt` (delete)
- All ~17 callers of `UserDataRepositoryHelper` listed in Current state
- `app/src/main/java/**` and `app/src/test/java/**` files with remaining Kotlin compiler warnings
- `gradle.properties` (`qualityKit.allWarningsAsErrors=true`)
- `app/src/main/res/values-*/strings.xml` (ONLY the 8 `StringFormatInvalid` + 12 `StringFormatCount` entries)
- `app/lint-baseline.xml` (prune ONLY the resolved `StringFormatInvalid` and `StringFormatCount` entries)

**Out of scope** (do NOT touch):
- `app/detekt-baseline.xml` (do not add to it; only edit if removing resolved entries)
- `MissingTranslation` / `ExtraTranslation` entries in `app/lint-baseline.xml`

## Git workflow

- Branch: `advisor/016-consolidate-user-data-repo-and-warnings-as-errors`
- Commit style: `refactor(repo,build): consolidate UserDataRepository singleton and enable allWarningsAsErrors`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Replace `UserDataRepositoryHelper` with cached singletons on `UserDataRepository` and `FeedRepository`

1. Add `companion object` singletons on `UserDataRepository` and `FeedRepository` matching `MediaServiceRepository.instance` / `SeriesRepository.instance` (lazy default `LocalUserDataRepository()` / `SeriesAwareFeedRepository()`, plus `@VisibleForTesting` override/reset if needed).
2. Migrate all callers of `UserDataRepositoryHelper.userDataRepository` → `UserDataRepository.instance` (or `PlaylistsHelper`/`SubscriptionHelper` where appropriate) and `UserDataRepositoryHelper.feedRepository` → `FeedRepository.instance`.
3. Delete `UserDataRepositoryHelper.kt`.

**Verify**: `grep -rn "UserDataRepositoryHelper" app/src/` → no matches; `./tools/quality/gate.sh fast` → exit 0.

### Step 2: Burn down all remaining Kotlin compiler warnings and flip `allWarningsAsErrors=true`

1. Run `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --rerun-tasks 2>&1 | grep -E '^w: '` to list every remaining warning.
2. Fix each warning cleanly (replace deprecated Android/Kotlin APIs with `Compat` equivalents, fix unchecked casts; only use `@Suppress("DEPRECATION")` with an explicit `// minSdk 26` comment where Android SDK forces a deprecated API on API < 33).
3. In `gradle.properties:31-32`, update to `qualityKit.allWarningsAsErrors=true` and remove the legacy adoption comment.

**Verify**: `./tools/quality/gate.sh fast` → exit 0 with `qualityKit.allWarningsAsErrors=true`.

### Step 3: Fix `StringFormatInvalid` and `StringFormatCount` in locale strings and prune `app/lint-baseline.xml`

1. Inspect the `StringFormatInvalid` (8) and `StringFormatCount` (12) `<issue>` entries in `app/lint-baseline.xml` to get exact locale file paths and string keys.
2. Fix the format specifiers (`%s`, `%1$s`, `%d`, etc.) in each cited `values-*/strings.xml` to match `values/strings.xml`.
3. Delete those 20 `<issue>` blocks from `app/lint-baseline.xml` (never add any new baseline entries!).

**Verify**: `./tools/quality/gate.sh full` → exit 0 (Android Lint passes with 0 new warnings and smaller `lint-baseline.xml`).

## Test plan

- Existing unit test suite + Android Lint + Kover via `./tools/quality/gate.sh full`.

## Done criteria

- [ ] `UserDataRepositoryHelper.kt` is deleted and zero references remain.
- [ ] `qualityKit.allWarningsAsErrors=true` in `gradle.properties`.
- [ ] Zero `StringFormatInvalid` or `StringFormatCount` entries remain in `app/lint-baseline.xml`.
- [ ] `./tools/quality/gate.sh full` exits 0.
- [ ] `plans/README.md` status row updated.

## STOP conditions

Stop and report back (do not improvise) if:
- Remaining Kotlin compiler warnings exceed ~60 distinct sites requiring invasive refactors outside deprecation/cast cleanup — pause and report the warning breakdown.
- Fixing `StringFormat*` triggers a new Lint error on untouched strings.

## Maintenance notes

- With `qualityKit.allWarningsAsErrors=true`, CI and `gate.sh fast` now match `AGENTS.md:27` literally and block any future compiler warnings.
