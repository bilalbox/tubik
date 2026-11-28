# Plan 017: Fix `buildLanguages` configuration-phase mutation, remove `mavenLocal()`, and migrate off AGP 9 legacy opt-outs

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f44e95979..HEAD -- build.gradle.kts settings.gradle.kts gradle.properties app/build.gradle.kts baselineprofile/build.gradle.kts`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: MED
- **Depends on**: `plans/001-upgrade-quality-kit-qk.md`, `plans/005-gradle-dependency-locking-osv-scanner.md`
- **Category**: migration
- **Planned at**: commit `f44e95979`, 2026-11-28

## Why this matters

1. `build.gradle.kts:15-49` runs `buildLanguages` file generation directly in the Gradle configuration block instead of `doLast`, rewriting `app/src/main/res/values/languages.xml` in unsorted filesystem order on every Gradle invocation and breaking configuration caching.
2. `settings.gradle.kts:22` includes `mavenLocal()`, risking non-reproducible dependency resolution from `~/.m2`.
3. `gradle.properties:23-24` sets `org.gradle.unsafe.configuration-cache-problems=warn` (masking cache regressions) and `android.newDsl=false` (an AGP 9 opt-out slated for removal in AGP 10).

## Current state

- `build.gradle.kts:15-49`:
  ```kotlin
  tasks.register("buildLanguages") {
      val projectDirectory = layout.projectDirectory
      val resPath = projectDirectory.file("app/src/main/res")
      val locales = resPath.asFile.listFiles()...
      ...
      outputFile.bufferedWriter().use { it.write(localesConfig) }
  }
  ```
- `settings.gradle.kts:22`: `mavenLocal()`.
- `gradle.properties:22-24`:
  ```properties
  org.gradle.configuration-cache=true
  org.gradle.unsafe.configuration-cache-problems=warn
  android.newDsl=false
  ```

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Config cache check | `./gradlew help --configuration-cache` | exit 0; `git status --short` clean |
| Fast gate | `./tools/quality/gate.sh fast` | exit 0 |
| Full gate | `./tools/quality/gate.sh full` | exit 0 |

## Scope

**In scope** (the only files you should modify):
- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle.properties`
- `app/build.gradle.kts` (only if `android.newDsl=true` requires DSL adjustments)
- `baselineprofile/build.gradle.kts` (only if `android.newDsl=true` requires DSL adjustments)
- `build-logic/quality/src/main/kotlin/QualityKitAndroidPlugin.kt` (only if `android.newDsl=true` requires plugin wiring adjustment)

**Out of scope** (do NOT touch):
- `app/src/main/res/values/languages.xml` (do not commit spurious diffs unless `buildLanguages` output is intentionally sorted and clean)
- `gradle/libs.versions.toml`

## Git workflow

- Branch: `advisor/017-fix-build-languages-and-agp9-migration`
- Commit style: `build: fix buildLanguages configuration phase, drop mavenLocal, and enable AGP newDsl`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Move `buildLanguages` into `doLast` with deterministic sorting and remove `mavenLocal()`

1. In `build.gradle.kts:15-49`, refactor `buildLanguages` so `resDir` and `outputFile` are declared as `@InputDirectory` / `@OutputFile` (via `inputs.dir(...)` / `outputs.file(...)`), sort `locales` (`sorted()`), and move all file reading/writing inside `doLast { ... }`.
2. In `settings.gradle.kts:22`, remove `mavenLocal()`.

**Verify**: `./gradlew help && git status --short app/src/main/res/values/languages.xml` → `help` exits 0 and `languages.xml` is NOT modified.

### Step 2: Remove `org.gradle.unsafe.configuration-cache-problems=warn`

Remove line 23 from `gradle.properties` and run `./gradlew qualityFast --configuration-cache`. Fix any configuration-cache violations in `build.gradle.kts` / `app/build.gradle.kts` / `baselineprofile/build.gradle.kts` (for example, `keystoreProperties` loading at configuration time if flagged — use `providers.fileContents` or `layout.projectDirectory` if needed).

**Verify**: `./gradlew qualityFast --configuration-cache` → exits 0 with configuration cache stored/reused cleanly.

### Step 3: Test removing `android.newDsl=false`

Remove `android.newDsl=false` from `gradle.properties:24` (AGP 9 defaults to `android.newDsl=true`). Run `./gradlew qualityFast`.
- If third-party plugins (`google-protobuf`, `roborazzi`, or `dependency-analysis`) fail due to internal AGP 9 new-DSL incompatibilities that cannot be fixed cleanly in `app/build.gradle.kts` / `baselineprofile/build.gradle.kts`, **STOP and report** (keep `android.newDsl=false` with a comment naming the blocking plugin).
- If it succeeds or requires only minor DSL updates in `app/build.gradle.kts` / `baselineprofile/build.gradle.kts`, apply them and run `./tools/quality/gate.sh full` + `./gradlew :app:compileReleaseKotlin`.

**Verify**: `./tools/quality/gate.sh full` → exit 0.

## Test plan

- Build/configuration-cache verification via `./gradlew qualityFast --configuration-cache`, `./tools/quality/gate.sh full`, and `./gradlew :app:compileReleaseKotlin`.

## Done criteria

- [ ] `buildLanguages` runs file I/O only inside `doLast` and `./gradlew help` does not dirty `languages.xml`.
- [ ] `mavenLocal()` is removed from `settings.gradle.kts`.
- [ ] `org.gradle.unsafe.configuration-cache-problems=warn` is removed and configuration cache passes.
- [ ] `android.newDsl=false` is removed (or documented STOP condition reported if a plugin blocks it).
- [ ] `./tools/quality/gate.sh full` exits 0.
- [ ] `plans/README.md` status row updated.

## STOP conditions

Stop and report back (do not improvise) if:
- A third-party Gradle plugin (`google-protobuf`, `roborazzi`, `dependency-analysis`) throws an internal error under `android.newDsl=true` that cannot be resolved in the build scripts.
- Removing `mavenLocal()` fails to resolve a dependency (which would mean a dependency was only in `~/.m2`).

## Maintenance notes

- Run `./gradlew buildLanguages` only manually when adding/removing `values-<locale>/strings.xml` directories.
