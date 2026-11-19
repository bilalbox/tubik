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
            ApkVerificationInfo("dev.twenty3p.tubik", setOf(VALID_CERT)),
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
        apkUrl: String = "https://tubik.23p.dev/updates/tubik-v1.3.0.apk",
    ): UpdateManifest =
        UpdateManifest(
            schemaVersion = 1,
            versionCode = versionCode,
            versionName = versionName,
            minSdk = minSdk,
            sha256 = sha256,
            sizeBytes = sizeBytes,
            apkUrl = apkUrl,
            releaseNotesUrl = "https://github.com/bilalbox/tubik/releases/tag/v$versionName",
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
        val pinnedUpdater = AppUpdater(pinnedPlatform, InMemoryUpdateStorage(), client)
        assertEquals(
            DownloadAndInstallOutcome.BlockedByScreenPin,
            pinnedUpdater.downloadAndInstall(sampleManifest()),
        )
        assertFalse(pinnedUpdater.canOpenExternalLink())

        val noPermPlatform =
            FakeUpdatePlatform(dir, screenPinned = false, canRequestInstalls = false)
        val noPermUpdater = AppUpdater(noPermPlatform, InMemoryUpdateStorage(), client)
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

    @Test
    fun `downloadAndInstall deletes staged APK on verification, Screen Pin, and commit failures`() {
        val payload = "tampered-apk-payload".toByteArray()
        val tempDir = tempUpdatesDir()
        val refFile = File(tempDir, "hash.tmp").apply { writeBytes(payload) }
        val realSha = AppUpdater.computeSha256(refFile)
        refFile.delete()
        val client = stubHttpClient { req -> bytesResponse(req, payload) }

        // 1. SHA-256 mismatch deletes staged APK
        val badShaOutcome =
            AppUpdater(FakeUpdatePlatform(tempDir), InMemoryUpdateStorage(), client)
                .downloadAndInstall(sampleManifest(sha256 = VALID_SHA256))
        assertTrue(badShaOutcome is DownloadAndInstallOutcome.VerificationFailed)
        assertTrue(tempDir.listFiles().orEmpty().isEmpty())

        // 2. Signing-certificate mismatch deletes staged APK
        val badCertPlatform =
            FakeUpdatePlatform(
                updatesDir = tempDir,
                apkInfo = ApkVerificationInfo("dev.twenty3p.tubik", setOf("deadbeef".repeat(8))),
            )
        val badCertOutcome =
            AppUpdater(badCertPlatform, InMemoryUpdateStorage(), client)
                .downloadAndInstall(sampleManifest(sha256 = realSha))
        assertEquals(
            DownloadAndInstallOutcome.VerificationFailed(VerificationResult.SignatureMismatch),
            badCertOutcome,
        )
        assertTrue(tempDir.listFiles().orEmpty().isEmpty())

        // 3. Screen Pin activated mid-download deletes staged APK and never commits
        val midPinPlatform = FakeUpdatePlatform(updatesDir = tempDir)
        midPinPlatform.onReadApkInfo = { midPinPlatform.screenPinned = true }
        val midPinOutcome =
            AppUpdater(midPinPlatform, InMemoryUpdateStorage(), client)
                .downloadAndInstall(sampleManifest(sha256 = realSha))
        assertEquals(DownloadAndInstallOutcome.BlockedByScreenPin, midPinOutcome)
        assertTrue(midPinPlatform.committedFiles.isEmpty())
        assertTrue(tempDir.listFiles().orEmpty().isEmpty())

        // 4. PackageInstaller commit failure deletes staged APK
        val failCommitPlatform =
            FakeUpdatePlatform(
                updatesDir = tempDir,
                commitResult = Result.failure(IOException("Session failed")),
            )
        val failCommitOutcome =
            AppUpdater(failCommitPlatform, InMemoryUpdateStorage(), client)
                .downloadAndInstall(sampleManifest(sha256 = realSha))
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

    companion object {
        private val VALID_SHA256 = "a".repeat(64)
        private val VALID_CERT = "b".repeat(64)
    }
}
