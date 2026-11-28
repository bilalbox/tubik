# Plan 021: Spike — On-device Music Removal benchmark & diagnostics harness

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f44e95979..HEAD -- app/src/main/java/com/github/libretube/player/MusicRemovalEngine.kt app/src/main/java/com/github/libretube/player/OrtSpeechRuntime.kt baselineprofile/build.gradle.kts`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: direction
- **Planned at**: commit `f44e95979`, 2026-11-28

## Why this matters

`docs/adr/0002-mute-not-music-speech-only-removal.md:15` explicitly records an open validation gap:
"The model sits behind one seam, so it can be swapped (FastEnhancer-S, DPDFNet 16 kHz/48 kHz) once it has been measured on real low-end hardware; this hasn't been tested on an A56 yet."
Building a deterministic 10-second 16 kHz benchmark runner over `MusicRemovalEngine` (with a JVM smoke test and an Android instrumentation/adb-runnable harness) lets the maintainer measure real-device p50/p95 hop latency, sustained realtime multiplier, and **Overload Mute** behavior reproducibly, and informs whether to expose a parent diagnostics check in Settings.

## Current state

- `app/src/main/java/com/github/libretube/player/MusicRemovalEngine.kt:42-74`: accepts `modelProvider`, `fallbackModelProvider`, `gateProvider`, `playbackSpeedProvider`, and `MusicRemovalConfig(nanoTimeProvider = ...)`; exposes `isMutedForOverload`, `isUsingFallbackModel`, `sampleRate`, `hopSize`.
- `app/src/main/assets/` bundles `fastenhancer_s_16k.onnx` (813 KB), `fastenhancer_t_16k.onnx` (122 KB), and `silero_vad.onnx` (1.3 MB).
- `app/build.gradle.kts` includes `testImplementation(libs.onnxruntime.jvm)` and `implementation(libs.onnxruntime.android)`.
- ADR-0002 constraints: 16 kHz mono, 512-sample hop (32 ms), 1.5× max playback speed cap, fail-closed **Overload Mute**.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Fast gate | `./tools/quality/gate.sh fast` | exit 0 |
| Benchmark JVM test | `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.player.MusicRemovalBenchmarkTest"` | BUILD SUCCESSFUL, all tests pass |
| Full gate | `./tools/quality/gate.sh full` | exit 0 |

## Scope

**In scope** (the only files you should modify/create):
- `app/src/main/java/com/github/libretube/player/MusicRemovalBenchmark.kt` (create — pure benchmark runner + synthetic 16 kHz speech+noise generator)
- `app/src/test/java/com/github/libretube/player/MusicRemovalBenchmarkTest.kt` (create — JVM unit/smoke test)
- `app/src/androidTest/java/com/github/libretube/player/MusicRemovalDeviceBenchmarkTest.kt` (create — adb-runnable on-device instrumentation test loading real ONNX assets)
- `docs/design/music-removal-benchmark.md` (create — benchmark methodology, adb run command, and Settings diagnostics trade-off memo)

**Out of scope** (do NOT touch):
- `MusicRemovalEngine.kt`, `OrtSpeechRuntime.kt`, `MainSettings.kt`, `settings.xml` (do NOT add a Settings UI entry until the maintainer reviews the memo)

## Git workflow

- Branch: `advisor/021-spike-on-device-music-removal-benchmark`
- Commit style: `feat(player): add deterministic Music Removal benchmark harness and design memo`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Implement `MusicRemovalBenchmark.kt`

Create `app/src/main/java/com/github/libretube/player/MusicRemovalBenchmark.kt`:
- `fun generateSyntheticSpeechNoisePcm(durationSeconds: Int = 10, sampleRate: Int = 16_000): FloatArray`: deterministic PRNG + modulated vowel-formant sine waves + low-amplitude broadband noise (zero external audio files needed).
- `data class BenchmarkReport(val modelName: String, val warmupMs: Double, val hopCount: Int, val p50HopMs: Double, val p95HopMs: Double, val p99HopMs: Double, val sustainedRealtimeMultiplier: Double, val safeMaxSpeed: Float, val triggeredOverloadMute: Boolean)`
- `fun runBenchmark(modelName: String, engineFactory: () -> MusicRemovalEngine, audioSamples: FloatArray = generateSyntheticSpeechNoisePcm(), playbackSpeed: Float = 1.5f): BenchmarkReport`:
  - Measures warmup + first hop, runs all 512-sample hops, records per-hop `System.nanoTime()` durations, computes p50/p95/p99, `sustainedRealtimeMultiplier = 32.0 / meanHopMs`, `safeMaxSpeed = minOf(1.5f, (sustainedRealtimeMultiplier * 0.85).toFloat())`, and closes the engine in `use { ... }`.

**Verify**: `./tools/quality/gate.sh fast` → exit 0.

### Step 2: Add JVM smoke test and Android instrumentation harness

1. `MusicRemovalBenchmarkTest.kt`: run `MusicRemovalBenchmark.runBenchmark` with fake/scripted `SpeechModel` & `SpeechGate` (matching `MusicRemovalEngineTest.kt`) and assert `hopCount == (10 * 16_000) / 512`, percentiles are ordered `p50 <= p95 <= p99`, and `safeMaxSpeed <= 1.5f`.
2. `MusicRemovalDeviceBenchmarkTest.kt` (under `app/src/androidTest/java/...`): loads real `OrtSpeechRuntime.fromAssets(ApplicationProvider.getApplicationContext())` for both Primary (`fastenhancer_s_16k`) and Fallback (`fastenhancer_t_16k`) models, runs `MusicRemovalBenchmark.runBenchmark`, and logs the formatted `BenchmarkReport` via `Log.i("MusicRemovalBench", ...)`.

**Verify**: `./gradlew :app:testDebugUnitTest --tests "com.github.libretube.player.MusicRemovalBenchmarkTest"` → all pass.

### Step 3: Write `docs/design/music-removal-benchmark.md` and run full gate

Document:
1. Exact `adb` / Gradle command to run `MusicRemovalDeviceBenchmarkTest` on a connected Galaxy A56 (`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.github.libretube.player.MusicRemovalDeviceBenchmarkTest`).
2. Target pass thresholds for ADR-0002 (sustained >= 1.5× realtime without **Overload Mute**).
3. Trade-off analysis: developer-only instrumentation harness vs exposing a 5-second "Test Music Removal speed" action in Settings.

**Verify**: `./tools/quality/gate.sh full` → exit 0.

## Test plan

- `MusicRemovalBenchmarkTest.kt` (JVM) + `MusicRemovalDeviceBenchmarkTest.kt` (androidTest, compiled/checked; executed manually on physical device).
- Verification: `./tools/quality/gate.sh full` → exit 0.

## Done criteria

- [ ] `MusicRemovalBenchmark.kt`, `MusicRemovalBenchmarkTest.kt`, `MusicRemovalDeviceBenchmarkTest.kt`, and `docs/design/music-removal-benchmark.md` exist.
- [ ] `MusicRemovalBenchmarkTest` passes.
- [ ] `./tools/quality/gate.sh full` exits 0.
- [ ] `plans/README.md` status row updated.

## STOP conditions

Stop and report back (do not improvise) if:
- `app/src/androidTest/` does not compile under `gate.sh fast` / `full` because `androidTestImplementation` lacks JUnit/TestRunner dependencies (stop and report instead of adding unrequested dependencies).

## Maintenance notes

- When evaluating DPDFNet or new FastEnhancer checkpoints, plug them into `MusicRemovalDeviceBenchmarkTest` to compare against the Galaxy A56 baseline before swapping defaults.
