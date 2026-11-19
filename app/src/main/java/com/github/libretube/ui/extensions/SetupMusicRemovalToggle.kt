package com.github.libretube.ui.extensions

import android.widget.Toast
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.github.libretube.R
import com.github.libretube.ui.dialogs.ParentPinDialogs
import com.github.libretube.util.AccessControl
import com.github.libretube.util.PolicyChange
import com.google.android.material.button.MaterialButton

fun MaterialButton.setupMusicRemovalToggle(
    channelId: String?,
    onToggled: (Boolean) -> Unit = {},
) {
    val accessControl = AccessControl.instance
    if (channelId.isNullOrBlank() || !accessControl.policy.musicRemovalEnabled) {
        isGone = true
        setOnClickListener(null)
        return
    }

    isVisible = true

    fun updateUi(enabledForChannel: Boolean) {
        setIconResource(if (enabledForChannel) R.drawable.ic_music_off else R.drawable.ic_audio)
        val label =
            context.getString(
                if (enabledForChannel) R.string.music_removal_channel_on
                else R.string.music_removal_channel_off,
            )
        contentDescription = label
        tooltipText = label
    }

    updateUi(accessControl.isMusicRemovalEnabledForChannel(channelId))

    setOnClickListener {
        if (!accessControl.policy.musicRemovalEnabled) {
            isGone = true
            return@setOnClickListener
        }

        val nextEnabled = !accessControl.isMusicRemovalEnabledForChannel(channelId)
        accessControl.apply(
            PolicyChange.SetChannelMusicRemoval(channelId, enabled = nextEnabled),
            ParentPinDialogs.promptAdapter(context),
        ) {
            updateUi(nextEnabled)
            Toast.makeText(
                    context,
                    if (nextEnabled) R.string.music_removal_channel_on
                    else R.string.music_removal_channel_off,
                    Toast.LENGTH_SHORT,
                )
                .show()
            onToggled(nextEnabled)
        }
    }
}
