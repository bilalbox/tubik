# Plan 001: Upgrade vendored quality-kit to latest `/qk`

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f44e95979..HEAD -- .quality-kit.lock.json Makefile tools/quality/ build-logic/quality/gradle/libs.versions.toml .github/workflows/ci.yml`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: dx
- **Planned at**: commit `f44e95979`, 2026-11-28

## Why this matters

Tubik's vendored quality-kit (`.quality-kit.lock.json`) is pinned to revision `1f8c10fe97cf70508a93e605e2b689ddd385c307`, while the local `/home/bilal/mycode/quality-kit` (`./qk`) repository has advanced to `75d2e5a`. Upgrading brings parallel `gate:fast` execution, the `gate:diff` tier (`make gate-diff`), the zero-dependency file-size ratchet (`baselines/file-size.json`), `tools/quality/quality.mk` Makefile wiring, and `--pack kotlin-android` coverage scoping without losing Tubik's intentional customizations.

## Current state

- `.quality-kit.lock.json:3` records `"kitRevision": "1f8c10fe97cf70508a93e605e2b689ddd385c307"`.
- Running `cd /home/bilal/mycode/quality-kit && ./qk audit --target /home/bilal/mycode/tubik --packs kotlin-android` reports:
  - **11 stale owned files** (unchanged since apply; kit moved on):
    `tools/quality/kotlin-android/run-format.sh`, `.betterleaks.toml`, `tools/quality/gate-lib.sh`, `tools/quality/run-secret-scan.sh`, `tools/quality/check-baseline-growth.sh`, `tools/quality/run-vuln-scan.sh`, `tools/quality/run-coverage-ratchet.sh`, `tools/quality/ratchets/compare.py`, `tools/quality/ratchets/compare.ts`, `tools/quality/ratchets/run-tool.ts`, `tools/quality/gate.sh`.
  - **3 missing files** (`upgrade` never creates files; requires `apply`):
    `tools/quality/run-file-size-ratchet.sh`, `tools/quality/quality.mk`, `baselines/file-size.json`.
  - **1 unmerged file**: `Makefile` (lacks `-include tools/quality/quality.mk`).
  - **3 customized owned files** ("Merge by hand" per `quality-kit/SKILL.md` Drift playbook):
    1. `build-logic/quality/gradle/libs.versions.toml` — Tubik has newer pins (`agp = "9.5.3"`, `roborazzi = "1.79.0"`, `robolectric = "4.18.1"`, `composablePreviewScanner = "0.10.1"`) matching `gradle/libs.versions.toml` vs template's `9.5.0`/`1.77.0`/`4.17`/`0.9.3`. **Keep** Tubik's versions.
    2. `tools/quality/kotlin-android/gate.sh` — Tubik added `step native-decode "${SCRIPT_DIR}/native-decode-check.sh"` in `full` mode (`tools/quality/kotlin-android/gate.sh:44`). **Merge** the new template (`diff|fast|full`, `quality_gate_parse_args`, `step_parallel`, `file-size`, `--pack kotlin-android` on coverage) while preserving `step native-decode`.
    3. `.github/workflows/ci.yml` — Tubik added `push: branches: [master]` (`ci.yml:4-6`). **Keep** Tubik's version.
- Agent hooks (`quality-kit/SKILL.md:68`): legacy Targets with a pre-existing `.quality-kit.lock.json` lacking `agentHooks` and `.claude/settings.json` stay opted out on `audit`/`upgrade`/`apply` unless `--agent-hooks` is explicitly passed. Keep opted-out by default.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Kit audit | `cd /home/bilal/mycode/quality-kit && ./qk audit --target /home/bilal/mycode/tubik --packs kotlin-android` | exit 0; 0 stale, 0 missing, 0 unmerged |
| Upgrade dry-run | `cd /home/bilal/mycode/quality-kit && ./qk upgrade --target /home/bilal/mycode/tubik --dry-run` | exit 0 |
| Fast gate | `./tools/quality/gate.sh fast` | exit 0 |
| Diff gate | `./tools/quality/gate.sh diff` | exit 0 |
| Full gate | `./tools/quality/gate.sh full` | exit 0 |

## Scope

**In scope** (the only files you should modify/create):
- `.quality-kit.lock.json`
- `.betterleaks.toml`
- `Makefile`
- `baselines/file-size.json` (create)
- `tools/quality/**` (kit-managed files + `tools/quality/kotlin-android/gate.sh` merge)
- `build-logic/quality/gradle/libs.versions.toml` (only if inspected; keep Tubik's pins!)
- `.github/workflows/ci.yml` (keep Tubik's `push: branches: [master]` trigger)

**Out of scope** (do NOT touch):
- `app/**`, `baselineprofile/**`, `gradle/libs.versions.toml`, `gradle.properties`
- `tools/quality/kotlin-android/native-decode-check.sh` and `.cpp`

## Git workflow

- Branch: `advisor/001-upgrade-quality-kit-qk`
- Commit style: conventional commits, e.g. `chore(quality): upgrade quality-kit to 75d2e5a`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Confirm clean dry-run and run `./qk upgrade`

Run from `/home/bilal/mycode/quality-kit`:
```bash
cd /home/bilal/mycode/quality-kit && ./qk upgrade --target /home/bilal/mycode/tubik --dry-run && ./qk upgrade --target /home/bilal/mycode/tubik
```
This updates the 11 stale owned files and refreshes `kitRevision` in `.quality-kit.lock.json` without touching the 3 customized files.

**Verify**: `cd /home/bilal/mycode/tubik && git diff --name-only` shows the 11 stale files + `.quality-kit.lock.json` updated, and `build-logic/quality/gradle/libs.versions.toml` still has `agp = "9.5.3"`.

### Step 2: Run `./qk apply --wire` to add missing kit files and `Makefile` include

```bash
cd /home/bilal/mycode/quality-kit && ./qk apply --target /home/bilal/mycode/tubik --packs kotlin-android --wire
```
**Verify**: `test -f /home/bilal/mycode/tubik/tools/quality/run-file-size-ratchet.sh && test -f /home/bilal/mycode/tubik/tools/quality/quality.mk && test -f /home/bilal/mycode/tubik/baselines/file-size.json && grep -q "quality.mk" /home/bilal/mycode/tubik/Makefile` → exit 0; `build-logic/quality/gradle/libs.versions.toml` still has `agp = "9.5.3"` and `robolectric = "4.18.1"`.

### Step 3: Merge `tools/quality/kotlin-android/gate.sh`

Copy/merge the new template `/home/bilal/mycode/quality-kit/languages/kotlin-android/templates/tools/quality/kotlin-android/gate.sh` into `tools/quality/kotlin-android/gate.sh`, preserving Tubik's `native-decode-check.sh` step in `full` mode right after `coverage`:
```bash
if [[ "${mode}" == "full" ]]; then
	# Kover XML from qualityFull; ratchet replaces the old minLineCoverage floor.
	step coverage "${SCRIPT_DIR}/../run-coverage-ratchet.sh" --pack kotlin-android
	# Guards libtubik_native's slot decoding against NDK compiler miscompiles.
	step native-decode "${SCRIPT_DIR}/native-decode-check.sh"
fi
```
Verify `.github/workflows/ci.yml` still retains `push: branches: [master]`.

**Verify**: `bash -n tools/quality/kotlin-android/gate.sh && grep -q "native-decode-check.sh" tools/quality/kotlin-android/gate.sh && grep -q "\-\-pack kotlin-android" tools/quality/kotlin-android/gate.sh` → exit 0.

### Step 4: Verify file-size ratchet baseline and run `qk audit` + all gates

Check `baselines/file-size.json` by running `./tools/quality/run-file-size-ratchet.sh`. If legacy large files (e.g., `CustomExoPlayerView.kt`, `PlayerFragment.kt`) are not yet seeded in `baselines/file-size.json`, run `./tools/quality/run-file-size-ratchet.sh --update-baseline` (or the flag printed by `run-file-size-ratchet.sh --help`) so existing files are baselined without failing.

**Verify**:
1. `cd /home/bilal/mycode/quality-kit && ./qk audit --target /home/bilal/mycode/tubik --packs kotlin-android` → 0 stale, 0 missing, 0 unmerged.
2. `cd /home/bilal/mycode/tubik && ./tools/quality/gate.sh diff && ./tools/quality/gate.sh fast && ./tools/quality/gate.sh full` → all exit 0.

## Test plan

- No Kotlin unit tests are added; verification is via `qk audit`, `run-file-size-ratchet.sh`, and `gate.sh diff` / `fast` / `full` (which runs the full unit-test suite, Kover ratchet, Lint, `buildHealth`, and `native-decode-check.sh`).

## Done criteria

- [ ] `.quality-kit.lock.json` `kitRevision` matches `/home/bilal/mycode/quality-kit` HEAD (`75d2e5a...`).
- [ ] `cd /home/bilal/mycode/quality-kit && ./qk audit --target /home/bilal/mycode/tubik --packs kotlin-android` reports 0 stale, 0 missing, 0 unmerged.
- [ ] `tools/quality/kotlin-android/gate.sh` still runs `native-decode-check.sh` in `full` mode.
- [ ] `build-logic/quality/gradle/libs.versions.toml` retains `agp = "9.5.3"`, `roborazzi = "1.79.0"`, `robolectric = "4.18.1"`.
- [ ] `./tools/quality/gate.sh diff`, `./tools/quality/gate.sh fast`, and `./tools/quality/gate.sh full` all exit 0.
- [ ] `plans/README.md` status row updated.

## STOP conditions

Stop and report back (do not improvise) if:
- `/home/bilal/mycode/quality-kit` does not exist or has uncommitted changes.
- `./qk apply --wire` overwrites or downgrades `build-logic/quality/gradle/libs.versions.toml` or `.github/workflows/ci.yml`.
- `./tools/quality/gate.sh full` fails on a pre-existing issue after merging `gate.sh` (twice after a reasonable fix attempt).

## Maintenance notes

- Customized files intentionally kept (`build-logic/quality/gradle/libs.versions.toml`, `tools/quality/kotlin-android/gate.sh`, `.github/workflows/ci.yml`) will continue to show as `customized` in `qk audit`; future upgrades must keep `native-decode-check.sh` in `gate.sh`.
