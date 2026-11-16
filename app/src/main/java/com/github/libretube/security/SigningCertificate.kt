package com.github.libretube.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Diagnostic helper that describes the APK signing certificate for log output. */
internal object SigningCertificate {
    private const val UNAVAILABLE = "unavailable"

    /** Returns the lowercase hex SHA-256 of the first signing certificate, or "unavailable". */
    fun describe(context: Context): String =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            UNAVAILABLE
        } else {
            runCatching {
                val cert =
                    context.packageManager
                        .getPackageInfo(
                            context.packageName,
                            PackageManager.GET_SIGNING_CERTIFICATES,
                        )
                        .signingInfo
                        ?.apkContentsSigners
                        ?.firstOrNull()
                cert?.let {
                    MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString(
                        "",
                    ) {
                        "%02x".format(it)
                    }
                }
            }
                .getOrNull() ?: UNAVAILABLE
        }
}
