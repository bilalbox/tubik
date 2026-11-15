package com.github.libretube.updater

import com.github.libretube.api.JsonHelper
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
    /** Returns a copy with [sha256] normalized to lowercase. */
    fun normalized(): UpdateManifest = copy(sha256 = sha256.trim().lowercase())

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        private val SHA256_HEX_REGEX = Regex("^[0-9a-fA-F]{64}$")

        /** Parses a JSON string into an [UpdateManifest] using the app's [JsonHelper]. */
        fun parse(jsonString: String): UpdateManifest =
            JsonHelper.json.decodeFromString(serializer(), jsonString)

        /** Encodes an [UpdateManifest] to JSON for local preference caching. */
        fun encode(manifest: UpdateManifest): String =
            JsonHelper.json.encodeToString(serializer(), manifest)

        /** Validates whether [sha256] is a 64-character hexadecimal SHA-256 digest. */
        fun isValidSha256Hex(sha256: String): Boolean = SHA256_HEX_REGEX.matches(sha256.trim())

        /** Validates whether [url] parses as an HTTPS URL. */
        fun isHttpsUrl(url: String): Boolean = url.trim().toHttpUrlOrNull()?.isHttps == true

        /**
         * Evaluates [manifest] against [currentVersionCode] and [currentSdkInt], checking schema
         * version, HTTPS [apkUrl], SHA-256 format, SDK compatibility, and version eligibility.
         */
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

        private fun validateManifest(manifest: UpdateManifest): String? {
            return when {
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
}

/** Result of evaluating an update manifest for the current device and installed version. */
sealed interface UpdateCheckResult {
    data class UpdateAvailable(val manifest: UpdateManifest) : UpdateCheckResult

    data class UpToDate(val currentVersionCode: Int) : UpdateCheckResult

    data class IncompatibleSdk(val manifest: UpdateManifest, val currentSdkInt: Int) :
        UpdateCheckResult

    data class InvalidManifest(val reason: String) : UpdateCheckResult
}
