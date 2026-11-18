package com.github.libretube.ui.extensions

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat.Type.InsetsType
import androidx.core.view.WindowInsetsControllerCompat
import com.github.libretube.R
import com.github.libretube.helpers.ScreenPinHelper
import com.github.libretube.util.ParentPinStore

private const val ScreenPinRefreshDelayMs = 300L

fun Activity.requestScreenPin(refreshControls: () -> Unit) {
    if (ParentPinStore.instance.startScreenPin { ScreenPinHelper.startLockTask(this) }) {
        Toast.makeText(this, R.string.screen_pin_unpin_hint, Toast.LENGTH_SHORT).show()
        // Android can update lock-task state after the request returns.
        Handler(Looper.getMainLooper()).postDelayed(refreshControls, ScreenPinRefreshDelayMs)
    }
    refreshControls()
}

fun Window.toggleSystemBars(@InsetsType types: Int, showBars: Boolean) {
    WindowCompat.getInsetsController(this, decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (showBars) {
            show(types)
        } else {
            hide(types)
        }
    }
}
