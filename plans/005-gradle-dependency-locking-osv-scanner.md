# Plan 005: Enable Gradle dependency locking so `osv-scanner` scans app dependencies

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f44e95979..HEAD -- app/build.gradle.kts baselineprofile/build.gradle.kts tools/quality/run-vuln-scan.sh osv-scanner.toml`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED
- **Depends on**: `plans/001-upgrade-quality-kit-qk.md`
- **Category**: security
- **Planned at**: commit `f44e95979`, 2026-11-28

## Why this matters

`tools/quality/run-vuln-scan.sh:79` runs `osv-scanner scan source -r . --allow-no-lockfiles`. Because no `gradle.lockfile` is committed in the repo, `osv-scanner` currently performs `0 Extract calls`, finds no package sources, and exits 0 — rendering the CI and `gate.sh full` dependency vulnerability check a silent no-op while shipping dozens of transitive Maven dependencies (`OkHttp`, `Media3`, `Protobuf`, `ONNX Runtime`, `NewPipeExtractor`, `Guava`).

## Current state

- `tools/quality/run-vuln-scan.sh:79-85`:
  ```bash
  args=(scan source -r . --allow-no-lockfiles)
  if [[ -f "${config}" ]]; then
  	args+=(--config "${config}")
  fi
  args+=("${extra_args[@]+"${extra_args[@]}"}")

  osv-scanner "${args[@]}"
  ```
- `app/build.gradle.kts` and `baselineprofile/build.gradle.kts` use `gradle/libs.versions.toml` without `dependencyLocking { lockAllConfigurations() }`.
- `osv-scanner.toml` is empty (template comments only).

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Write locks | `./gradlew dependencies --write-locks` | BUILD SUCCESSFUL; creates `gradle.lockfile` |
| Vuln scan | `./tools/quality/run-vuln-scan.sh` | exit 0; scans > 0 Gradle packages |
| Full gate | `./tools/quality/gate.sh full` | exit 0 |

## Scope

**In scope** (the only files you should modify/create):
- `app/build.gradle.kts`
- `baselineprofile/build.gradle.kts`
- `app/gradle.lockfile` (create)
- `baselineprofile/gradle.lockfile` (create)
- `tools/quality/run-vuln-scan.sh` (only to ensure zero-package scans fail, e.g. removing `--allow-no-lockfiles`)
- `AGENTS.md` (add one short note under `## Definition of Done` on refreshing lockfiles with `./gradlew dependencies --write-locks` after dependency changes)

**Out of scope** (do NOT touch):
- `build-logic/quality/build.gradle.kts` (composite build plugins; keep out of scope unless needed)
- `gradle/libs.versions.toml` (do NOT bump or downgrade dependencies in this plan; if `osv-scanner` reports real vulnerabilities, STOP and report)
- `osv-scanner.toml` (do NOT suppress newly discovered real CVEs without maintainer review)

## Git workflow

- Branch: `advisor/005-gradle-dependency-locking-osv-scanner`
- Commit style: `chore(security): enable Gradle dependency locking for osv-scanner`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Enable `dependencyLocking` in `:app` and `:baselineprofile`

Add at the top-level (outside `android { ... }`) in both `app/build.gradle.kts` and `baselineprofile/build.gradle.kts`:
```kotlin
dependencyLocking {
    lockAllConfigurations()
}
```
**Verify**: `./gradlew :app:dependencies --write-locks :baselineprofile:dependencies --write-locks` → BUILD SUCCESSFUL; `test -f app/gradle.lockfile && test -f baselineprofile/gradle.lockfile` → exit 0.

### Step 2: Verify Gradle builds cleanly with lockfiles enforced

Run `./gradlew qualityFast` to confirm all configurations resolve deterministically against the committed lockfiles (including `detekt`, `ktfmt`, and `testDebugUnitTest` configurations). If any configuration fails due to an unlocked/missing entry in `gradle.lockfile`, re-run `./gradlew --write-locks qualityFull` once so test/lint/kover configurations are also locked.

**Verify**: `./gradlew qualityFast` → BUILD SUCCESSFUL with no lockfile mismatch errors.

### Step 3: Remove `--allow-no-lockfiles` from `run-vuln-scan.sh` and run `osv-scanner`

Update `tools/quality/run-vuln-scan.sh:79`:
```bash
args=(scan source -r .)
```
Run `./tools/quality/run-vuln-scan.sh`.
- If `osv-scanner` finds **0 vulnerabilities** and reports scanned Gradle packages (>0), proceed to Step 4.
- If `osv-scanner` reports **real vulnerabilities** in `app/gradle.lockfile`, **STOP and report the CVE/GHSA IDs and affected packages** — do NOT blindly add them to `osv-scanner.toml`!

**Verify**: `./tools/quality/run-vuln-scan.sh` → exit 0 and output confirms `gradle.lockfile` was scanned.

### Step 4: Document lockfile refresh in `AGENTS.md` and run `gate.sh full`

Add one concise bullet in `AGENTS.md` noting: "When changing dependencies in `gradle/libs.versions.toml`, regenerate lockfiles with `./gradlew --write-locks qualityFull` and commit `*/gradle.lockfile`."

**Verify**: `./tools/quality/gate.sh full` → exit 0.

## Test plan

- Verification is via `./tools/quality/run-vuln-scan.sh` (confirming `gradle.lockfile` extraction > 0 packages) and `./tools/quality/gate.sh full`.

## Done criteria

- [ ] `app/gradle.lockfile` and `baselineprofile/gradle.lockfile` are committed and `dependencyLocking { lockAllConfigurations() }` is enabled.
- [ ] `tools/quality/run-vuln-scan.sh` does not pass `--allow-no-lockfiles` and exits 0 after scanning Gradle lockfiles.
- [ ] `./tools/quality/gate.sh full` exits 0.
- [ ] `plans/README.md` status row updated.

## STOP conditions

Stop and report back (do not improvise) if:
- `osv-scanner` reports real HIGH/CRITICAL vulnerabilities in the current locked dependencies (list the IDs, packages, and versions and stop).
- Gradle dynamic configurations (e.g. `testFixtures` or `ksp`) fail to lock cleanly after one `--write-locks` pass.

## Maintenance notes

- `tools/quality/run-vuln-scan.sh` is a `quality-kit` owned file; customizing it to drop `--allow-no-lockfiles` will show as `customized` in `./qk audit` until `quality-kit` adopts the same guard.
- Every Renovate or manual dependency bump must refresh `app/gradle.lockfile` and `baselineprofile/gradle.lockfile`.
