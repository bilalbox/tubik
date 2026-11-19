package com.github.libretube.updater

import android.content.Context
import android.os.Build
import com.github.libretube.BuildConfig
import com.github.libretube.api.JsonHelper
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.helpers.PreferenceHelper
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

/** Parsed update manifest matching `https://tubik.23p.dev/updates/latest.json`. */
@Serializable
data class UpdateManifest(
    val schemaVersion: Int,
    val versionCode: Int,
    val versionName: String,
    val minSdk: Int,
    val minSupportedVersionCode: Int = 0,
    val publishedAt: String = "",
    val sha256: String,
    val sizeBytes: Long,
    val apkUrl: String,
    val releaseNotesUrl: String = "",
    val changelog: String = "",
) {
    fun normalized(): UpdateManifest = copy(sha256 = sha256.trim().lowercase())

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        private val SHA256_HEX_REGEX = Regex("^[0-9a-fA-F]{64}$")

        fun parse(jsonString: String): UpdateManifest =
            JsonHelper.json.decodeFromString(serializer(), jsonString)

        fun encode(manifest: UpdateManifest): String =
            JsonHelper.json.encodeToString(serializer(), manifest)

        fun isValidSha256Hex(sha256: String): Boolean = SHA256_HEX_REGEX.matches(sha256.trim())

        fun isHttpsUrl(url: String): Boolean = url.trim().toHttpUrlOrNull()?.isHttps == true

        fun evaluate(
            manifest: UpdateManifest,
            currentVersionCode: Int,
            currentSdkInt: Int,
        ): UpdateCheckResult {
            val normalized = manifest.normalized()
            val invalidReason = validateManifest(normalized)
            return when {
                invalidReason != null -> UpdateCheckResult.InvalidManifest(invalidReason)
                currentSdkInt < normalized.minSdk ->
                    UpdateCheckResult.IncompatibleSdk(normalized, currentSdkInt)
                normalized.versionCode <= currentVersionCode ->
                    UpdateCheckResult.UpToDate(currentVersionCode)
                else -> UpdateCheckResult.UpdateAvailable(normalized)
            }
        }

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
    }
}

sealed interface UpdateCheckResult {
    data class UpdateAvailable(val manifest: UpdateManifest) : UpdateCheckResult

    data class UpToDate(val currentVersionCode: Int) : UpdateCheckResult

    data class IncompatibleSdk(val manifest: UpdateManifest, val currentSdkInt: Int) :
        UpdateCheckResult

    data class InvalidManifest(val reason: String) : UpdateCheckResult
}

data class CachedUpdateState(
    val lastCheckTimestampMillis: Long,
    val result: UpdateCheckResult?,
)

sealed interface BackgroundCheckOutcome {
    data object Disabled : BackgroundCheckOutcome

    data class UpdateNotified(val manifest: UpdateManifest) : BackgroundCheckOutcome

    data class AlreadyNotified(val manifest: UpdateManifest) : BackgroundCheckOutcome

    data class NoUpdate(val result: UpdateCheckResult) : BackgroundCheckOutcome

    data class Failed(val error: Throwable) : BackgroundCheckOutcome
}

data class ApkVerificationInfo(
    val packageName: String,
    val signerSha256Set: Set<String>,
)

sealed interface VerificationResult {
    data object Success : VerificationResult

    data class Sha256Mismatch(val expected: String, val actual: String) : VerificationResult

    data class PackageMismatch(val expected: String, val actual: String?) : VerificationResult

    data object MissingSignatures : VerificationResult

    data object SignatureMismatch : VerificationResult

    data object FileMissing : VerificationResult
}

sealed interface DownloadAndInstallStage {
    data class Downloading(val bytesRead: Long, val totalBytes: Long) : DownloadAndInstallStage

    data object Verifying : DownloadAndInstallStage

    data object Installing : DownloadAndInstallStage
}

sealed interface DownloadAndInstallOutcome {
    data object BlockedByScreenPin : DownloadAndInstallOutcome

    data object NeedsUnknownSourcesPermission : DownloadAndInstallOutcome

    data class DownloadFailed(val error: Throwable) : DownloadAndInstallOutcome

    data class VerificationFailed(val result: VerificationResult) : DownloadAndInstallOutcome

    data object InstallCommitted : DownloadAndInstallOutcome

    data class InstallFailed(val error: Throwable) : DownloadAndInstallOutcome
}

sealed interface UpdateInstallStatus {
    data object BlockedByScreenPin : UpdateInstallStatus

    data object Succeeded : UpdateInstallStatus

    data object Aborted : UpdateInstallStatus

    data class Failed(val message: String?) : UpdateInstallStatus
}

