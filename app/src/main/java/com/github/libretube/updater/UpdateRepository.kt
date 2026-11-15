package com.github.libretube.updater

import android.os.Build
import com.github.libretube.BuildConfig
import java.io.File
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Cached update check status and timestamp for immediate Settings display. */
data class CachedUpdateState(
    val lastCheckTimestampMillis: Long,
    val result: UpdateCheckResult?,
)

/** Fetches, evaluates, caches, and downloads Tubik OTA updates over HTTPS. */
class UpdateRepository(
    private val storage: UpdateStorage = PreferenceUpdateStorage,
    private val httpClient: OkHttpClient = UpdateHttpClient.shared,
    private val manifestUrl: String = DEFAULT_MANIFEST_URL,
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    private val currentSdkInt: Int = Build.VERSION.SDK_INT,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    /** Fetches `latest.json` over HTTPS, evaluates eligibility, and updates cached status. */
    fun checkForUpdate(): Result<UpdateCheckResult> = runCatching {
        val url = manifestUrl.trim().toHttpUrlOrNull()
        require(url != null && url.isHttps) { "Manifest URL must use HTTPS: $manifestUrl" }

        val request = Request.Builder().url(url).get().build()
        val bodyText =
            httpClient.newCall(request).execute().use { response ->
                if (!response.request.url.isHttps) {
                    throw IOException("Refused redirect to non-HTTPS URL: ${response.request.url}")
                }
                if (!response.isSuccessful) {
                    throw IOException("Manifest request failed with HTTP ${response.code}")
                }
                response.body.string()
            }

        val manifest = UpdateManifest.parse(bodyText).normalized()
        val evaluation = UpdateManifest.evaluate(manifest, currentVersionCode, currentSdkInt)

        storage.lastCheckTimestampMillis = clockMillis()
        when (evaluation) {
            is UpdateCheckResult.UpdateAvailable,
            is UpdateCheckResult.IncompatibleSdk -> {
                storage.cachedManifestJson = UpdateManifest.encode(manifest)
            }
            is UpdateCheckResult.UpToDate,
            is UpdateCheckResult.InvalidManifest -> {
                storage.cachedManifestJson = null
            }
        }
        evaluation
    }

    /** Returns the cached update check status, normalizing away outdated caches after upgrade. */
    fun getCachedState(): CachedUpdateState {
        val timestamp = storage.lastCheckTimestampMillis
        val rawJson = storage.cachedManifestJson
        val manifest =
            rawJson
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { UpdateManifest.parse(it).normalized() }.getOrNull() }

        val result =
            if (manifest != null) {
                val evaluated = UpdateManifest.evaluate(manifest, currentVersionCode, currentSdkInt)
                if (
                    evaluated is UpdateCheckResult.UpToDate ||
                        evaluated is UpdateCheckResult.InvalidManifest
                ) {
                    storage.cachedManifestJson = null
                }
                evaluated
            } else {
                if (!rawJson.isNullOrBlank()) {
                    storage.cachedManifestJson = null
                }
                if (timestamp > 0L) UpdateCheckResult.UpToDate(currentVersionCode) else null
            }

        return CachedUpdateState(timestamp, result)
    }

    /**
     * Downloads the release APK referenced by [manifest] into [targetDir], cleaning stale update
     * files beforehand and removing partial artifacts on failure.
     */
    fun downloadApk(
        manifest: UpdateManifest,
        targetDir: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<File> = runCatching {
        val url = manifest.apkUrl.trim().toHttpUrlOrNull()
        require(url != null && url.isHttps) { "APK URL must use HTTPS: ${manifest.apkUrl}" }

        cleanDirectory(targetDir)
        check(targetDir.exists() || targetDir.mkdirs()) {
            "Unable to create update directory: ${targetDir.absolutePath}"
        }

        val safeVersion = manifest.versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val partFile = File(targetDir, "tubik-v$safeVersion.apk.part")
        val finalFile = File(targetDir, "tubik-v$safeVersion.apk")
        var completed = false
        try {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                validateHttpsSuccessResponse(response)
                streamResponseBody(response.body, partFile, manifest.sizeBytes, onProgress)
            }
            check(partFile.renameTo(finalFile)) { "Failed to finalize downloaded APK" }
            completed = true
            finalFile
        } finally {
            if (!completed) {
                partFile.delete()
                finalFile.delete()
            }
        }
    }

    private fun validateHttpsSuccessResponse(response: okhttp3.Response) {
        if (!response.request.url.isHttps) {
            throw IOException("Refused redirect to non-HTTPS URL: ${response.request.url}")
        }
        if (!response.isSuccessful) {
            throw IOException("APK download failed with HTTP ${response.code}")
        }
    }

    private fun streamResponseBody(
        body: okhttp3.ResponseBody,
        targetFile: File,
        fallbackSizeBytes: Long,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ) {
        val totalBytes =
            body.contentLength().takeIf { it > 0L } ?: fallbackSizeBytes.coerceAtLeast(0L)
        var bytesReadTotal = 0L
        val buffer = ByteArray(STREAM_BUFFER_SIZE)

        body.byteStream().use { input ->
            targetFile.outputStream().use { output ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    bytesReadTotal += read
                    onProgress(bytesReadTotal, totalBytes)
                }
            }
        }
    }

    companion object {
        const val DEFAULT_MANIFEST_URL = "https://tubik.23p.dev/updates/latest.json"
        private const val STREAM_BUFFER_SIZE = 8192

        /** Removes any cached update APKs or partial downloads inside [directory]. */
        fun cleanDirectory(directory: File) {
            if (!directory.exists()) return
            directory.listFiles()?.forEach { child ->
                if (child.isFile) {
                    child.delete()
                }
            }
        }
    }
}
