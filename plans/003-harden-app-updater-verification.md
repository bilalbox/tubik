# Plan 003: Harden `AppUpdater` host allowlist, APK `versionCode` check, and download size bounds

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f44e95979..HEAD -- app/src/main/java/com/github/libretube/updater/AppUpdater.kt app/src/main/java/com/github/libretube/updater/AndroidUpdatePlatform.kt app/src/main/java/com/github/libretube/ui/dialogs/AppUpdateDialog.kt app/src/test/java/com/github/libretube/updater/AppUpdaterTest.kt`
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

`AppUpdater` currently accepts any HTTPS host for `apkUrl` and `releaseNotesUrl` (drifting from `PRIVACY_POLICY.md:24` and `tools/release/update_website_manifest.py`, which host APKs strictly on `tubik-releases.23p.dev`), never verifies that the downloaded APK's `longVersionCode` matches `manifest.versionCode` and exceeds the installed version (permitting signed downgrade submission), and streams APK bodies with no byte ceiling or exact-size verification.

## Current state

- `app/src/main/java/com/github/libretube/updater/AppUpdater.kt:70-80`:
  ```kotlin
  private fun validateManifest(manifest: UpdateManifest): String? =
      when {
          manifest.schemaVersion != SUPPORTED_SCHEMA_VERSION ->
              "Unsupported schemaVersion: ${manifest.schemaVersion}"
          manifest.versionCode <= 0 || manifest.versionName.isBlank() ->
              "Invalid version metadata"
          !isHttpsUrl(manifest.apkUrl) -> "APK URL must use HTTPS"
          !isValidSha256Hex(manifest.sha256) -> "Invalid SHA-256 digest format"
          manifest.sizeBytes < 0L -> "Invalid negative sizeBytes"
          else -> null
      }
  ```
- `app/src/main/java/com/github/libretube/updater/AppUpdater.kt:112-115`:
  ```kotlin
  data class ApkVerificationInfo(
      val packageName: String,
      val signerSha256Set: Set<String>,
  )
  ```
- `app/src/main/java/com/github/libretube/updater/AppUpdater.kt:481-497` (`verifySignatures`) checks `archiveInfo.packageName` and `archiveInfo.signerSha256Set` only — no `versionCode` check.
- `app/src/main/java/com/github/libretube/updater/AppUpdater.kt:508-529` (`streamResponseBody`) reads until EOF with no maximum byte cap or `manifest.sizeBytes` enforcement.
- `app/src/main/java/com/github/libretube/updater/AndroidUpdatePlatform.kt:48-54` constructs `ApkVerificationInfo(packageName = info.packageName, signerSha256Set = extractSignerFingerprints(info))`.
- `app/src/main/java/com/github/libretube/ui/dialogs/AppUpdateDialog.kt:167-178` checks only `UpdateManifest.isHttpsUrl(notesUrl)` before opening `notesUrl` in an external browser.
- `app/src/test/java/com/github/libretube/updater/AppUpdaterTest.kt:32-42` defines `FakeUpdatePlatform` with `ApkVerificationInfo("dev.twenty3p.tubik", setOf(VALID_CERT))`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Fast gate | `./tools/quality/gate.sh fast` | exit 0 |
| Updater tests | `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.updater.AppUpdaterTest"` | BUILD SUCCESSFUL, all tests pass |
| Full gate | `./tools/quality/gate.sh full` | exit 0 |

## Scope

**In scope** (the only files you should modify):
- `app/src/main/java/com/github/libretube/updater/AppUpdater.kt`
- `app/src/main/java/com/github/libretube/updater/AndroidUpdatePlatform.kt`
- `app/src/main/java/com/github/libretube/ui/dialogs/AppUpdateDialog.kt`
- `app/src/test/java/com/github/libretube/updater/AppUpdaterTest.kt`
- `app/src/main/res/values/strings.xml` (only if adding a new error string for version mismatch; check existing `update_verification_*` strings first)

**Out of scope** (do NOT touch):
- `tools/release/update_website_manifest.py`, `website/updates/latest.json`
- `ScreenPinHelper.kt`, `UpdateInstallReceiver.kt`

## Git workflow

- Branch: `advisor/003-harden-app-updater-verification`
- Commit style: `fix(updater): enforce host allowlist, versionCode check, and download size bounds`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Enforce host allowlist on `apkUrl` and `releaseNotesUrl`

In `UpdateManifest.Companion` (`AppUpdater.kt`):
- Add `const val ALLOWED_APK_HOST = "tubik-releases.23p.dev"` and `const val ALLOWED_RELEASE_NOTES_HOST = "github.com"`, `const val ALLOWED_RELEASE_NOTES_PATH_PREFIX = "/bilalbox/tubik/releases"`.
- Add `fun isAllowedApkUrl(url: String): Boolean` (requires `url.trim().toHttpUrlOrNull()` non-null, `isHttps`, and `host.equals(ALLOWED_APK_HOST, ignoreCase = true)`).
- Add `fun isAllowedReleaseNotesUrl(url: String): Boolean` (blank is allowed; non-blank must be HTTPS `github.com` with `encodedPath.startsWith(ALLOWED_RELEASE_NOTES_PATH_PREFIX)`).
- Enforce `isAllowedApkUrl(manifest.apkUrl)` in `validateManifest`, `ApkTransport.downloadApk`, and `ApkTransport.validateHttpsSuccess` (when `isApkDownload = true`, check the final `response.request.url` after redirects so an open redirect cannot leave `tubik-releases.23p.dev`).
- Update `AppUpdateDialog.kt:168` to use `UpdateManifest.isAllowedReleaseNotesUrl(notesUrl) && notesUrl.isNotBlank()`.

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 2: Add `versionCode` verification to `ApkVerificationInfo` and `AppUpdater`