/** Persistence port for OTA update preferences and cached manifest metadata. */
interface UpdateStorage {
    var autoUpdateCheckEnabled: Boolean
    var lastCheckTimestampMillis: Long
    var cachedManifestJson: String?
    var lastNotifiedVersionCode: Int
}

object PreferenceUpdateStorage : UpdateStorage {
    override var autoUpdateCheckEnabled: Boolean
        get() = PreferenceHelper.getBoolean(PreferenceKeys.AUTO_UPDATE_CHECK_ENABLED, true)
        set(value) = PreferenceHelper.putBoolean(PreferenceKeys.AUTO_UPDATE_CHECK_ENABLED, value)

    override var lastCheckTimestampMillis: Long
        get() = PreferenceHelper.getLong(PreferenceKeys.LAST_UPDATE_CHECK_TIMESTAMP, 0L)
        set(value) = PreferenceHelper.putLong(PreferenceKeys.LAST_UPDATE_CHECK_TIMESTAMP, value)

    override var cachedManifestJson: String?
        get() =
            PreferenceHelper.getString(PreferenceKeys.CACHED_UPDATE_MANIFEST, "").takeIf {
                it.isNotBlank()
            }
        set(value) {
            if (value.isNullOrBlank()) {
                PreferenceHelper.remove(PreferenceKeys.CACHED_UPDATE_MANIFEST)
            } else {
                PreferenceHelper.putString(PreferenceKeys.CACHED_UPDATE_MANIFEST, value)
            }
        }

    override var lastNotifiedVersionCode: Int
        get() = PreferenceHelper.getInt(PreferenceKeys.LAST_NOTIFIED_UPDATE_VERSION_CODE, 0)
        set(value) =
            PreferenceHelper.putInt(PreferenceKeys.LAST_NOTIFIED_UPDATE_VERSION_CODE, value)
}

/** Single Android OS adapter seam for the OTA updater lifecycle. */
interface UpdatePlatform {
    val packageName: String
    val updatesDir: File

    fun isScreenPinned(): Boolean

    fun canRequestPackageInstalls(): Boolean

    fun readApkVerificationInfo(apkFile: File): ApkVerificationInfo?

    fun readInstalledSigners(packageName: String): Set<String>?

    fun commitInstall(apkFile: File): Result<Unit>

    fun schedulePeriodicCheck(intervalHours: Long, replaceExisting: Boolean)

    fun cancelPeriodicCheck()

    fun notifyUpdateAvailable(manifest: UpdateManifest)
}

/**
 * Deep OTA updater module owning manifest checks, background scheduling/deduplication, HTTPS APK
 * download, SHA-256 & signing-certificate verification, Screen Pin guards, PackageInstaller commit,
 * install status events, and atomic staged-APK cleanup.
 */
