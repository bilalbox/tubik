package com.github.libretube.ui.sheets

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.os.bundleOf
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.constants.IntentData
import com.github.libretube.enums.ShareObjectType
import com.github.libretube.enums.menuoption.ChannelBottomSheetItem
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.BackgroundHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.obj.ShareData
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.ui.dialogs.ShareDialog
import com.github.libretube.util.ChannelSettingSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dialog with different options for a selected video.
 *
 * Needs the [channelId] to load the content from the right video.
 */
class ChannelOptionsBottomSheet : BaseBottomSheet() {
    private lateinit var channelId: String
    private var channelName: String? = null
    private var subscribed: Boolean = false
    private var channelAvatar: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        channelId = arguments?.getString(IntentData.channelId)!!
        channelName = arguments?.getString(IntentData.channelName)
        channelAvatar = arguments?.getString(IntentData.channelAvatar)
        subscribed = arguments?.getBoolean(IntentData.isSubscribed, false) ?: false

        setTitle(channelName)

        // List that stores the different menu options. In the future could be add more options
        // here.
        val optionsList =
            mutableListOf<ChannelBottomSheetItem>(
                ChannelBottomSheetItem.Share,
                ChannelBottomSheetItem.PlayLatestVideos,
                ChannelBottomSheetItem.PlayOnBackground,
            )
        if (subscribed) optionsList.add(ChannelBottomSheetItem.AddToGroup)
        optionsList += channelSettingItems()

        setItems(optionsList) { which ->
            when (val option = optionsList[which]) {
                is ChannelBottomSheetItem.FeedScope,
                is ChannelBottomSheetItem.AudioOnlyDefault -> toggleChannelSetting(option)

                ChannelBottomSheetItem.Share -> {
                    val bundle =
                        bundleOf(
                            IntentData.id to channelId,
                            IntentData.shareObjectType to ShareObjectType.CHANNEL,
                            IntentData.shareData to
                                ShareData(
                                    title = channelName,
                                    previewImageUrl = channelAvatar,
                                ),
                        )
                    val newShareDialog = ShareDialog()
                    newShareDialog.arguments = bundle
                    newShareDialog.show(parentFragmentManager, null)
                }

                ChannelBottomSheetItem.AddToGroup -> {
                    val sheet =
                        AddChannelToGroupSheet().apply {
                            arguments = bundleOf(IntentData.channelId to channelId)
                        }
                    sheet.show(parentFragmentManager, null)
                }

                ChannelBottomSheetItem.PlayLatestVideos -> {
                    try {
                        val channel =
                            withContext(Dispatchers.IO) {
                                MediaServiceRepository.instance.getChannel(channelId)
                            }
                        channel.relatedStreams.firstOrNull()?.url?.toID()?.let {
                            NavigationHelper.navigateVideo(
                                requireContext(),
                                PlayerData(
                                    it,
                                    channelId = channelId,
                                ),
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG(), e.toString())
                    }
                }

                ChannelBottomSheetItem.PlayOnBackground -> {
                    try {
                        val channel =
                            withContext(Dispatchers.IO) {
                                MediaServiceRepository.instance.getChannel(channelId)
                            }
                        channel.relatedStreams.firstOrNull()?.url?.toID()?.let {
                            BackgroundHelper.playOnBackground(
                                requireContext(),
                                PlayerData(
                                    videoId = it,
                                    channelId = channelId,
                                ),
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG(), e.toString())
                    }
                }
            }
        }
    }

    /** Per-channel settings that never weaken protection, so they never need the Parent PIN. */
    private fun channelSettingItems(): List<ChannelBottomSheetItem> = buildList {
        if (subscribed) {
            add(ChannelBottomSheetItem.FeedScope(seriesOnlyFeedScope.isEnabled(channelId)))
        }
        add(ChannelBottomSheetItem.AudioOnlyDefault(audioOnlyDefault.isEnabled(channelId)))
    }

    private fun toggleChannelSetting(option: ChannelBottomSheetItem) {
        val message =
            when (option) {
                is ChannelBottomSheetItem.FeedScope -> {
                    seriesOnlyFeedScope.set(channelId, !option.seriesOnly)
                    if (option.seriesOnly) R.string.feed_scope_all_uploads_on
                    else R.string.feed_scope_series_only_on
                }
                is ChannelBottomSheetItem.AudioOnlyDefault -> {
                    audioOnlyDefault.set(channelId, !option.enabled)
                    if (option.enabled) R.string.audio_only_default_off
                    else R.string.audio_only_default_on
                }
                else -> return
            }
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        val seriesOnlyFeedScope
            get() = ChannelSettingSet.seriesOnlyFeedScope

        val audioOnlyDefault
            get() = ChannelSettingSet.audioOnlyDefault
    }
}
