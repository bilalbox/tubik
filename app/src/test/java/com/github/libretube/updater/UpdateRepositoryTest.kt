package com.github.libretube.updater

import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateRepositoryTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private class FakeUpdateStorage(
        override var autoUpdateCheckEnabled: Boolean = true,
        override var lastCheckTimestampMillis: Long = 0L,
        override var cachedManifestJson: String? = null,
        override var lastNotifiedVersionCode: Int = 0,
    ) : UpdateStorage

    @Test
    fun `parseManifest parses valid latest json and ignores unknown fields`() {
        val json =
            """
            {
              "schemaVersion": 1,
              "versionCode": 81,
              "versionName": "0.7.1",
              "minSdk": 26,
              "minSupportedVersionCode": 80,
              "publishedAt": "2026-11-15T00:00:00Z",
              "sha256": "${VALID_SHA256}",
              "sizeBytes": 48123456,
              "apkUrl": "https://tubik-releases.23p.dev/tubik-v0.7.1.apk",
              "releaseNotesUrl": "https://github.com/bilalbox/tubik/releases/tag/v0.7.1",
              "changelog": "Improved playback stability",
              "futureField": "ignored"
            }
            """
                .trimIndent()

        val manifest = UpdateManifest.parse(json)

        assertEquals(1, manifest.schemaVersion)
        assertEquals(81, manifest.versionCode)
        assertEquals("0.7.1", manifest.versionName)
        assertEquals(26, manifest.minSdk)
        assertEquals(80, manifest.minSupportedVersionCode)
        assertEquals("2026-11-15T00:00:00Z", manifest.publishedAt)
        assertEquals(VALID_SHA256, manifest.sha256)
        assertEquals(48123456L, manifest.sizeBytes)
        assertEquals("https://tubik-releases.23p.dev/tubik-v0.7.1.apk", manifest.apkUrl)
        assertEquals(
            "https://github.com/bilalbox/tubik/releases/tag/v0.7.1",
            manifest.releaseNotesUrl,
        )
        assertEquals("Improved playback stability", manifest.changelog)
    }

    @Test
    fun `evaluateManifest checks versionCode, minSdk, schemaVersion, https apkUrl, and sha256`() {
        val baseManifest =
            UpdateManifest(
                schemaVersion = 1,
                versionCode = 81,
                versionName = "0.7.1",
                minSdk = 26,
                minSupportedVersionCode = 80,
                publishedAt = "2026-11-15T00:00:00Z",
                sha256 = VALID_SHA256,
                sizeBytes = 48123456L,
                apkUrl = "https://tubik-releases.23p.dev/tubik-v0.7.1.apk",
                releaseNotesUrl = "https://github.com/bilalbox/tubik/releases/tag/v0.7.1",
                changelog = "Fixes",
            )

        assertEquals(
            UpdateCheckResult.UpdateAvailable(baseManifest),
            UpdateManifest.evaluate(baseManifest, currentVersionCode = 80, currentSdkInt = 26),
        )
        assertEquals(
            UpdateCheckResult.UpToDate(currentVersionCode = 81),
            UpdateManifest.evaluate(baseManifest, currentVersionCode = 81, currentSdkInt = 34),
        )
        assertEquals(
            UpdateCheckResult.UpToDate(currentVersionCode = 82),
            UpdateManifest.evaluate(baseManifest, currentVersionCode = 82, currentSdkInt = 34),
        )
        assertTrue(
            UpdateManifest.evaluate(
                baseManifest.copy(minSdk = 30),
                currentVersionCode = 80,
                currentSdkInt = 29,
            ) is UpdateCheckResult.IncompatibleSdk,
        )
        assertTrue(
            UpdateManifest.evaluate(
                baseManifest.copy(schemaVersion = 2),
                currentVersionCode = 80,
                currentSdkInt = 34,
            ) is UpdateCheckResult.InvalidManifest,
        )
        assertTrue(
            UpdateManifest.evaluate(
                baseManifest.copy(apkUrl = "http://tubik-releases.23p.dev/tubik-v0.7.1.apk"),
                currentVersionCode = 80,
                currentSdkInt = 34,
            ) is UpdateCheckResult.InvalidManifest,
        )
        assertTrue(
            UpdateManifest.evaluate(
                baseManifest.copy(sha256 = "not-a-valid-sha"),
                currentVersionCode = 80,
                currentSdkInt = 34,
            ) is UpdateCheckResult.InvalidManifest,
        )
    }

    @Test
    fun `UpdateHttpClient disables ssl downgrade redirects and omits body logging`() {
        val client = UpdateHttpClient.build()
        assertFalse(client.followSslRedirects)
        assertTrue(client.followRedirects)
        assertFalse(client.interceptors.any { it is HttpLoggingInterceptor })
    }

    @Test
    fun `checkForUpdate fetches https manifest, caches result and timestamp, and clears stale cache when upgraded`() {
        val storage = FakeUpdateStorage()
        val manifestJson =
            """
            {
              "schemaVersion": 1,
              "versionCode": 81,
              "versionName": "0.7.1",
              "minSdk": 26,
              "minSupportedVersionCode": 80,
              "publishedAt": "2026-11-15T00:00:00Z",
              "sha256": "$VALID_SHA256",
              "sizeBytes": 1234,
              "apkUrl": "https://tubik-releases.23p.dev/tubik-v0.7.1.apk",
              "releaseNotesUrl": "https://github.com/bilalbox/tubik/releases/tag/v0.7.1",
              "changelog": "Release notes"
            }
            """
                .trimIndent()

        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_2)
                        .code(200)
                        .message("OK")
                        .body(manifestJson.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build()

        val repo =
            UpdateRepository(
                storage = storage,
                httpClient = client,
                manifestUrl = UpdateRepository.DEFAULT_MANIFEST_URL,
                currentVersionCode = 80,
                currentSdkInt = 34,
                clockMillis = { 1_700_000_000_000L },
            )

        val result = repo.checkForUpdate().getOrThrow()
        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        assertEquals(1_700_000_000_000L, storage.lastCheckTimestampMillis)

        val cached = repo.getCachedState()
        assertEquals(1_700_000_000_000L, cached.lastCheckTimestampMillis)
        assertTrue(cached.result is UpdateCheckResult.UpdateAvailable)

        // Simulate app upgraded to versionCode 81: cached manifest no longer reports available
        // update
        val upgradedRepo =
            UpdateRepository(
                storage = storage,
                httpClient = client,
                manifestUrl = UpdateRepository.DEFAULT_MANIFEST_URL,
                currentVersionCode = 81,
                currentSdkInt = 34,
                clockMillis = { 1_700_000_000_000L },
            )
        val upgradedCached = upgradedRepo.getCachedState()
        assertEquals(UpdateCheckResult.UpToDate(81), upgradedCached.result)
    }

    @Test
    fun `checkForUpdate rejects non-https manifest url and preserves prior cache on http error`() {
        val storage = FakeUpdateStorage(lastCheckTimestampMillis = 555L, cachedManifestJson = null)
        val errorClient =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_2)
                        .code(503)
                        .message("Service Unavailable")
                        .body("unavailable".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
                .build()

        val insecureRepo =
            UpdateRepository(
                storage = storage,
                httpClient = errorClient,
                manifestUrl = "http://tubik.23p.dev/updates/latest.json",
                currentVersionCode = 80,
                currentSdkInt = 34,
            )
        assertTrue(insecureRepo.checkForUpdate().isFailure)
        assertEquals(555L, storage.lastCheckTimestampMillis)

        val failingRepo =
            UpdateRepository(
                storage = storage,
                httpClient = errorClient,
                manifestUrl = UpdateRepository.DEFAULT_MANIFEST_URL,
                currentVersionCode = 80,
                currentSdkInt = 34,
            )
        assertTrue(failingRepo.checkForUpdate().isFailure)
        assertEquals(555L, storage.lastCheckTimestampMillis)
        assertNull(storage.cachedManifestJson)
    }

    @Test
    fun `downloadApk cleans stale files and streams bytes with progress`() {
        val updatesDir = tempFolder.newFolder("updates")
        val staleApk = File(updatesDir, "tubik-old.apk").apply { writeText("stale") }
        val payload = "fake-apk-binary-payload".toByteArray()
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_2)
                        .code(200)
                        .message("OK")
                        .body(
                            payload.toResponseBody(
                                "application/vnd.android.package-archive".toMediaType(),
                            ),
                        )
                        .build()
                }
                .build()
        val repo = UpdateRepository(storage = FakeUpdateStorage(), httpClient = client)
        val manifest =
            UpdateManifest(
                schemaVersion = 1,
                versionCode = 81,
                versionName = "0.7.1",
                minSdk = 26,
                sha256 = VALID_SHA256,
                sizeBytes = payload.size.toLong(),
                apkUrl = "https://tubik-releases.23p.dev/tubik-v0.7.1.apk",
            )

        val progressEvents = mutableListOf<Pair<Long, Long>>()
        val downloaded =
            repo
                .downloadApk(manifest, updatesDir) { read, total ->
                    progressEvents.add(read to total)
                }
                .getOrThrow()

        assertFalse(staleApk.exists())
        assertTrue(downloaded.exists())
        assertEquals(payload.toList(), downloaded.readBytes().toList())
        assertTrue(progressEvents.isNotEmpty())
        assertEquals(payload.size.toLong(), progressEvents.last().first)
    }

    @Test
    fun `downloadApk deletes partial files on http error and rejects non-https apkUrl`() {
        val updatesDir = tempFolder.newFolder("updates-error")
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_2)
                        .code(500)
                        .message("Error")
                        .body("error".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
                .build()
        val repo = UpdateRepository(storage = FakeUpdateStorage(), httpClient = client)
        val manifest =
            UpdateManifest(
                schemaVersion = 1,
                versionCode = 81,
                versionName = "0.7.1",
                minSdk = 26,
                sha256 = VALID_SHA256,
                sizeBytes = 100L,
                apkUrl = "https://tubik-releases.23p.dev/tubik-v0.7.1.apk",
            )

        assertTrue(repo.downloadApk(manifest, updatesDir).isFailure)
        assertEquals(0, updatesDir.listFiles()?.size ?: 0)
        assertTrue(
            repo
                .downloadApk(
                    manifest.copy(apkUrl = "http://tubik-releases.23p.dev/tubik.apk"),
                    updatesDir,
                )
                .isFailure,
        )
    }

    companion object {
        private const val VALID_SHA256 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
