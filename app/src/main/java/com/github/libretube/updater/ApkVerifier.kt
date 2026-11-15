package com.github.libretube.updater

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** Extracted package identity and SHA-256 certificate fingerprints for an APK or installed app. */
data class ApkVerificationInfo(
    val packageName: String,
    val signerSha256Set: Set<String>,
)

/** Abstraction over [PackageManager] archive/package signature lookup for unit testing. */
interface ApkSignatureReader {
    fun readApkVerificationInfo(apkFile: File): ApkVerificationInfo?

    fun readInstalledSigners(packageName: String): Set<String>?
}

/** Result of verifying a downloaded APK's SHA-256 digest and signing certificates. */
sealed interface VerificationResult {
    data object Success : VerificationResult

    data class Sha256Mismatch(val expected: String, val actual: String) : VerificationResult

    data class PackageMismatch(val expected: String, val actual: String?) : VerificationResult

    data object MissingSignatures : VerificationResult

    data object SignatureMismatch : VerificationResult

    data object FileMissing : VerificationResult
}

/** Verifies downloaded APK integrity (SHA-256) and signing certificate provenance. */
object ApkVerifier {
    private const val BUFFER_SIZE = 8192

    /**
     * Verifies [apkFile] against [expectedSha256] and the currently installed package's signing
     * certificates for [expectedPackageName]. Deletes [apkFile] if verification fails.
     */
    fun verify(
        apkFile: File,
        expectedSha256: String,
        expectedPackageName: String,
        signatureReader: ApkSignatureReader,
    ): VerificationResult {
        if (!apkFile.isFile) return VerificationResult.FileMissing

        val normalizedExpected = expectedSha256.trim().lowercase()
        val actualSha256 = computeSha256(apkFile)
        val hashesMatch =
            UpdateManifest.isValidSha256Hex(normalizedExpected) &&
                MessageDigest.isEqual(
                    normalizedExpected.toByteArray(Charsets.UTF_8),
                    actualSha256.toByteArray(Charsets.UTF_8),
                )

        val outcome =
            if (!hashesMatch) {
                VerificationResult.Sha256Mismatch(normalizedExpected, actualSha256)
            } else {
                verifySignatures(apkFile, expectedPackageName, signatureReader)
            }

        if (outcome != VerificationResult.Success) {
            apkFile.delete()
        }
        return outcome
    }

    private fun verifySignatures(
        apkFile: File,
        expectedPackageName: String,
        signatureReader: ApkSignatureReader,
    ): VerificationResult {
        val archiveInfo = signatureReader.readApkVerificationInfo(apkFile)
        val installedSigners = signatureReader.readInstalledSigners(expectedPackageName)
        return when {
            archiveInfo == null || archiveInfo.packageName != expectedPackageName ->
                VerificationResult.PackageMismatch(
                    expected = expectedPackageName,
                    actual = archiveInfo?.packageName,
                )
            installedSigners.isNullOrEmpty() || archiveInfo.signerSha256Set.isEmpty() ->
                VerificationResult.MissingSignatures
            archiveInfo.signerSha256Set != installedSigners -> VerificationResult.SignatureMismatch
            else -> VerificationResult.Success
        }
    }

    /** Streams [file] and returns its lowercase hex SHA-256 digest. */
    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toLowerHex()
    }

    /** Computes the lowercase hex SHA-256 fingerprint of an encoded X.509 certificate. */
    fun fingerprintCertificate(certBytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(certBytes).toLowerHex()

    private fun ByteArray.toLowerHex(): String = joinToString("") { "%02x".format(it) }
}

/** Android [PackageManager]-backed [ApkSignatureReader] supporting API 26+. */
class AndroidApkSignatureReader(private val context: Context) : ApkSignatureReader {
    override fun readApkVerificationInfo(apkFile: File): ApkVerificationInfo? {
        val info = queryPackageInfo(archivePath = apkFile.absolutePath) ?: return null
        return ApkVerificationInfo(
            packageName = info.packageName,
            signerSha256Set = extractSignerFingerprints(info),
        )
    }

    override fun readInstalledSigners(packageName: String): Set<String>? {
        val info =
            runCatching { queryPackageInfo(installedPackageName = packageName) }.getOrNull()
                ?: return null
        return extractSignerFingerprints(info)
    }

    private fun queryPackageInfo(
        archivePath: String? = null,
        installedPackageName: String? = null,
    ): PackageInfo? {
        val pm = context.packageManager
        val target = archivePath ?: requireNotNull(installedPackageName)
        val isArchive = archivePath != null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val flags =
                PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong(),
                )
            if (isArchive) {
                pm.getPackageArchiveInfo(target, flags)
            } else {
                pm.getPackageInfo(target, flags)
            }
        } else {
            @Suppress("DEPRECATION")
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    PackageManager.GET_SIGNING_CERTIFICATES
                } else {
                    PackageManager.GET_SIGNATURES
                }
            @Suppress("DEPRECATION")
            if (isArchive) {
                pm.getPackageArchiveInfo(target, flags)
            } else {
                pm.getPackageInfo(target, flags)
            }
        }
    }

    private fun extractSignerFingerprints(info: PackageInfo): Set<String> {
        val signatures =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION") info.signatures
            }
        return signatures
            ?.map { ApkVerifier.fingerprintCertificate(it.toByteArray()) }
            ?.toSet()
            .orEmpty()
    }
}
