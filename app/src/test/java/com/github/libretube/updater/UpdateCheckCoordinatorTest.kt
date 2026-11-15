package com.github.libretube.updater

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckCoordinatorTest {
    private class FakeUpdateStorage(
        override var autoUpdateCheckEnabled: Boolean = true,
        override var lastCheckTimestampMillis: Long = 0L,
        override var cachedManifestJson: String? = null,
        override var lastNotifiedVersionCode: Int = 0,
    ) : UpdateStorage

    private class FakeWorkScheduler : UpdateWorkScheduler {
        val scheduledCalls = mutableListOf<Pair<Long, Boolean>>()
        var cancelCount = 0

        override fun schedulePeriodicCheck(intervalHours: Long, replaceExisting: Boolean) {
            scheduledCalls.add(intervalHours to replaceExisting)
        }

        override fun cancelPeriodicCheck() {
            cancelCount++
        }
    }

    private class FakeNotifier : UpdateNotifier {
        val notifiedManifests = mutableListOf<UpdateManifest>()

        override fun notifyUpdateAvailable(manifest: UpdateManifest) {
            notifiedManifests.add(manifest)
        }
    }

    private fun clientReturning(json: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_2)
                    .code(200)
                    .message("OK")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

    @Test
    fun `syncSchedule schedules 24h periodic check by default and cancels when toggled off`() {
        val storage = FakeUpdateStorage(autoUpdateCheckEnabled = true)
        val scheduler = FakeWorkScheduler()
        val coordinator =
            UpdateCheckCoordinator(
                repository =
                    UpdateRepository(storage = storage, httpClient = clientReturning("{}")),
                storage = storage,
                scheduler = scheduler,
                notifier = FakeNotifier(),
            )

        coordinator.syncSchedule(replaceExisting = false)
        assertEquals(listOf(24L to false), scheduler.scheduledCalls)
        assertEquals(0, scheduler.cancelCount)

        coordinator.setAutoUpdateEnabled(false)
        assertFalse(storage.autoUpdateCheckEnabled)
        assertEquals(1, scheduler.cancelCount)

        coordinator.setAutoUpdateEnabled(true)
        assertTrue(storage.autoUpdateCheckEnabled)
        assertEquals(listOf(24L to false, 24L to true), scheduler.scheduledCalls)
    }

    @Test
    fun `runBackgroundCheck notifies once per versionCode and skips when disabled`() {
        val manifestJson =
            """
            {
              "schemaVersion": 1,
              "versionCode": 81,
              "versionName": "0.7.1",
              "minSdk": 26,
              "sha256": "${"0123456789abcdef".repeat(4)}",
              "sizeBytes": 48123456,
              "apkUrl": "https://tubik-releases.23p.dev/tubik-v0.7.1.apk"
            }
            """
                .trimIndent()
        val storage = FakeUpdateStorage(autoUpdateCheckEnabled = true)
        val notifier = FakeNotifier()
        val repo =
            UpdateRepository(
                storage = storage,
                httpClient = clientReturning(manifestJson),
                currentVersionCode = 80,
                currentSdkInt = 34,
                clockMillis = { 1000L },
            )
        val coordinator =
            UpdateCheckCoordinator(
                repository = repo,
                storage = storage,
                scheduler = FakeWorkScheduler(),
                notifier = notifier,
            )

        val first = coordinator.runBackgroundCheck()
        assertTrue(first is BackgroundCheckOutcome.UpdateNotified)
        assertEquals(listOf(81), notifier.notifiedManifests.map { it.versionCode })
        assertEquals(81, storage.lastNotifiedVersionCode)

        // Running again for the same versionCode must not send duplicate notifications
        val second = coordinator.runBackgroundCheck()
        assertTrue(second is BackgroundCheckOutcome.AlreadyNotified)
        assertEquals(1, notifier.notifiedManifests.size)

        // When disabled, background check returns Disabled without notifying
        storage.autoUpdateCheckEnabled = false
        val disabled = coordinator.runBackgroundCheck()
        assertEquals(BackgroundCheckOutcome.Disabled, disabled)
    }

    @Test
    fun `UpdateInstallGate blocks install and unknown-sources triggers while Screen Pin is active`() {
        assertEquals(
            InstallPrecondition.BlockedByScreenPin,
            UpdateInstallGate.evaluate(isScreenPinned = true, canRequestPackageInstalls = false),
        )
        assertEquals(
            InstallPrecondition.BlockedByScreenPin,
            UpdateInstallGate.evaluate(isScreenPinned = true, canRequestPackageInstalls = true),
        )
        assertEquals(
            InstallPrecondition.NeedsUnknownSourcesPermission,
            UpdateInstallGate.evaluate(isScreenPinned = false, canRequestPackageInstalls = false),
        )
        assertEquals(
            InstallPrecondition.ReadyToInstall,
            UpdateInstallGate.evaluate(isScreenPinned = false, canRequestPackageInstalls = true),
        )
    }
}
