package com.github.libretube.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.libretube.updater.AppUpdater
import com.github.libretube.updater.BackgroundCheckOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Periodic background worker that checks `latest.json` and notifies when an update is ready. */
class UpdateCheckWorker(appContext: Context, parameters: WorkerParameters) :
    CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val outcome =
            withContext(Dispatchers.IO) {
                AppUpdater.getInstance(applicationContext).runBackgroundCheck()
            }
        return when (outcome) {
            is BackgroundCheckOutcome.Failed -> Result.retry()
            else -> Result.success()
        }
    }
}
