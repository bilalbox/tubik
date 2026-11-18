package com.github.libretube.helpers

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import androidx.core.content.getSystemService

object ScreenPinHelper {
    fun isPinned(context: Context): Boolean {
        val am = context.getSystemService<ActivityManager>() ?: return false
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    fun startLockTask(activity: Activity): Boolean = runCatching {
        activity.startLockTask()
    }
        .isSuccess
}
