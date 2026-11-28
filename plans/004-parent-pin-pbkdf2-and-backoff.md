# Plan 004: Upgrade Parent PIN hashing to PBKDF2 and add verification backoff

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f44e95979..HEAD -- app/src/main/java/com/github/libretube/util/AccessControl.kt app/src/main/java/com/github/libretube/helpers/PreferenceHelper.kt app/src/main/java/com/github/libretube/constants/PreferenceKeys.kt app/src/main/java/com/github/libretube/ui/dialogs/ParentPinDialogs.kt app/src/test/java/com/github/libretube/util/AccessControlTest.kt`
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

`AccessControl.hashPin` currently computes a single round of `SHA-256(salt || pin)` (`AccessControl.kt:316-321`) and `checkPin` has no rate limiting or lockout (`AccessControl.kt:276-292`), while `ParentPinDialogs` uses numeric PIN input (`TYPE_CLASS_NUMBER`). A 4–6 digit **Parent PIN** can be guessed repeatedly in the UI without cooldown and cracked offline trivially if `auth.xml` is ever extracted.

## Current state

- `app/src/main/java/com/github/libretube/util/AccessControl.kt:276-295, 316-328`:
  ```kotlin
  fun checkPin(pin: String): Boolean {
      val storedHash = storage.pinHash
      val storedSalt = storage.pinSalt
      val normalized = pin.trim()
      val saltBytes = storedSalt?.let {
          runCatching { Base64.getDecoder().decode(it) }.getOrNull()
      }
      return if (storedHash == null || saltBytes == null || normalized.isEmpty()) {
          false
      } else {
          val candidateHash = hashPin(normalized, saltBytes)
          MessageDigest.isEqual(
              storedHash.toByteArray(Charsets.UTF_8),
              candidateHash.toByteArray(Charsets.UTF_8),
          )
      }
  }
  ...
  private fun hashPin(pin: String, saltBytes: ByteArray): String {
      val digest = MessageDigest.getInstance("SHA-256")
      digest.update(saltBytes)
      val hashed = digest.digest(pin.toByteArray(Charsets.UTF_8))
      return Base64.getEncoder().encodeToString(hashed)
  }
  ```
- `app/src/main/java/com/github/libretube/helpers/PreferenceHelper.kt:269-283` persists PIN hash/salt in `authSettings` (`auth.xml`).
- `app/src/main/java/com/github/libretube/ui/dialogs/ParentPinDialogs.kt:105-113` and `155-160` call `AccessControl.instance.checkPin(pin)` and `changePin(currentPin, newPin)`.
- Terminology (`GLOSSARY.md`): always use **Parent PIN** (never password/passcode).

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Fast gate | `./tools/quality/gate.sh fast` | exit 0 |
| AccessControl tests | `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.util.AccessControlTest"` | BUILD SUCCESSFUL, all tests pass |
| Full gate | `./tools/quality/gate.sh full` | exit 0 |

## Scope

**In scope** (the only files you should modify):
- `app/src/main/java/com/github/libretube/util/AccessControl.kt`
- `app/src/main/java/com/github/libretube/constants/PreferenceKeys.kt`
- `app/src/main/java/com/github/libretube/helpers/PreferenceHelper.kt`
- `app/src/main/java/com/github/libretube/ui/dialogs/ParentPinDialogs.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/com/github/libretube/util/AccessControlTest.kt`

**Out of scope** (do NOT touch):
- `app/src/main/res/values-*/strings.xml` (non-English translations are managed separately)
- `MainSettings.kt`, `SettingsActivity.kt`, `BackupRestoreSettings.kt`

## Git workflow

- Branch: `advisor/004-parent-pin-pbkdf2-and-backoff`
- Commit style: `fix(security): upgrade Parent PIN to PBKDF2-HMAC-SHA256 with retry backoff`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Add PBKDF2-HMAC-SHA256 hashing with transparent legacy SHA-256 migration

In `AccessControl.kt`:
- Define `PBKDF2_PREFIX = "pbkdf2_sha256$"`, `PBKDF2_ITERATIONS = 120_000`, `PBKDF2_KEY_BITS = 256`.
- Update `hashPin(pin: String, saltBytes: ByteArray): String` to derive a 256-bit key via `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")` using `PBEKeySpec(pin.toCharArray(), saltBytes, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)` (clearing `spec.clearPassword()` in `finally`) and return `"${PBKDF2_PREFIX}${PBKDF2_ITERATIONS}$${Base64.getEncoder().encodeToString(key)}"`.
- Keep `legacySha256Hash(pin, saltBytes)` private for verifying existing unprefixed hashes.
- When `verifyPin` / `checkPin` succeeds against a legacy SHA-256 hash, transparently re-hash with `setPin(normalized)` (fresh salt + PBKDF2) so existing users upgrade silently on next unlock.

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 2: Add persistent exponential backoff to `AccessControlStorage` and `AccessControl`

