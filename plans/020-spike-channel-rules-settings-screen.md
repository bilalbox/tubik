# Plan 020: Spike — Centralized "Channel rules" Settings screen (aggregation model + design spec)

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f44e95979..HEAD -- app/src/main/java/com/github/libretube/util/AccessControl.kt app/src/main/java/com/github/libretube/util/ChannelSettingSet.kt app/src/main/res/xml/settings.xml`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: LOW
- **Depends on**: none
- **Category**: direction
- **Planned at**: commit `f44e95979`, 2026-11-28

## Why this matters

Per-channel overrides are currently scattered across four separate preference sets with no overview UI: **Channel Opt-Out** (`AccessControl.musicRemovalDisabledChannels`), **Play as audio by default** (`ChannelSettingSet.audioOnlyDefault`), **Feed Scope** (`ChannelSettingSet.seriesOnlyFeedScope`), and muted notifications (`PreferenceHelper.getIgnorableNotificationChannels()`). Parents cannot audit which channels have **Channel Opt-Out** active (security-relevant because it bypasses **Music Removal**) without visiting each channel page individually. This spike delivers a pure, unit-tested aggregation model plus a concrete UI/UX design spec (`docs/design/channel-rules-screen.md`) ready for maintainer review.

## Current state

- `app/src/main/java/com/github/libretube/util/AccessControl.kt:347-358`: `musicRemovalDisabledChannels` (comma-separated channel IDs; toggling requires `PolicyChange.SetChannelMusicRemoval` + **Parent PIN**).
- `app/src/main/java/com/github/libretube/util/ChannelSettingSet.kt:34-42`: `audioOnlyDefault` and `seriesOnlyFeedScope` (no PIN required per `ChannelSettingSet.kt:7-9`).
- `app/src/main/java/com/github/libretube/helpers/PreferenceHelper.kt:314-325`: `getIgnorableNotificationChannels()` (note `"".split(",")` returns `[""]` — aggregation must filter `isNotBlank()`).
- `app/src/main/res/xml/settings.xml:5-41`: `<PreferenceCategory app:title="@string/app_name">` holds **Allowlist**, **Music Removal**, **Screen Pin**, **Lock Settings**, and **Parent PIN**.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Fast gate | `./tools/quality/gate.sh fast` | exit 0 |
| Channel rules tests | `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.util.ChannelRulesSummaryTest"` | BUILD SUCCESSFUL, all tests pass |
| Full gate | `./tools/quality/gate.sh full` | exit 0 |

## Scope

**In scope** (the only files you should modify/create):
- `app/src/main/java/com/github/libretube/util/ChannelRulesSummary.kt` (create — pure aggregation model & helper)
- `app/src/test/java/com/github/libretube/util/ChannelRulesSummaryTest.kt` (create)
- `docs/design/channel-rules-screen.md` (create — design spec & open questions)

**Out of scope** (do NOT touch):
- `settings.xml`, `MainSettings.kt`, `AccessControl.kt`, `ChannelSettingSet.kt` (keep this spike additive; full UI implementation follows maintainer review of the design spec)

## Git workflow

- Branch: `advisor/020-spike-channel-rules-settings-screen`
- Commit style: `feat(settings): add ChannelRulesSummary aggregation model and Channel rules design spike`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Implement `ChannelRulesSummary.kt`

Create `app/src/main/java/com/github/libretube/util/ChannelRulesSummary.kt` containing:
- `data class ChannelRuleEntry(val channelId: String, val channelName: String?, val uploaderAvatar: String?, val isSubscribed: Boolean, val musicRemovalOptOut: Boolean, val audioOnlyDefault: Boolean, val seriesOnlyFeedScope: Boolean, val notificationsMuted: Boolean)`
- `data class ChannelRulesSummary(val subscribedWithRules: List<ChannelRuleEntry>, val orphanedWithRules: List<ChannelRuleEntry>, val optOutCount: Int)`
- `fun buildChannelRulesSummary(subscriptions: List<LocalSubscription>, musicRemovalDisabledChannels: Set<String>, audioOnlyChannels: Set<String>, seriesOnlyChannels: Set<String>, mutedNotificationChannels: Set<String>): ChannelRulesSummary`
  - Normalizes IDs via `normalizeChannelId(...)`, ignores blank entries (handling `"".split(",")` safely), joins against `subscriptions` for display name/avatar, separates subscribed vs unsubscribed ("orphaned") channels that have at least one active rule, and sorts deterministically (Channel Opt-Out first, then by name/ID).

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 2: Add `ChannelRulesSummaryTest.kt`

Test:
1. Empty/blank preference strings produce 0 entries (handling `"".split(",")` edge case).
2. Subscribed channel with **Channel Opt-Out** + **Feed Scope** appears in `subscribedWithRules` with `optOutCount == 1`.
3. Unsubscribed channel with a leftover override appears in `orphanedWithRules` with `isSubscribed == false`.
4. Channel IDs in URL or `@` / `UC...` form normalize consistently.

**Verify**: `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.util.ChannelRulesSummaryTest"` → all pass.

### Step 3: Write `docs/design/channel-rules-screen.md` and run full gate

Document:
1. Entry point in `settings.xml` under the Tubik category (summary showing e.g. `"2 Music Removal opt-outs · 3 audio-only"`).
2. Row layout & inline toggles: **Channel Opt-Out** toggle gated by `AccessControl.apply(PolicyChange.SetChannelMusicRemoval(...), ...)` (**Parent PIN**); **Play as audio by default** and **Feed Scope** without PIN.
3. Open decision on orphaned channel IDs (show in a collapsible "Unsubscribed channels" section with one-tap "Clear rules" vs auto-pruning on unsubscribe).

**Verify**: `./tools/quality/gate.sh full` → exit 0.

## Test plan

- `ChannelRulesSummaryTest.kt` (new) + `./tools/quality/gate.sh full`.

## Done criteria

- [ ] `ChannelRulesSummary.kt` and `ChannelRulesSummaryTest.kt` exist and pass.
- [ ] `docs/design/channel-rules-screen.md` exists and uses `GLOSSARY.md` terms (**Channel Opt-Out**, **Parent PIN**, **Music Removal**, **Feed Scope**, **Play as audio by default**).
- [ ] `./tools/quality/gate.sh full` exits 0.
- [ ] `plans/README.md` status row updated.

## STOP conditions

Stop and report back (do not improvise) if:
- `LocalSubscription` entity fields differ from `channelId`, `name`, `uploaderAvatar` (read `LocalSubscription.kt` before implementing Step 1).

## Maintenance notes

- Once approved, the UI Fragment only needs to collect `ChannelRulesSummary` and dispatch `PolicyChange.SetChannelMusicRemoval` / `ChannelSettingSet.set(...)`.
