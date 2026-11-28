package com.github.libretube.updater

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    private class InMemoryUpdateStorage(
        override var autoUpdateCheckEnabled: Boolean = true,
        override var lastCheckTimestampMillis: Long = 0L,
        override var cachedManifestJson: String? = null,
        override var lastNotifiedVersionCode: Int = 0,
    ) : UpdateStorage

    private class FakeUpdatePlatform(
        override val updatesDir: File,
        override val packageName: String = "dev.twenty3p.tubik",
        var screenPinned: Boolean = false,
        var canRequestInstalls: Boolean = true,
        var apkInfo: ApkVerificationInfo? =
            ApkVerificationInfo("dev.twenty3p.tubik", setOf(VALID_CERT), versionCode = 90L),
        var installedSigners: Set<String>? = setOf(VALID_CERT),
        var commitResult: Result<Unit> = Result.success(Unit),
        var onReadApkInfo: (() -> Unit)? = null,
    ) : UpdatePlatform {
        var scheduledCalls = 0
        var lastIntervalHours: Long? = null
        var lastReplaceExisting: Boolean? = null
        var cancelledCalls = 0
        val notifiedManifests = mutableListOf<UpdateManifest>()
        val committedFiles = mutableListOf<File>()

        override fun isScreenPinned(): Boolean = screenPinned

        override fun canRequestPackageInstalls(): Boolean = canRequestInstalls

        override fun readApkVerificationInfo(apkFile: File): ApkVerificationInfo? {
            onReadApkInfo?.invoke()
            return apkInfo
        }

        override fun readInstalledSigners(packageName: String): Set<String>? = installedSigners

        override fun commitInstall(apkFile: File): Result<Unit> {
            committedFiles.add(apkFile)
            return commitResult
        }

        override fun schedulePeriodicCheck(intervalHours: Long, replaceExisting: Boolean) {
            scheduledCalls++
            lastIntervalHours = intervalHours
            lastReplaceExisting = replaceExisting
        }

        override fun cancelPeriodicCheck() {
            cancelledCalls++
        }

        override fun notifyUpdateAvailable(manifest: UpdateManifest) {
            notifiedManifests.add(manifest)
        }
    }

    private fun tempUpdatesDir(): File =
        Files.createTempDirectory("app-updater-test-").toFile().apply { deleteOnExit() }

    private fun stubHttpClient(handler: (Request) -> Response): OkHttpClient =
        AppUpdater.buildHttpClient()
            .newBuilder()
            .addInterceptor(Interceptor { chain -> handler(chain.request()) })
            .build()

    private fun jsonResponse(request: Request, body: String, code: Int = 200): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private fun bytesResponse(request: Request, bytes: ByteArray, code: Int = 200): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(bytes.toResponseBody("application/vnd.android.package-archive".toMediaType()))
            .build()

    private fun sampleManifest(
        versionCode: Int = 90,
        versionName: String = "1.3.0",
        minSdk: Int = 26,
        sha256: String = VALID_SHA256,
        sizeBytes: Long = 1024L,
        apkUrl: String = "https://tubik-releases.23p.dev/tubik-v1.3.0.apk",
        releaseNotesUrl: String = "https://github.com/bilalbox/tubik/releases/tag/v$versionName",
    ): UpdateManifest =
        UpdateManifest(
            schemaVersion = 1,
            versionCode = versionCode,
            versionName = versionName,
            minSdk = minSdk,
            sha256 = sha256,
            sizeBytes = sizeBytes,
            apkUrl = apkUrl,
            releaseNotesUrl = releaseNotesUrl,
            changelog = "Bug fixes",
        )

    @Test
    fun `checkForUpdate evaluates eligibility, caches available manifests, and clears stale cache after upgrade`() {
        val manifest = sampleManifest(versionCode = 90, versionName = "1.3.0")
        val storage = InMemoryUpdateStorage()
        val platform = FakeUpdatePlatform(tempUpdatesDir())
        val client = stubHttpClient { req -> jsonResponse(req, UpdateManifest.encode(manifest)) }

        val updater =
            AppUpdater(
                platform = platform,
                storage = storage,
                httpClient = client,
                currentVersionCode = 86,
                currentSdkInt = 33,
                clockMillis = { 123456L },
            )

        val result = updater.checkForUpdate().getOrThrow()
        assertEquals(UpdateCheckResult.UpdateAvailable(manifest), result)
        assertEquals(123456L, updater.cachedState().lastCheckTimestampMillis)
        assertEquals(UpdateCheckResult.UpdateAvailable(manifest), updater.cachedState().result)

        // After app upgrades to version 90, cachedState normalizes to UpToDate and clears stale
        // JSON
        val upgradedUpdater =
            AppUpdater(
                platform = platform,
                storage = storage,
                httpClient = client,
                currentVersionCode = 90,
                currentSdkInt = 33,
            )
        assertEquals(UpdateCheckResult.UpToDate(90), upgradedUpdater.cachedState().result)
        assertNull(storage.cachedManifestJson)
    }

    @Test
    fun `checkForUpdate rejects non-HTTPS URLs, HTTP errors, and incompatible minSdk`() {
        val platform = FakeUpdatePlatform(tempUpdatesDir())
        val httpManifestUpdater =
            AppUpdater(
                platform = platform,
                storage = InMemoryUpdateStorage(),
                httpClient = stubHttpClient { error("Should not hit network") },
                manifestUrl = "http://tubik.23p.dev/updates/latest.json",
                currentVersionCode = 86,
                currentSdkInt = 33,
            )
        assertTrue(httpManifestUpdater.checkForUpdate().isFailure)

        val futureSdkManifest = sampleManifest(versionCode = 95, minSdk = 35)
        val sdkUpdater =
            AppUpdater(
                platform = platform,
                storage = InMemoryUpdateStorage(),
                httpClient =
                    stubHttpClient { req ->
                        jsonResponse(req, UpdateManifest.encode(futureSdkManifest))
                    },
                currentVersionCode = 86,
                currentSdkInt = 33,
            )
        assertEquals(
            UpdateCheckResult.IncompatibleSdk(futureSdkManifest, 33),
            sdkUpdater.checkForUpdate().getOrThrow(),
        )
    }

    @Test
    fun `schedule sync and runBackgroundCheck deduplicate notifications per versionCode`() {
        val manifest = sampleManifest(versionCode = 91)
        val storage = InMemoryUpdateStorage(autoUpdateCheckEnabled = true)
        val platform = FakeUpdatePlatform(tempUpdatesDir())
        val updater =
            AppUpdater(
                platform = platform,
                storage = storage,
                httpClient =
                    stubHttpClient { req -> jsonResponse(req, UpdateManifest.encode(manifest)) },
                currentVersionCode = 86,
                currentSdkInt = 33,
            )

        updater.syncSchedule(replaceExisting = false)
        assertEquals(1, platform.scheduledCalls)
        assertEquals(AppUpdater.CHECK_INTERVAL_HOURS, platform.lastIntervalHours)
        assertEquals(false, platform.lastReplaceExisting)

        assertEquals(BackgroundCheckOutcome.UpdateNotified(manifest), updater.runBackgroundCheck())
        assertEquals(1, platform.notifiedManifests.size)

        // Second check for same versionCode deduplicates notification
        assertEquals(BackgroundCheckOutcome.AlreadyNotified(manifest), updater.runBackgroundCheck())
        assertEquals(1, platform.notifiedManifests.size)

        // Turning off auto-update cancels periodic work and short-circuits background check
        updater.setAutoUpdateEnabled(false)
        assertEquals(1, platform.cancelledCalls)
        assertEquals(BackgroundCheckOutcome.Disabled, updater.runBackgroundCheck())
    }

    @Test
    fun `downloadAndInstall blocks before network when Screen Pin is active or permission missing`() {
        val dir = tempUpdatesDir()
        var networkCalls = 0
        val client = stubHttpClient {
            networkCalls++
            error("Network should not be called when blocked")
        }

        val pinnedPlatform = FakeUpdatePlatform(dir, screenPinned = true)
        val pinnedUpdater =
            AppUpdater(pinnedPlatform, InMemoryUpdateStorage(), client, currentVersionCode = 86)
        assertEquals(
            DownloadAndInstallOutcome.BlockedByScreenPin,
            pinnedUpdater.downloadAndInstall(sampleManifest()),
        )
        assertFalse(pinnedUpdater.canOpenExternalLink())

        val noPermPlatform =
            FakeUpdatePlatform(dir, screenPinned = false, canRequestInstalls = false)
        val noPermUpdater =
            AppUpdater(noPermPlatform, InMemoryUpdateStorage(), client, currentVersionCode = 86)
        assertEquals(
            DownloadAndInstallOutcome.NeedsUnknownSourcesPermission,
            noPermUpdater.downloadAndInstall(sampleManifest()),
        )
        assertEquals(0, networkCalls)
    }

    @Test
    fun `downloadAndInstall downloads, verifies SHA-256 and signatures, and commits install`() {
        val dir = tempUpdatesDir()
        val payload = "signed-tubik-release-apk-bytes".toByteArray()
        val tempRef = File(dir, "ref.tmp").apply { writeBytes(payload) }
        val expectedSha = AppUpdater.computeSha256(tempRef)
        tempRef.delete()

        val manifest = sampleManifest(sha256 = expectedSha, sizeBytes = payload.size.toLong())
        val platform = FakeUpdatePlatform(dir)
        val updater =
            AppUpdater(
                platform = platform,
                storage = InMemoryUpdateStorage(),
                httpClient = stubHttpClient { req -> bytesResponse(req, payload) },
                currentVersionCode = 86,
            )

        val stages = mutableListOf<DownloadAndInstallStage>()
        val outcome = updater.downloadAndInstall(manifest) { stages.add(it) }

        assertEquals(DownloadAndInstallOutcome.InstallCommitted, outcome)
        assertEquals(1, platform.committedFiles.size)
        assertTrue(platform.committedFiles.single().isFile)
        assertTrue(stages.any { it is DownloadAndInstallStage.Downloading })
        assertTrue(stages.contains(DownloadAndInstallStage.Verifying))
        assertTrue(stages.contains(DownloadAndInstallStage.Installing))
    }

    private fun computeSha256(dir: File, bytes: ByteArray): String {
        val refFile = File(dir, "hash.tmp").apply { writeBytes(bytes) }
        return AppUpdater.computeSha256(refFile).also { refFile.delete() }
    }

    @Test
    fun `downloadAndInstall deletes staged APK on SHA-256 and signature failures`() {
        val payload = "tampered-apk-payload".toByteArray()
        val tempDir = tempUpdatesDir()
        val realSha = computeSha256(tempDir, payload)
        val client = stubHttpClient { req -> bytesResponse(req, payload) }

        val badShaOutcome =
            AppUpdater(
                    FakeUpdatePlatform(tempDir),
                    InMemoryUpdateStorage(),
                    client,
                    currentVersionCode = 86,
                )
                .downloadAndInstall(
                    sampleManifest(sha256 = VALID_SHA256, sizeBytes = payload.size.toLong()),
                )
        assertTrue(badShaOutcome is DownloadAndInstallOutcome.VerificationFailed)
        assertTrue(tempDir.listFiles().orEmpty().isEmpty())

        val badCertPlatform =
            FakeUpdatePlatform(
                updatesDir = tempDir,
                apkInfo =
                    ApkVerificationInfo(
                        "dev.twenty3p.tubik",
                        setOf("deadbeef".repeat(8)),
                        versionCode = 90L,
                    ),
            )
        val badCertOutcome =
            AppUpdater(badCertPlatform, InMemoryUpdateStorage(), client, currentVersionCode = 86)
                .downloadAndInstall(
                    sampleManifest(sha256 = realSha, sizeBytes = payload.size.toLong()),
                )
        assertEquals(
            DownloadAndInstallOutcome.VerificationFailed(VerificationResult.SignatureMismatch),
            badCertOutcome,
        )
        assertTrue(tempDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `downloadAndInstall deletes staged APK on mid-download Screen Pin and commit failure`() {
        val payload = "tampered-apk-payload".toByteArray()
        val tempDir = tempUpdatesDir()
        val realSha = computeSha256(tempDir, payload)
        val client = stubHttpClient { req -> bytesResponse(req, payload) }

        val midPinPlatform = FakeUpdatePlatform(updatesDir = tempDir)
        midPinPlatform.onReadApkInfo = { midPinPlatform.screenPinned = true }
        val midPinOutcome =
            AppUpdater(midPinPlatform, InMemoryUpdateStorage(), client, currentVersionCode = 86)
                .downloadAndInstall(
                    sampleManifest(sha256 = realSha, sizeBytes = payload.size.toLong()),
                )
        assertEquals(DownloadAndInstallOutcome.BlockedByScreenPin, midPinOutcome)
        assertTrue(midPinPlatform.committedFiles.isEmpty())
        assertTrue(tempDir.listFiles().orEmpty().isEmpty())

        val failCommitPlatform =
            FakeUpdatePlatform(
                updatesDir = tempDir,
                commitResult = Result.failure(IOException("Session failed")),
            )
        val failCommitOutcome =
            AppUpdater(failCommitPlatform, InMemoryUpdateStorage(), client, currentVersionCode = 86)
                .downloadAndInstall(
                    sampleManifest(sha256 = realSha, sizeBytes = payload.size.toLong()),
                )
        assertTrue(failCommitOutcome is DownloadAndInstallOutcome.InstallFailed)
        assertTrue(tempDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `onInstallCallback cleans staged APKs and emits install status on installEvents`() =
        runBlocking {
            val dir = tempUpdatesDir()
            val stagedApk = File(dir, "tubik-v1.3.0.apk").apply { writeText("apk") }
            val extraFile = File(dir, "stale.apk").apply { writeText("old") }
            val updater =
                AppUpdater(
                    platform = FakeUpdatePlatform(dir),
                    storage = InMemoryUpdateStorage(),
                    httpClient = stubHttpClient { error("Unused") },
                )

            var received: UpdateInstallStatus? = null
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    received = updater.installEvents.first()
                }
            updater.onInstallCallback(UpdateInstallStatus.Succeeded, stagedApk.absolutePath)
            job.join()

            assertEquals(UpdateInstallStatus.Succeeded, received)
            assertFalse(stagedApk.exists())
            assertFalse(extraFile.exists())
        }

    @Test
    fun `evaluate rejects disallowed apkUrl host, disallowed releaseNotesUrl, and oversized sizeBytes`() {
        val badHostManifest = sampleManifest(apkUrl = "https://evil.example/tubik-v1.3.0.apk")
        assertTrue(
            UpdateManifest.evaluate(badHostManifest, currentVersionCode = 86, currentSdkInt = 33)
                is UpdateCheckResult.InvalidManifest,
        )

        val badNotesManifest =
            sampleManifest(releaseNotesUrl = "https://evil.example/bilalbox/tubik/releases")
        assertTrue(
            UpdateManifest.evaluate(badNotesManifest, currentVersionCode = 86, currentSdkInt = 33)
                is UpdateCheckResult.InvalidManifest,
        )

        val oversizedManifest = sampleManifest(sizeBytes = UpdateManifest.MAX_APK_SIZE_BYTES + 1L)
        assertTrue(
            UpdateManifest.evaluate(oversizedManifest, currentVersionCode = 86, currentSdkInt = 33)
                is UpdateCheckResult.InvalidManifest,
        )
    }

    @Test
    fun `downloadAndInstall rejects redirects to disallowed APK hosts`() {
        val dir = tempUpdatesDir()
        val payload = "signed-tubik-release-apk-bytes".toByteArray()
        val redirectedRequest = Request.Builder().url("https://evil.example/tubik.apk").build()
        val client = stubHttpClient {
            Response.Builder()
                .request(redirectedRequest)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    payload.toResponseBody("application/vnd.android.package-archive".toMediaType()),
                )
                .build()
        }

        val updater =
            AppUpdater(
                platform = FakeUpdatePlatform(dir),
                storage = InMemoryUpdateStorage(),
                httpClient = client,
                currentVersionCode = 86,
            )
        val outcome = updater.downloadAndInstall(sampleManifest(sizeBytes = payload.size.toLong()))

        assertTrue(outcome is DownloadAndInstallOutcome.DownloadFailed)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `isAllowedReleaseNotesUrl enforces github repository releases prefix`() {
        assertTrue(UpdateManifest.isAllowedReleaseNotesUrl(""))
        assertTrue(
            UpdateManifest.isAllowedReleaseNotesUrl(
                "https://github.com/bilalbox/tubik/releases/tag/v1.3.1",
            ),
        )
        assertFalse(
            UpdateManifest.isAllowedReleaseNotesUrl(
                "http://github.com/bilalbox/tubik/releases/tag/v1.3.1",
            ),
        )
        assertFalse(
            UpdateManifest.isAllowedReleaseNotesUrl("https://github.com/other/repo/releases"),
        )
        assertFalse(
            UpdateManifest.isAllowedReleaseNotesUrl(
                "https://evil.example/bilalbox/tubik/releases/tag/v1.3.1",
            ),
        )
    }

    @Test
    fun `downloadAndInstall rejects APK with lower or mismatched versionCode`() {
        val dir = tempUpdatesDir()
        val payload = "signed-tubik-release-apk-bytes".toByteArray()
        val refFile = File(dir, "ref.tmp").apply { writeBytes(payload) }
        val realSha = AppUpdater.computeSha256(refFile)
        refFile.delete()
        val client = stubHttpClient { req -> bytesResponse(req, payload) }
        val manifest =
            sampleManifest(
                versionCode = 90,
                sha256 = realSha,
                sizeBytes = payload.size.toLong(),
            )

        // 1. APK versionCode <= installed currentVersionCode (downgrade / replay)
        val downgradePlatform =
            FakeUpdatePlatform(
                updatesDir = dir,
                apkInfo =
                    ApkVerificationInfo("dev.twenty3p.tubik", setOf(VALID_CERT), versionCode = 85L),
            )
        val downgradeOutcome =
            AppUpdater(
                    downgradePlatform,
                    InMemoryUpdateStorage(),
                    client,
                    currentVersionCode = 86,
                )
                .downloadAndInstall(manifest)
        assertEquals(
            DownloadAndInstallOutcome.VerificationFailed(
                VerificationResult.VersionMismatch(expected = 90L, actual = 85L),
            ),
            downgradeOutcome,
        )
        assertTrue(dir.listFiles().orEmpty().isEmpty())

        // 2. APK versionCode > currentVersionCode but != manifest.versionCode
        val mismatchPlatform =
            FakeUpdatePlatform(
                updatesDir = dir,
                apkInfo =
                    ApkVerificationInfo("dev.twenty3p.tubik", setOf(VALID_CERT), versionCode = 88L),
            )
        val mismatchOutcome =
            AppUpdater(
                    mismatchPlatform,
                    InMemoryUpdateStorage(),
                    client,
                    currentVersionCode = 86,
                )
                .downloadAndInstall(manifest)
        assertEquals(
            DownloadAndInstallOutcome.VerificationFailed(
                VerificationResult.VersionMismatch(expected = 90L, actual = 88L),
            ),
            mismatchOutcome,
        )
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `downloadAndInstall aborts when response body exceeds or is truncated below manifest sizeBytes`() {
        val dir = tempUpdatesDir()
        val bodyBytes = "0123456789abcdef".toByteArray() // 16 bytes
        val client = stubHttpClient { req -> bytesResponse(req, bodyBytes) }
        val updater =
            AppUpdater(
                platform = FakeUpdatePlatform(dir),
                storage = InMemoryUpdateStorage(),
                httpClient = client,
                currentVersionCode = 86,
            )

        val oversizedOutcome = updater.downloadAndInstall(sampleManifest(sizeBytes = 8L))
        assertTrue(oversizedOutcome is DownloadAndInstallOutcome.DownloadFailed)
        assertTrue(dir.listFiles().orEmpty().isEmpty())

        val truncatedOutcome = updater.downloadAndInstall(sampleManifest(sizeBytes = 32L))
        assertTrue(truncatedOutcome is DownloadAndInstallOutcome.DownloadFailed)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    companion object {
        private val VALID_SHA256 = "a".repeat(64)
        private val VALID_CERT = "b".repeat(64)
    }
}
