package com.github.libretube.updater

/** Abstraction over periodic background update check scheduling (WorkManager in production). */
interface UpdateWorkScheduler {
    fun schedulePeriodicCheck(intervalHours: Long, replaceExisting: Boolean)

    fun cancelPeriodicCheck()
}

/**
 * Abstraction over posting update-available notifications (NotificationManagerCompat in
 * production).
 */
fun interface UpdateNotifier {
    fun notifyUpdateAvailable(manifest: UpdateManifest)
}

/** Outcome of running a periodic background update check. */
sealed interface BackgroundCheckOutcome {
    data object Disabled : BackgroundCheckOutcome

    data class UpdateNotified(val manifest: UpdateManifest) : BackgroundCheckOutcome

    data class AlreadyNotified(val manifest: UpdateManifest) : BackgroundCheckOutcome

    data class NoUpdate(val result: UpdateCheckResult) : BackgroundCheckOutcome

    data class Failed(val error: Throwable) : BackgroundCheckOutcome
}

/** Precondition state before launching unknown-sources settings or PackageInstaller. */
enum class InstallPrecondition {
    BlockedByScreenPin,
    NeedsUnknownSourcesPermission,
    ReadyToInstall,
}

/** Evaluates child-device safety preconditions prior to starting APK installation. */
object UpdateInstallGate {
    fun evaluate(isScreenPinned: Boolean, canRequestPackageInstalls: Boolean): InstallPrecondition {
        return when {
            isScreenPinned -> InstallPrecondition.BlockedByScreenPin
            !canRequestPackageInstalls -> InstallPrecondition.NeedsUnknownSourcesPermission
            else -> InstallPrecondition.ReadyToInstall
        }
    }
}

/** Coordinates periodic update check scheduling, toggle updates, and background notifications. */
class UpdateCheckCoordinator(
    private val repository: UpdateRepository,
    private val storage: UpdateStorage,
    private val scheduler: UpdateWorkScheduler,
    private val notifier: UpdateNotifier,
) {
    /** Synchronizes periodic background scheduling with the stored preference toggle. */
    fun syncSchedule(replaceExisting: Boolean = false) {
        if (storage.autoUpdateCheckEnabled) {
            scheduler.schedulePeriodicCheck(CHECK_INTERVAL_HOURS, replaceExisting)
        } else {
            scheduler.cancelPeriodicCheck()
        }
    }

    /** Updates the auto-update toggle and reschedules or cancels background work. */
    fun setAutoUpdateEnabled(enabled: Boolean) {
        storage.autoUpdateCheckEnabled = enabled
        syncSchedule(replaceExisting = true)
    }

    /**
     * Runs a background update check without ever presenting dialogs or install intents.
     * Deduplicates notifications per `versionCode`.
     */
    fun runBackgroundCheck(): BackgroundCheckOutcome {
        if (!storage.autoUpdateCheckEnabled) {
            return BackgroundCheckOutcome.Disabled
        }

        return repository
            .checkForUpdate()
            .fold(
                onSuccess = { checkResult ->
                    when (checkResult) {
                        is UpdateCheckResult.UpdateAvailable -> {
                            val manifest = checkResult.manifest
                            if (manifest.versionCode > storage.lastNotifiedVersionCode) {
                                notifier.notifyUpdateAvailable(manifest)
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

    companion object {
        const val CHECK_INTERVAL_HOURS = 24L
    }
}
