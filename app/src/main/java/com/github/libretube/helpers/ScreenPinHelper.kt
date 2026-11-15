package com.github.libretube.helpers

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.getSystemService
import com.github.libretube.R
import com.github.libretube.util.ScreenLockTaskAdapter

object ScreenPinHelper {
    private const val LOCK_TASK_CALLBACK_DELAY_MS = 300L

    fun isPinned(context: Context): Boolean {
        val am = context.getSystemService<ActivityManager>() ?: return false
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    fun lockTaskAdapter(
        activity: Activity,
        onStateChanged: (() -> Unit)? = null,
    ): ScreenLockTaskAdapter = ScreenLockTaskAdapter {
        runCatching {
            activity.startLockTask()
            Toast.makeText(activity, R.string.screen_pin_unpin_hint, Toast.LENGTH_SHORT).show()
            onStateChanged?.let { callback ->
                Handler(Looper.getMainLooper()).postDelayed(callback, LOCK_TASK_CALLBACK_DELAY_MS)
            }
        }
    }
}
