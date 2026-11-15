package com.github.libretube.updater

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkVerifierTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private class FakeSignatureReader(
        private val installedSigners: Set<String>?,
        private val archiveInfo: ApkVerificationInfo?,
    ) : ApkSignatureReader {
        override fun readApkVerificationInfo(apkFile: File): ApkVerificationInfo? = archiveInfo

        override fun readInstalledSigners(packageName: String): Set<String>? = installedSigners
    }

    @Test
    fun `verify succeeds when sha256, packageName, and signing certificates match`() {
        val apkBytes = "valid-signed-apk-content".toByteArray()
        val apkFile = tempFolder.newFile("tubik-v0.7.1.apk").apply { writeBytes(apkBytes) }
        val expectedSha = ApkVerifier.computeSha256(apkFile)
        val certFingerprint = ApkVerifier.fingerprintCertificate("tubik-release-cert".toByteArray())

        val reader =
            FakeSignatureReader(
                installedSigners = setOf(certFingerprint),
                archiveInfo =
                    ApkVerificationInfo(
                        packageName = "dev.twenty3p.tubik",
                        signerSha256Set = setOf(certFingerprint),
                    ),
            )

        val result =
            ApkVerifier.verify(
                apkFile = apkFile,
                expectedSha256 = expectedSha.uppercase(),
                expectedPackageName = "dev.twenty3p.tubik",
                signatureReader = reader,
            )

        assertEquals(VerificationResult.Success, result)
        assertTrue(apkFile.exists())
    }

    @Test
    fun `verify aborts and deletes cached APK on tampered sha256`() {
        val apkFile = tempFolder.newFile("tampered.apk").apply { writeText("tampered-bytes") }
        val wrongSha = "0".repeat(64)
        val certFingerprint = ApkVerifier.fingerprintCertificate("tubik-release-cert".toByteArray())

        val reader =
            FakeSignatureReader(
                installedSigners = setOf(certFingerprint),
                archiveInfo = ApkVerificationInfo("dev.twenty3p.tubik", setOf(certFingerprint)),
            )

        val result =
            ApkVerifier.verify(
                apkFile = apkFile,
                expectedSha256 = wrongSha,
                expectedPackageName = "dev.twenty3p.tubik",
                signatureReader = reader,
            )

        assertTrue(result is VerificationResult.Sha256Mismatch)
        assertFalse(apkFile.exists())
    }

    @Test
    fun `verify aborts and deletes cached APK on mismatched signing certificate`() {
        val apkFile = tempFolder.newFile("evil-signed.apk").apply { writeText("re-signed-apk") }
        val expectedSha = ApkVerifier.computeSha256(apkFile)
        val installedCert = ApkVerifier.fingerprintCertificate("tubik-release-cert".toByteArray())
        val attackerCert = ApkVerifier.fingerprintCertificate("attacker-cert".toByteArray())

        val reader =
            FakeSignatureReader(
                installedSigners = setOf(installedCert),
                archiveInfo = ApkVerificationInfo("dev.twenty3p.tubik", setOf(attackerCert)),
            )

        val result =
            ApkVerifier.verify(
                apkFile = apkFile,
                expectedSha256 = expectedSha,
                expectedPackageName = "dev.twenty3p.tubik",
                signatureReader = reader,
            )

        assertTrue(result is VerificationResult.SignatureMismatch)
        assertFalse(apkFile.exists())
    }

    @Test
    fun `verify aborts and deletes cached APK on package mismatch or missing signatures`() {
        val cert = ApkVerifier.fingerprintCertificate("tubik-release-cert".toByteArray())

        val wrongPkgApk = tempFolder.newFile("wrong-pkg.apk").apply { writeText("other-app") }
        val wrongPkgResult =
            ApkVerifier.verify(
                apkFile = wrongPkgApk,
                expectedSha256 = ApkVerifier.computeSha256(wrongPkgApk),
                expectedPackageName = "dev.twenty3p.tubik",
                signatureReader =
                    FakeSignatureReader(
                        installedSigners = setOf(cert),
                        archiveInfo = ApkVerificationInfo("com.example.other", setOf(cert)),
                    ),
            )
        assertTrue(wrongPkgResult is VerificationResult.PackageMismatch)
        assertFalse(wrongPkgApk.exists())

        val unsignedApk = tempFolder.newFile("unsigned.apk").apply { writeText("unsigned") }
        val unsignedResult =
            ApkVerifier.verify(
                apkFile = unsignedApk,
                expectedSha256 = ApkVerifier.computeSha256(unsignedApk),
                expectedPackageName = "dev.twenty3p.tubik",
                signatureReader =
                    FakeSignatureReader(
                        installedSigners = setOf(cert),
                        archiveInfo = ApkVerificationInfo("dev.twenty3p.tubik", emptySet()),
                    ),
            )
        assertTrue(unsignedResult is VerificationResult.MissingSignatures)
        assertFalse(unsignedApk.exists())
    }
}