- Add `val versionCode: Long = 0L` (or required `Long`) to `ApkVerificationInfo` (`AppUpdater.kt:112`). In `AndroidUpdatePlatform.readApkVerificationInfo` (`AndroidUpdatePlatform.kt:50`), populate `versionCode = PackageInfoCompat.getLongVersionCode(info)` (from `androidx.core.content.pm.PackageInfoCompat`).
- Add `VerificationResult.VersionMismatch(val expectedMinVersionCode: Long, val actualVersionCode: Long)` (or `expected: Long, actual: Long`) to `VerificationResult`.
- In `ApkTransport.verifyApk` / `verifySignatures`, pass `expectedVersionCode: Long = manifest.versionCode.toLong()` and `currentVersionCode: Long`: reject if `archiveInfo.versionCode != expectedVersionCode || archiveInfo.versionCode <= currentVersionCode`.
- Update `AppUpdateDialog.kt` (and `AppUpdater.kt` error formatting if `VerificationResult` is exhaustively matched) to handle `VerificationResult.VersionMismatch` cleanly.

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 3: Enforce download size ceiling and exact byte-count check

In `ApkTransport` (`AppUpdater.kt`):
- Define `private const val MAX_APK_SIZE_BYTES = 200L * 1024L * 1024L`.
- In `UpdateManifest.validateManifest`, reject `manifest.sizeBytes > MAX_APK_SIZE_BYTES`.
- In `streamResponseBody`: compute `maxAllowedBytes = if (fallbackSizeBytes > 0L) minOf(fallbackSizeBytes, MAX_APK_SIZE_BYTES) else MAX_APK_SIZE_BYTES`. Inside the read loop, before/after writing, if `bytesReadTotal > maxAllowedBytes`, throw `IOException("APK download exceeded allowed size ($maxAllowedBytes bytes)")`. After the loop, if `fallbackSizeBytes > 0L && bytesReadTotal != fallbackSizeBytes`, throw `IOException("APK download size mismatch: expected $fallbackSizeBytes, got $bytesReadTotal")`.

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 4: Expand `AppUpdaterTest.kt` and run full gate

Update existing test fixtures in `AppUpdaterTest.kt` to use `https://tubik-releases.23p.dev/...` for valid `apkUrl` and populate `ApkVerificationInfo.versionCode`. Add unit tests covering:
1. `validateManifest` / `evaluate` rejects non-`tubik-releases.23p.dev` `apkUrl` and oversized `sizeBytes > MAX_APK_SIZE_BYTES`.
2. `downloadApk` rejects a redirect to a disallowed host.
3. `isAllowedReleaseNotesUrl` accepts `https://github.com/bilalbox/tubik/releases/tag/v1.3.1` and rejects arbitrary HTTPS hosts.
4. `verifyApk` returns `VerificationResult.VersionMismatch` when APK `versionCode` is lower than installed or does not match `manifest.versionCode`.
5. `downloadApk` aborts when response body exceeds `manifest.sizeBytes` or is truncated below `manifest.sizeBytes`.

**Verify**: `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.updater.AppUpdaterTest"` → all tests pass; `./tools/quality/gate.sh full` → exit 0.

## Test plan

- `app/src/test/java/com/github/libretube/updater/AppUpdaterTest.kt` (model after existing tests in the same file using `FakeUpdatePlatform` + `stubHttpClient`).
- Verification: `./tools/quality/gate.sh full` → exit 0.

## Done criteria

- [ ] `UpdateManifest` and `ApkTransport` reject APK URLs/redirects outside `https://tubik-releases.23p.dev`.
- [ ] `AppUpdateDialog` only shows/opens `releaseNotesUrl` on `https://github.com/bilalbox/tubik/releases/...`.
- [ ] `ApkTransport.verifyApk` enforces `archiveInfo.versionCode == manifest.versionCode.toLong() && archiveInfo.versionCode > currentVersionCode`.
- [ ] `streamResponseBody` enforces `MAX_APK_SIZE_BYTES` and exact `manifest.sizeBytes` match when `> 0`.
- [ ] `./tools/quality/gate.sh full` exits 0.
- [ ] `plans/README.md` status row updated.

## STOP conditions

Stop and report back (do not improvise) if:
- Existing tests in `AppUpdaterTest.kt` rely on a different `apkUrl` host pattern that reveals a documented alternate CDN host in `tools/release/`.
- `PackageInfoCompat.getLongVersionCode` is unavailable or `AppUpdater` callers cannot supply `currentVersionCode`.

## Maintenance notes

- If the APK hosting domain ever moves off `tubik-releases.23p.dev`, update `UpdateManifest.ALLOWED_APK_HOST`, `tools/release/update_website_manifest.py`, and `PRIVACY_POLICY.md` together.
