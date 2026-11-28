# Plan 002: Disable Android Auto Backup and harden `network_security_config.xml`

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f44e95979..HEAD -- app/src/main/AndroidManifest.xml app/src/main/res/xml/network_security_config.xml app/src/main/res/xml/data_extraction_rules.xml`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: security
- **Planned at**: commit `f44e95979`, 2026-11-28

## Why this matters

`PRIVACY_POLICY.md` commits that the Parent PIN hash (`auth.xml`), crash logs, subscriptions, watch history, and settings stay strictly on the device and are never uploaded. Yet `AndroidManifest.xml` sets `android:allowBackup="true"` with no exclusion rules, allowing Android Auto Backup to upload `auth.xml` and user data to cloud storage. Additionally, `network_security_config.xml` globally enables `cleartextTrafficPermitted="true"` and trusts `<certificates src="user" />` (leftover from self-hosted Piped), permitting cleartext fallback and user-CA interception of OTA manifest (`https://tubik.23p.dev/updates/latest.json`) and YouTube/SponsorBlock traffic in release builds.

## Current state

- `app/src/main/AndroidManifest.xml:23-38`:
  ```xml
    <application
        android:name=".LibreTubeApp"
        android:allowBackup="true"
        ...
        android:networkSecurityConfig="@xml/network_security_config"
  ```
- `app/src/main/res/xml/network_security_config.xml:1-16`:
  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <network-security-config xmlns:tools="http://schemas.android.com/tools">
      <!-- Allow clear text traffic -->
      <base-config
          cleartextTrafficPermitted="true"
          tools:ignore="InsecureBaseConfiguration">
          <trust-anchors>
              <!-- Trust preinstalled CAs -->
              <certificates src="system" />
              <!-- Additionally trust user added CAs -->
              <certificates
                  src="user"
                  tools:ignore="AcceptsUserCertificates" />
          </trust-anchors>
      </base-config>
  </network-security-config>
  ```
- `app/src/main/java/com/github/libretube/helpers/PreferenceHelper.kt:269-283` stores Parent PIN hash/salt in `auth.xml`.
- `minSdk = 26`, `targetSdk = 36` (`app/build.gradle.kts:38-39`).

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Fast gate | `./tools/quality/gate.sh fast` | exit 0 |
| Targeted test | `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.security.PlatformHardeningTest"` | BUILD SUCCESSFUL, all tests pass |
| Full gate | `./tools/quality/gate.sh full` | exit 0 |

## Scope

**In scope** (the only files you should modify/create):
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/xml/data_extraction_rules.xml` (create)
- `app/src/main/res/xml/network_security_config.xml`
- `app/src/test/java/com/github/libretube/security/PlatformHardeningTest.kt` (create)

**Out of scope** (do NOT touch):
- `PRIVACY_POLICY.md`, `app/lint-baseline.xml` (unless lint requires removing baselined entries? No, removing `tools:ignore` does not add lint issues; do not add to `lint-baseline.xml`), `AppUpdater.kt`.

## Git workflow

- Branch: `advisor/002-android-auto-backup-and-network-security`
- Commit style: `fix(security): disable Android Auto Backup and harden network security config`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Disable Auto Backup and add `data_extraction_rules.xml`

Create `app/src/main/res/xml/data_extraction_rules.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
        <exclude domain="file" path="." />
    </cloud-backup>
    <device-transfer>
        <exclude domain="sharedpref" path="auth.xml" />
    </device-transfer>
</data-extraction-rules>
```
Update `app/src/main/AndroidManifest.xml:25` to set:
```xml
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
```
(Keep `tools:targetApi="tiramisu"` on `<application>` or use `tools:ignore="DataExtractionRules"` only if `minSdk = 26` lint warns; test with lint first — `tools:targetApi="tiramisu"` is already on `<application>` at line 38.)

**Verify**: `grep -n "allowBackup=\"false\"" app/src/main/AndroidManifest.xml && grep -n "dataExtractionRules" app/src/main/AndroidManifest.xml` → both match.

### Step 2: Harden `network_security_config.xml`

Replace `app/src/main/res/xml/network_security_config.xml` with:
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
    <debug-overrides>
        <trust-anchors>
            <certificates src="user" />
        </trust-anchors>
    </debug-overrides>
</network-security-config>
```
Notice the `xmlns:tools` and `tools:ignore` suppressions are removed because the config is now compliant.

**Verify**: `./gradlew :app:lintDebug` (or `./tools/quality/gate.sh fast`) → exit 0 with no new security lint warnings.

### Step 3: Add `PlatformHardeningTest.kt` and run full gate

Create `app/src/test/java/com/github/libretube/security/PlatformHardeningTest.kt` (using Robolectric, modeled after `app/src/test/java/com/github/libretube/ui/activities/AboutActivityTest.kt`):
- Assert `(ApplicationProvider.getApplicationContext<Context>().applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) == 0`.
- Parse `R.xml.network_security_config` via `Resources.getXml` and assert `base-config` has `cleartextTrafficPermitted == "false"` and no `<certificates src="user">` inside `<base-config>`.
- Parse `R.xml.data_extraction_rules` and assert `<cloud-backup>` excludes `.` and `<device-transfer>` excludes `auth.xml`.

**Verify**: `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.security.PlatformHardeningTest"` → all pass; `./tools/quality/gate.sh full` → exit 0.

## Test plan

- `PlatformHardeningTest.kt`:
  1. `manifest disables allowBackup`
  2. `network_security_config disables cleartext and restricts base trust anchors to system`
  3. `data_extraction_rules excludes cloud backup and auth sharedprefs`
- Verification: `./tools/quality/gate.sh full` → exit 0.

## Done criteria

- [ ] `android:allowBackup="false"` and `android:dataExtractionRules="@xml/data_extraction_rules"` are set in `AndroidManifest.xml`.
- [ ] `network_security_config.xml` sets `cleartextTrafficPermitted="false"` and trusts only `system` CAs in `<base-config>`.
- [ ] `PlatformHardeningTest` exists and passes.
- [ ] `./tools/quality/gate.sh full` exits 0 (mandatory for manifest changes per `AGENTS.md`).
- [ ] `plans/README.md` status row updated.

## STOP conditions

Stop and report back (do not improvise) if:
- `AndroidManifest.xml` or `network_security_config.xml` differs from the excerpts above.
- Android Lint rejects `android:dataExtractionRules` under `minSdk = 26` and requires a `fullBackupContent` companion attribute (report before adding an unrequested file).

## Maintenance notes

- If any future feature legitimately requires local-network cleartext (none are planned; `ROADMAP.md` excludes local servers), add a narrow `<domain-config>` rather than re-enabling cleartext in `<base-config>`.
