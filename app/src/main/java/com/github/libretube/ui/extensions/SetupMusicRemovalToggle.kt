package com.github.libretube.ui.extensions

import android.widget.Toast
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.github.libretube.R
import com.github.libretube.ui.dialogs.ParentPinDialogs
import com.github.libretube.util.ParentPinStore
import com.github.libretube.util.ProtectedAction
import com.google.android.material.button.MaterialButton

fun MaterialButton.setupMusicRemovalToggle(
    channelId: String?,
    onToggled: (Boolean) -> Unit = {},
) {
    val store = ParentPinStore.instance
    if (channelId.isNullOrBlank() || !store.isMusicRemovalEnabled()) {
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

    updateUi(store.isMusicRemovalEnabledForChannel(channelId))

    setOnClickListener {
        if (!store.isMusicRemovalEnabled()) {
            isGone = true
            return@setOnClickListener
        }

        val nextEnabled = !store.isMusicRemovalEnabledForChannel(channelId)
        store.authorize(
            ProtectedAction.SetChannelMusicRemoval(enabled = nextEnabled),
            ParentPinDialogs.promptAdapter(context),
        ) {
            store.setMusicRemovalEnabledForChannel(channelId, nextEnabled)
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