- Add `var failedPinAttempts: Int` (default `0`) and `var pinLockedUntilEpochMillis: Long` (default `0L`) to `AccessControlStorage` (with default properties on `InMemoryAccessControlStorage` and `authSettings` backing in `PreferenceHelper` + `PreferenceAccessControlStorage`, keys in `PreferenceKeys.kt`).
- Add `private val clockMillis: () -> Long = System::currentTimeMillis` as an optional constructor parameter on `AccessControl` (keeping existing 1-arg callers compiling).
- Add `sealed interface PinVerificationResult`: `data object Verified`, `data class Incorrect(val failedAttempts: Int)`, `data class LockedOut(val remainingMillis: Long)`.
- Implement `fun verifyPin(pin: String): PinVerificationResult`:
  - If `storage.pinLockedUntilEpochMillis > clockMillis()`, return `LockedOut(remainingMillis)` WITHOUT checking the PIN.
  - If PIN matches: reset `failedPinAttempts = 0`, `pinLockedUntilEpochMillis = 0L`, upgrade legacy hash if needed, return `Verified`.
  - If PIN does not match (and `pinHash != null`): increment `failedPinAttempts`; if `failedPinAttempts >= 5`, set `pinLockedUntilEpochMillis = clockMillis() + minOf(30_000L shl (failedAttempts - 5).coerceAtMost(5), 900_000L)` (30 s doubling up to 15 min), and return `LockedOut(remaining)` if locked or `Incorrect(failedAttempts)`.
- Also reset `failedPinAttempts = 0` and `pinLockedUntilEpochMillis = 0L` in `setPin()` when setting a new PIN. Keep `checkPin(pin): Boolean` returning `verifyPin(pin) == PinVerificationResult.Verified`.

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 3: Surface lockout feedback in `ParentPinDialogs.kt`

- Add `parent_pin_locked_out` string in `app/src/main/res/values/strings.xml` (e.g. `"Too many incorrect Parent PIN attempts. Try again in %d seconds."`).
- Update `showVerifyPinDialog` and `showChangePinDialog` in `ParentPinDialogs.kt` to call `AccessControl.instance.verifyPin(...)` (or check `PinVerificationResult.LockedOut`) and display the localized cooldown seconds on `pinLayout.error` / `currentLayout.error`.

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 4: Expand `AccessControlTest.kt` and run full gate

In `AccessControlTest.kt`:
1. Assert `setPin` produces a `"pbkdf2_sha256$120000$..."` hash and `checkPin` verifies it.
2. Seed `InMemoryAccessControlStorage` with a legacy SHA-256 hash + salt; assert `checkPin` returns `true` AND upgrades `storage.pinHash` to `"pbkdf2_sha256$..."`.
3. Using a fake `clockMillis`, assert 5 failed attempts trigger `LockedOut`, a correct PIN during cooldown is rejected as `LockedOut`, advancing the clock past cooldown allows `Verified`, and `Verified` resets `failedPinAttempts` to 0.

**Verify**: `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.util.AccessControlTest"` → all pass; `./tools/quality/gate.sh full` → exit 0.

## Test plan

- `app/src/test/java/com/github/libretube/util/AccessControlTest.kt` (extend existing tests).
- Verification: `./tools/quality/gate.sh full` → exit 0.

## Done criteria

- [ ] New Parent PINs are hashed with `PBKDF2WithHmacSHA256` (120,000 iterations).
- [ ] Legacy SHA-256 hashes verify and transparently upgrade to PBKDF2 on success.
- [ ] 5 consecutive wrong PINs trigger persistent exponential backoff (30 s up to 15 min) enforced even if the dialog is closed/reopened.
- [ ] `AccessControlTest` covers PBKDF2, legacy migration, and backoff; `./tools/quality/gate.sh full` exits 0.
- [ ] `plans/README.md` status row updated.

## STOP conditions

Stop and report back (do not improvise) if:
- `PBKDF2WithHmacSHA256` behaves differently on Robolectric/JDK vs Android API 26 (`minSdk = 26` supports `PBKDF2WithHmacSHA256` natively).
- `PreferenceAccessControlStorage` or `AccessControl.kt` lines have drifted from the excerpts.

## Maintenance notes

- Lockout state lives in `auth.xml` (`authSettings`), NOT default `SharedPreferences`, so clearing general preferences does not reset brute-force counters.
