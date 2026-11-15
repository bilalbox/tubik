package com.github.libretube.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.app.PendingIntentCompat
import com.github.libretube.helpers.ScreenPinHelper
import com.github.libretube.receivers.UpdateInstallReceiver
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Terminal or interactive installation events emitted by [UpdateInstallReceiver]. */
sealed interface UpdateInstallStatus {
    data object BlockedByScreenPin : UpdateInstallStatus

    data object Succeeded : UpdateInstallStatus

    data object Aborted : UpdateInstallStatus

    data class Failed(val message: String?) : UpdateInstallStatus
}

/** Streams a verified APK into an Android [PackageInstaller.Session] and commits installation. */
object ApkInstaller {
    private const val SESSION_ENTRY_NAME = "tubik-update.apk"

    private val _events = MutableSharedFlow<UpdateInstallStatus>(extraBufferCapacity = 4)
    val events: SharedFlow<UpdateInstallStatus> = _events

    internal fun emitEvent(status: UpdateInstallStatus) {
        _events.tryEmit(status)
    }

    /**
     * Commits [apkFile] to [PackageInstaller] using an explicit non-exported
     * [UpdateInstallReceiver] callback. Enforces Screen Pin and unknown-sources guards before
     * touching [PackageInstaller].
     */
    fun install(context: Context, apkFile: File): Result<Unit> = runCatching {
        check(!ScreenPinHelper.isPinned(context)) {
            "Screen Pin is active; unpin before installing an update"
        }
        check(context.packageManager.canRequestPackageInstalls()) {
            "Unknown app sources permission is required to install updates"
        }
        require(apkFile.isFile) { "APK file does not exist: ${apkFile.absolutePath}" }

        val packageInstaller = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apkFile.length())
            }

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)
        var committed = false
        try {
            session.openWrite(SESSION_ENTRY_NAME, 0, apkFile.length()).use { out ->
                apkFile.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }

            val callbackIntent =
                Intent(context, UpdateInstallReceiver::class.java)
                    .setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS)
                    .putExtra(UpdateInstallReceiver.EXTRA_APK_PATH, apkFile.absolutePath)

            val pendingIntent =
                requireNotNull(
                    PendingIntentCompat.getBroadcast(
                        context,
                        sessionId,
                        callbackIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT,
                        true,
                    ),
                ) {
                    "Failed to create mutable PendingIntent for PackageInstaller"
                }

            session.commit(pendingIntent.intentSender)
            committed = true
        } finally {
            if (!committed) {
                runCatching { session.abandon() }
            }
            session.close()
        }
    }
}
