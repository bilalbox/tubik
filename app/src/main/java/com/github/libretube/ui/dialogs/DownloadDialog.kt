package com.github.libretube.ui.dialogs

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.github.libretube.R
import com.github.libretube.api.ChannelNotAllowedException
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.PipedStream
import com.github.libretube.api.obj.Streams
import com.github.libretube.api.obj.Subtitle
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.DialogDownloadBinding
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.getWhileDigit
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.parcelable.DownloadData
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadDialog : DialogFragment() {
    private lateinit var videoId: String
    private var onDownloadConfirm = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoId = arguments?.getString(IntentData.videoId)!!
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogDownloadBinding.inflate(layoutInflater)

        val dialog =
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.download)
                .setView(binding.root)
                .setPositiveButton(R.string.download, null)
                .setNegativeButton(R.string.cancel, null)
                .show()

        val positiveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        positiveButton.isEnabled = false
        positiveButton.setOnClickListener {
            onDownloadConfirm.invoke()
        }

        fetchAvailableSources(binding, positiveButton)
        showVideoAlreadyDownloadedWarning(binding)

        return dialog
    }

    private fun showVideoAlreadyDownloadedWarning(binding: DialogDownloadBinding) {
        lifecycleScope.launch {
            val alreadyDownloaded =
                withContext(Dispatchers.IO) {
                    DatabaseHolder.Database.downloadDao().getDownloadById(videoId)
                } != null

            binding.alreadyDownloadedCard.isVisible = alreadyDownloaded
        }
    }

    private fun fetchAvailableSources(
        binding: DialogDownloadBinding,
        positiveButton: android.widget.Button,
    ) {
        lifecycleScope.launch {
            val response =
                try {
                    withContext(Dispatchers.IO) {
                        MediaServiceRepository.instance.getStreams(videoId)
                    }
                } catch (_: ChannelNotAllowedException) {
                    context?.toastFromMainDispatcher(R.string.channel_not_allowed)
                    dismiss()
                    return@launch
                } catch (e: Exception) {
                    Log.e(TAG(), e.stackTraceToString())
                    context?.toastFromMainDispatcher(e.localizedMessage.orEmpty())
                    binding.loadingProgress.isVisible = false
                    dismiss()
                    return@launch
                }
            initDownloadOptions(binding, response, positiveButton)
        }
    }

    private fun initDownloadOptions(
        binding: DialogDownloadBinding,
        streams: Streams,
        positiveButton: android.widget.Button,
    ) {
        binding.loadingProgress.isVisible = false
        binding.downloadOptionsContainer.isVisible = true
        positiveButton.isEnabled = true
        binding.videoTitle.text = streams.title

        val videoStreams =
            streams.videoStreams
                .filter {
                    !it.url.isNullOrEmpty()
                }
                .filter { !it.format.orEmpty().contains("HLS") }
                .sortedByDescending {
                    it.quality.getWhileDigit()
                }

        val audioStreams =
            streams.audioStreams
                .filter {
                    !it.url.isNullOrEmpty()
                }
                .sortedBy {
                    // prioritize main audio track types (lower role flag) over secondary/subbed
                    // ones
                    PlayerHelper.getFullAudioRoleFlags(0, it.audioTrackType.orEmpty())
                }
                .sortedByDescending {
                    it.quality.getWhileDigit()
                }

        val subtitles =
            streams.subtitles
                .filter { !it.url.isNullOrEmpty() && !it.name.isNullOrEmpty() }
                .sortedBy { it.name }

        if (subtitles.isEmpty()) binding.subtitleSpinner.isGone = true

        binding.videoSpinner.items =
            videoStreams
                .map {
                    val fileSize = Formatter.formatShortFileSize(context, it.contentLength)
                    "${it.quality} ${it.codec} ($fileSize)"
                }
                .toMutableList()
                .also {
                    it.add(0, getString(R.string.no_video))
                }

        binding.audioSpinner.items =
            audioStreams
                .map {
                    val fileSize =
                        it.contentLength
                            .takeIf { l -> l > 0 }
                            ?.let { cl -> Formatter.formatShortFileSize(context, cl) }
                    val infoStr = listOfNotNull(it.audioTrackLocale, fileSize).joinToString(", ")
                    "${it.quality} ${it.format} ($infoStr)"
                }
                .toMutableList()
                .also {
                    it.add(0, getString(R.string.no_audio))
                }

        binding.subtitleSpinner.items =
            subtitles
                .map { it.name.orEmpty() }
                .toMutableList()
                .also {
                    it.add(0, getString(R.string.no_subtitle))
                }

        restorePreviousSelections(binding, videoStreams, audioStreams, subtitles)

        onDownloadConfirm = onDownloadConfirm@{
            val videoPosition = binding.videoSpinner.selectedItemPosition - 1
            val audioPosition = binding.audioSpinner.selectedItemPosition - 1
            val subtitlePosition = binding.subtitleSpinner.selectedItemPosition - 1

            if (listOf(videoPosition, audioPosition, subtitlePosition).all { it == -1 }) {
                Toast.makeText(context, R.string.nothing_selected, Toast.LENGTH_SHORT).show()
                return@onDownloadConfirm
            }

            val videoStream = videoStreams.getOrNull(videoPosition)
            val audioStream = audioStreams.getOrNull(audioPosition)
            val subtitle = subtitles.getOrNull(subtitlePosition)

            // video/audio choices only apply to this download; the defaults live in the settings
            PreferenceHelper.putString(SUBTITLE_LANGUAGE, subtitle?.code.orEmpty())

            val downloadData =
                DownloadData(
                    videoId = videoId,
                    videoFormat = videoStream?.format,
                    videoQuality = videoStream?.quality,
                    audioFormat = audioStream?.format,
                    audioQuality = audioStream?.quality,
                    audioLanguage = audioStream?.audioTrackLocale,
                    subtitleCode = subtitle?.code,
                )
            DownloadHelper.startDownloadService(requireContext(), downloadData)

            dismiss()
        }
    }

    private fun getSel(key: String) = PreferenceHelper.getString(key, "")

    /** Preselect the defaults from the settings (video/audio) and the last used subtitle */
    private fun restorePreviousSelections(
        binding: DialogDownloadBinding,
        videoStreams: List<PipedStream>,
        audioStreams: List<PipedStream>,
        subtitles: List<Subtitle>,
    ) {
        val videoDefault =
            PreferenceHelper.getString(PreferenceKeys.DEFAULT_VIDEO_DOWNLOAD_QUALITY, "1080")
        binding.videoSpinner.selectedItemPosition =
            defaultVideoIndex(videoStreams, videoDefault) + 1

        val audioDefault =
            PreferenceHelper.getString(PreferenceKeys.DEFAULT_AUDIO_DOWNLOAD_QUALITY, "best")
        binding.audioSpinner.selectedItemPosition =
            defaultAudioIndex(audioStreams, audioDefault) + 1

        subtitles
            .indexOfFirst { it.code == getSel(SUBTITLE_LANGUAGE) }
            .takeIf { it != -1 }
            ?.let {
                binding.subtitleSpinner.selectedItemPosition = it + 1
            }
    }

    /**
     * Index of the stream to preselect, or -1 for none. [streams] are sorted by quality,
     * descending. Picks the best stream that does not exceed the wanted quality, falling back to
     * the lowest one if all are above it.
     */
    private fun defaultVideoIndex(streams: List<PipedStream>, default: String): Int {
        val maxQuality =
            default.toIntOrNull() ?: if (default == "none") return -1 else Int.MAX_VALUE
        val index = streams.indexOfFirst {
            (it.quality.getWhileDigit() ?: Int.MAX_VALUE) <= maxQuality
        }
        return if (index != -1) index else streams.lastIndex
    }

    private fun defaultAudioIndex(streams: List<PipedStream>, default: String): Int =
        when (default) {
            "none" -> -1
            "lowest" ->
                streams.indices.minByOrNull { streams[it].quality.getWhileDigit() ?: Int.MAX_VALUE }
                    ?: -1
            else -> streams.indices.firstOrNull() ?: -1
        }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)

        setFragmentResult(DOWNLOAD_DIALOG_DISMISSED_KEY, bundleOf())
    }

    companion object {
        private const val SUBTITLE_LANGUAGE = "subtitle_download_language"

        const val DOWNLOAD_DIALOG_DISMISSED_KEY = "download_dialog_dismissed_key"
    }
}
