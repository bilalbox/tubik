package com.github.libretube.ui.sheets

import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.constants.IntentData
import com.github.libretube.enums.ImportFormat
import com.github.libretube.enums.PlaylistType
import com.github.libretube.enums.ShareObjectType
import com.github.libretube.enums.menuoption.PlayListBottomSheetItem
import com.github.libretube.extensions.serializable
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.BackgroundHelper
import com.github.libretube.helpers.ContextHelper
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.obj.ShareData
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.repo.SeriesRepository
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.dialogs.DeletePlaylistDialog
import com.github.libretube.ui.dialogs.PlaylistDescriptionDialog
import com.github.libretube.ui.dialogs.RenamePlaylistDialog
import com.github.libretube.ui.dialogs.ShareDialog
import com.github.libretube.ui.preferences.BackupRestoreSettings
import com.github.libretube.util.PlayingQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistOptionsBottomSheet : BaseBottomSheet() {
    private lateinit var playlistName: String
    private lateinit var playlistId: String
    private lateinit var playlistType: PlaylistType
    private var playlistThumbnail: String? = null

    private var exportFormat: ImportFormat = ImportFormat.NEWPIPE

    /**
     * @param isFollowed whether the playlist is a followed Series; null while unknown or when it
     *   can't be followed (the Allowlist hides Follow for owners that aren't Allowed Channels)
     */
    private fun buildOptionsList(isFollowed: Boolean?): List<PlayListBottomSheetItem> {
        // options for the dialog
        val optionsList =
            mutableListOf(
                PlayListBottomSheetItem.PlayOnBackground,
                PlayListBottomSheetItem.Download,
            )

        if (PlayingQueue.isNotEmpty()) optionsList.add(PlayListBottomSheetItem.AddToQueue)

        if (playlistType == PlaylistType.PUBLIC) {
            optionsList.add(PlayListBottomSheetItem.Share)
            optionsList.add(PlayListBottomSheetItem.Clone)

            // only public playlists can be followed as a Series
            if (isFollowed != null) {
                optionsList.add(
                    if (isFollowed) PlayListBottomSheetItem.Unfollow
                    else PlayListBottomSheetItem.Follow,
                )
            }
        } else {
            optionsList.add(PlayListBottomSheetItem.Export)
            optionsList.add(PlayListBottomSheetItem.Rename)
            optionsList.add(PlayListBottomSheetItem.ChangeDescription)
            optionsList.add(PlayListBottomSheetItem.Delete)
        }

        return optionsList
    }

    private suspend fun onOptionSelected(
        playListOption: PlayListBottomSheetItem,
        isFollowed: Boolean,
    ) {
        val mFragmentManager = (context as BaseActivity).supportFragmentManager

        when (playListOption) {
            // play the playlist in the background
            PlayListBottomSheetItem.PlayOnBackground -> {
                val playlist =
                    withContext(Dispatchers.IO) {
                            runCatching { PlaylistsHelper.getPlaylist(playlistId, playlistType) }
                        }
                        .getOrElse {
                            context?.toastFromMainDispatcher(R.string.error)
                            return
                        }

                playlist.relatedStreams.firstOrNull()?.let {
                    BackgroundHelper.playOnBackground(
                        requireContext(),
                        PlayerData(it.url!!.toID(), playlistId = playlistId),
                    )
                }
            }

            PlayListBottomSheetItem.AddToQueue -> {
                PlayingQueue.insertPlaylist(playlistId, playlistType, null)
            }
            // Clone the playlist to the users Piped account
            PlayListBottomSheetItem.Clone -> {
                val context = requireContext()
                val playlistId =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            PlaylistsHelper.clonePlaylist(playlistId)
                        }
                            .getOrNull()
                    }
                context.toastFromMainDispatcher(
                    if (playlistId != null) R.string.playlistCloned else R.string.server_error,
                )
            }
            // share the playlist
            PlayListBottomSheetItem.Share -> {
                val newShareDialog = ShareDialog()
                newShareDialog.arguments =
                    bundleOf(
                        IntentData.id to playlistId,
                        IntentData.shareObjectType to ShareObjectType.PLAYLIST,
                        IntentData.shareData to
                            ShareData(
                                title = playlistName,
                                previewImageUrl = playlistThumbnail,
                            ),
                    )
                // using parentFragmentManager, childFragmentManager doesn't work here
                newShareDialog.show(parentFragmentManager, ShareDialog::class.java.name)
            }

            PlayListBottomSheetItem.Delete -> {
                val newDeletePlaylistDialog = DeletePlaylistDialog()
                newDeletePlaylistDialog.arguments =
                    bundleOf(
                        IntentData.playlistId to playlistId,
                    )
                newDeletePlaylistDialog.show(mFragmentManager, null)
            }

            PlayListBottomSheetItem.Rename -> {
                val newRenamePlaylistDialog = RenamePlaylistDialog()
                newRenamePlaylistDialog.arguments =
                    bundleOf(
                        IntentData.playlistId to playlistId,
                        IntentData.playlistName to playlistName,
                    )
                newRenamePlaylistDialog.show(mFragmentManager, null)
            }

            PlayListBottomSheetItem.ChangeDescription -> {
                val newPlaylistDescriptionDialog = PlaylistDescriptionDialog()
                newPlaylistDescriptionDialog.arguments =
                    bundleOf(
                        IntentData.playlistId to playlistId,
                        IntentData.playlistDescription to "",
                    )
                newPlaylistDescriptionDialog.show(mFragmentManager, null)
            }

            PlayListBottomSheetItem.Download -> {
                DownloadHelper.startDownloadPlaylistDialog(
                    requireContext(),
                    mFragmentManager,
                    playlistId,
                    playlistName,
                    playlistType,
                )
            }

            PlayListBottomSheetItem.Export -> {
                val context = requireContext()

                BackupRestoreSettings.createImportFormatDialog(
                    context,
                    R.string.export_playlist,
                    BackupRestoreSettings.exportPlaylistFormatList + listOf(ImportFormat.URLSORIDS),
                ) { format, includeTimestamp ->
                    exportFormat = format
                    ContextHelper.unwrapActivity<MainActivity>(context)
                        .startPlaylistExport(
                            playlistId,
                            playlistName,
                            exportFormat,
                            includeTimestamp,
                        )
                }
            }

            else -> {
                withContext(Dispatchers.IO) {
                    if (isFollowed) {
                        SeriesRepository.instance.store.delete(playlistId)
                    } else {
                        val playlist = runCatching {
                            MediaServiceRepository.instance.getPlaylist(playlistId)
                        }
                            .getOrElse {
                                return@withContext
                            }
                        SeriesRepository.instance.follow(playlist.toSeries(playlistId))
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        arguments?.let {
            playlistName = it.getString(IntentData.playlistName)!!
            playlistId = it.getString(IntentData.playlistId)!!
            playlistType = it.serializable(IntentData.playlistType)!!
            playlistThumbnail = it.getString(IntentData.playlistThumbnail)
        }

        setTitle(playlistName)

        var optionsList = buildOptionsList(null)
        setItems(optionsList) { which ->
            onOptionSelected(optionsList[which], false)
        }

        // we have to update the options list as soon as we know whether the playlist is followed
        lifecycleScope.launch(Dispatchers.IO) {
            val series = SeriesRepository.instance
            val isFollowed = runCatching { series.store.get(playlistId) }.getOrNull() != null
            val canFollow =
                isFollowed ||
                    runCatching { series.canFollowPlaylist(playlistId) }.getOrDefault(false)
            if (!canFollow) return@launch

            withContext(Dispatchers.Main) {
                optionsList = buildOptionsList(isFollowed)

                setItems(optionsList) { which ->
                    onOptionSelected(optionsList[which], isFollowed)
                }
            }
        }
    }

    companion object {
        const val PLAYLIST_OPTIONS_REQUEST_KEY = "playlist_options_request_key"
    }
}