class AppUpdater(
    private val platform: UpdatePlatform,
    private val storage: UpdateStorage = PreferenceUpdateStorage,
    httpClient: OkHttpClient = sharedHttpClient,
    private val manifestUrl: String = DEFAULT_MANIFEST_URL,
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    private val currentSdkInt: Int = Build.VERSION.SDK_INT,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val transport = ApkTransport(httpClient)
    private val _installEvents = MutableSharedFlow<UpdateInstallStatus>(extraBufferCapacity = 4)
    val installEvents: SharedFlow<UpdateInstallStatus> = _installEvents

    fun canOpenExternalLink(): Boolean = !platform.isScreenPinned()

    /** Returns cached update status, clearing stale entries after an upgrade. */
    fun cachedState(): CachedUpdateState {
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
                if (!rawJson.isNullOrBlank()) storage.cachedManifestJson = null
                if (timestamp > 0L) UpdateCheckResult.UpToDate(currentVersionCode) else null
            }

        return CachedUpdateState(timestamp, result)
    }

    /** Fetches `latest.json` over HTTPS, evaluates eligibility, and updates cached status. */
    fun checkForUpdate(): Result<UpdateCheckResult> = runCatching {
        val bodyText = transport.fetchManifestText(manifestUrl)
        val manifest = UpdateManifest.parse(bodyText).normalized()
        val evaluation = UpdateManifest.evaluate(manifest, currentVersionCode, currentSdkInt)

        storage.lastCheckTimestampMillis = clockMillis()
        storage.cachedManifestJson =
            when (evaluation) {
                is UpdateCheckResult.UpdateAvailable,
                is UpdateCheckResult.IncompatibleSdk -> UpdateManifest.encode(manifest)
                is UpdateCheckResult.UpToDate,
                is UpdateCheckResult.InvalidManifest -> null
            }
        evaluation
    }

    /** Synchronizes periodic background scheduling with the stored auto-update toggle. */
    fun syncSchedule(replaceExisting: Boolean = false) {
        if (storage.autoUpdateCheckEnabled) {
            platform.schedulePeriodicCheck(CHECK_INTERVAL_HOURS, replaceExisting)
        } else {
            platform.cancelPeriodicCheck()
        }
    }

    /** Persists the auto-update toggle and reschedules or cancels background work. */
    fun setAutoUpdateEnabled(enabled: Boolean) {
        storage.autoUpdateCheckEnabled = enabled
        syncSchedule(replaceExisting = true)
    }

    /** Runs a background check and posts at most one notification per `versionCode`. */
    fun runBackgroundCheck(): BackgroundCheckOutcome =
        if (!storage.autoUpdateCheckEnabled) {
            BackgroundCheckOutcome.Disabled
        } else {
            checkForUpdate()
                .fold(
                    onSuccess = { checkResult ->
                        when (checkResult) {
                            is UpdateCheckResult.UpdateAvailable -> {
                                val manifest = checkResult.manifest
                                if (manifest.versionCode > storage.lastNotifiedVersionCode) {
                                    platform.notifyUpdateAvailable(manifest)
                                    storage.lastNotifiedVersionCode = manifest.versionCode
                                    BackgroundCheckOutcome.UpdateNotified(manifest)
                                } else {
                                    BackgroundCheckOutcome.AlreadyNotified(manifest)
                                }
                            }
                            else -> BackgroundCheckOutcome.NoUpdate(checkResult)
                        }
                    },
                    onFailure = { error -> BackgroundCheckOutcome.Failed(error) },
                )
        }

    /**
     * Executes the complete interactive OTA install flow: pre-check Screen Pin & Unknown Sources ->
     * HTTPS download -> SHA-256 + signing-certificate verification -> post-download Screen Pin &
     * Unknown Sources re-check -> PackageInstaller commit. Deletes staged APKs on every failure or
     * blocked path.
     */
    fun downloadAndInstall(
        manifest: UpdateManifest,
        onStage: (DownloadAndInstallStage) -> Unit = {},
    ): DownloadAndInstallOutcome {
        val preBlocked = checkPreconditions()
        val outcome =
            if (preBlocked != null) {
                preBlocked
            } else {
                executeDownloadVerifyAndCommit(manifest, onStage)
            }
        return outcome
    }

    /**
     * Processes PackageInstaller broadcast outcomes, cleans staged APKs, and notifies observers.
     */
    fun onInstallCallback(status: UpdateInstallStatus, apkPath: String? = null) {
        apkPath?.let { File(it).delete() }
        transport.cleanDirectory(platform.updatesDir)
        _installEvents.tryEmit(status)
    }

    private fun checkPreconditions(): DownloadAndInstallOutcome? =
        when {
            platform.isScreenPinned() -> DownloadAndInstallOutcome.BlockedByScreenPin
            !platform.canRequestPackageInstalls() ->
                DownloadAndInstallOutcome.NeedsUnknownSourcesPermission
            else -> null
        }

    private fun executeDownloadVerifyAndCommit(
        manifest: UpdateManifest,
        onStage: (DownloadAndInstallStage) -> Unit,
    ): DownloadAndInstallOutcome {
        val downloadResult =
            transport.downloadApk(manifest, platform.updatesDir) { bytesRead, totalBytes ->
                onStage(DownloadAndInstallStage.Downloading(bytesRead, totalBytes))
            }
        val apkFile = downloadResult.getOrElse { error ->
            return DownloadAndInstallOutcome.DownloadFailed(error)
        }

        onStage(DownloadAndInstallStage.Verifying)
        val verification = transport.verifyApk(apkFile, manifest.sha256, platform)
        val postBlocked =
            if (verification == VerificationResult.Success) checkPreconditions() else null

        val outcome =
            when {
                verification != VerificationResult.Success ->
                    DownloadAndInstallOutcome.VerificationFailed(verification)
                postBlocked != null -> postBlocked
                else -> {
                    onStage(DownloadAndInstallStage.Installing)
                    platform
                        .commitInstall(apkFile)
                        .fold(
                            onSuccess = { DownloadAndInstallOutcome.InstallCommitted },
                            onFailure = { error -> DownloadAndInstallOutcome.InstallFailed(error) },
                        )
                }
            }

        if (outcome != DownloadAndInstallOutcome.InstallCommitted) {
            apkFile.delete()
        }
        return outcome
    }

    private class ApkTransport(private val httpClient: OkHttpClient) {
        fun fetchManifestText(manifestUrl: String): String {
            val url = manifestUrl.trim().toHttpUrlOrNull()
            require(url != null && url.isHttps) { "Manifest URL must use HTTPS: $manifestUrl" }
            val request = Request.Builder().url(url).get().build()
            return httpClient.newCall(request).execute().use { response ->
                validateHttpsSuccess(response, "Manifest request")
                response.body.string()
            }
        }

        fun downloadApk(
            manifest: UpdateManifest,
            targetDir: File,
            onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
        ): Result<File> = runCatching {
            val url = manifest.apkUrl.trim().toHttpUrlOrNull()
            require(url != null && url.isHttps) { "APK URL must use HTTPS: ${manifest.apkUrl}" }

            cleanDirectory(targetDir)
            check(targetDir.exists() || targetDir.mkdirs()) {
                "Unable to create update directory: ${targetDir.absolutePath}"
            }

            val safeVersion = manifest.versionName.replace(SAFE_VERSION_REGEX, "_")
            val partFile = File(targetDir, "tubik-v$safeVersion.apk.part")
            val finalFile = File(targetDir, "tubik-v$safeVersion.apk")
            var completed = false
            try {
                val request = Request.Builder().url(url).get().build()
                httpClient.newCall(request).execute().use { response ->
                    validateHttpsSuccess(response, "APK download")
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

        fun verifyApk(
            apkFile: File,
            expectedSha256: String,
            platform: UpdatePlatform,
        ): VerificationResult {
            val outcome =
                if (!apkFile.isFile) {
                    VerificationResult.FileMissing
                } else {
                    val normalizedExpected = expectedSha256.trim().lowercase()
                    val actualSha256 = computeSha256(apkFile)
                    val hashesMatch =
                        UpdateManifest.isValidSha256Hex(normalizedExpected) &&
                            MessageDigest.isEqual(
                                normalizedExpected.toByteArray(Charsets.UTF_8),
                                actualSha256.toByteArray(Charsets.UTF_8),
                            )
                    if (!hashesMatch) {
                        VerificationResult.Sha256Mismatch(normalizedExpected, actualSha256)
                    } else {
                        verifySignatures(apkFile, platform)
                    }
                }
            if (outcome != VerificationResult.Success) {
                apkFile.delete()
            }
            return outcome
        }

        fun cleanDirectory(directory: File) {
            if (!directory.exists()) return
            directory.listFiles()?.forEach { child -> if (child.isFile) child.delete() }
        }

        private fun verifySignatures(apkFile: File, platform: UpdatePlatform): VerificationResult {
            val expectedPackage = platform.packageName
            val archiveInfo = platform.readApkVerificationInfo(apkFile)
            val installedSigners = platform.readInstalledSigners(expectedPackage)
            return when {
                archiveInfo == null || archiveInfo.packageName != expectedPackage ->
                    VerificationResult.PackageMismatch(
                        expected = expectedPackage,
                        actual = archiveInfo?.packageName,
                    )
                installedSigners.isNullOrEmpty() || archiveInfo.signerSha256Set.isEmpty() ->
                    VerificationResult.MissingSignatures
                archiveInfo.signerSha256Set != installedSigners ->
                    VerificationResult.SignatureMismatch
                else -> VerificationResult.Success
            }
        }

        private fun validateHttpsSuccess(response: Response, label: String) {
            if (!response.request.url.isHttps) {
                throw IOException("Refused redirect to non-HTTPS URL: ${response.request.url}")
            }
            if (!response.isSuccessful) {
                throw IOException("$label failed with HTTP ${response.code}")
            }
        }

        private fun streamResponseBody(
            body: ResponseBody,
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
    }

    companion object {
        const val DEFAULT_MANIFEST_URL = "https://tubik.23p.dev/updates/latest.json"
        const val CHECK_INTERVAL_HOURS = 24L

        private const val STREAM_BUFFER_SIZE = 8192
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 60L
        private const val CALL_TIMEOUT_SECONDS = 300L
        private val SAFE_VERSION_REGEX = Regex("[^A-Za-z0-9._-]")

        @Volatile private var sharedInstance: AppUpdater? = null

        private val sharedHttpClient: OkHttpClient by lazy { buildHttpClient() }

        /** Returns the process-wide [AppUpdater] wired to Android OS services and preferences. */
        fun getInstance(context: Context): AppUpdater {
            return sharedInstance
                ?: synchronized(this) {
                    sharedInstance
                        ?: run {
                            val appContext = context.applicationContext ?: context
                            PreferenceHelper.initialize(appContext)
                            AppUpdater(
                                platform = AndroidUpdatePlatform(appContext),
                                storage = PreferenceUpdateStorage,
                                httpClient = sharedHttpClient,
                            )
                        }
                            .also { sharedInstance = it }
                }
        }

        fun buildHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(false)
                .addNetworkInterceptor { chain ->
                    val request = chain.request()
                    if (!request.url.isHttps) {
                        throw IOException("Refusing non-HTTPS OTA request: ${request.url}")
                    }
                    chain.proceed(request)
                }
                .build()

        internal fun computeSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            file.inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        internal fun fingerprintCertificate(certBytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(certBytes).joinToString("") {
                "%02x".format(it)
            }
    }
}
