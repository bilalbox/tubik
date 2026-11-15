package com.github.libretube.ui.fragments

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.View
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.motion.widget.TransitionAdapter
import androidx.core.math.MathUtils.clamp
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.github.libretube.R
import com.github.libretube.api.JsonHelper
import com.github.libretube.api.obj.ChapterSegment
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.FragmentAudioPlayerBinding
import com.github.libretube.enums.PlayerCommand
import com.github.libretube.extensions.navigateVideo
import com.github.libretube.extensions.normalize
import com.github.libretube.extensions.seekBy
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.togglePlayPauseState
import com.github.libretube.extensions.updateIfChanged
import com.github.libretube.helpers.AudioHelper
import com.github.libretube.helpers.BackgroundHelper
import com.github.libretube.helpers.ClipboardHelper
import com.github.libretube.helpers.HandlerDelayedTaskScheduler
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PlaybackTimelineScheduler
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.player.PlaybackSession
import com.github.libretube.services.AbstractPlayerService
import com.github.libretube.services.OfflinePlayerService
import com.github.libretube.services.OnlinePlayerService
import com.github.libretube.ui.activities.AbstractPlayerHostActivity
import com.github.libretube.ui.extensions.getSystemInsets
import com.github.libretube.ui.extensions.setOnBackPressed
import com.github.libretube.ui.interfaces.AudioPlayerOptions
import com.github.libretube.ui.listeners.AudioPlayerThumbnailListener
import com.github.libretube.ui.models.ChaptersViewModel
import com.github.libretube.ui.models.CommonPlayerViewModel
import com.github.libretube.ui.sheets.ChaptersBottomSheet
import com.github.libretube.ui.sheets.PlaybackOptionsSheet
import com.github.libretube.ui.sheets.PlayingQueueSheet
import com.github.libretube.ui.sheets.SleepTimerSheet
import com.github.libretube.ui.sheets.VideoOptionsBottomSheet
import com.github.libretube.util.DataSaverMode
import com.github.libretube.util.PlayingQueue
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch

@UnstableApi
class AudioPlayerFragment : Fragment(R.layout.fragment_audio_player), AudioPlayerOptions {
    private var _binding: FragmentAudioPlayerBinding? = null
    val binding
        get() = _binding!!

    private lateinit var audioHelper: AudioHelper
    private val activity
        get() = context as AbstractPlayerHostActivity

    private val viewModel: CommonPlayerViewModel by activityViewModels()
    private val chaptersModel: ChaptersViewModel by activityViewModels()

    // for the transition
    private var transitionStartId = 0
    private var transitionEndId = 0

    private var handler = Handler(Looper.getMainLooper())
    private val timelineScheduler = PlaybackTimelineScheduler(HandlerDelayedTaskScheduler(handler))
    private var playerListener: Player.Listener? = null
    private var isPaused = !PlayerHelper.playAutomatically

    var isOffline: Boolean = false
        private set

    private var playerController: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        audioHelper = AudioHelper(requireContext())

        isOffline = requireArguments().getBoolean(IntentData.offlinePlayer)

        BackgroundHelper.startMediaService(
            requireContext(),
            if (isOffline) OfflinePlayerService::class.java else OnlinePlayerService::class.java,
        ) {
            if (_binding == null) {
                it.sendCustomCommand(AbstractPlayerService.stopServiceCommand, Bundle.EMPTY)
                it.release()
                return@startMediaService
            }

            playerController = it
            handleServiceConnection()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentAudioPlayerBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        // manually apply additional padding for edge-to-edge compatibility
        activity.getSystemInsets()?.let { systemBars ->
            with(binding.audioPlayerMain) {
                setPadding(
                    paddingLeft,
                    paddingTop + systemBars.top,
                    paddingRight,
                    paddingBottom + systemBars.bottom,
                )
            }
        }

        initializeTransitionLayout()

        // select the title TV in order for it to automatically scroll
        binding.title.isSelected = true
        binding.uploader.isSelected = true

        binding.title.setOnLongClickListener {
            ClipboardHelper.save(requireContext(), text = binding.title.text.toString())
            true
        }

        binding.minimizePlayer.setOnClickListener {
            activity.minimizePlayerContainerLayout()
            binding.playerMotionLayout.transitionToEnd()
        }

        binding.autoPlay.isChecked = PlayerHelper.autoPlayEnabled
        binding.autoPlay.setOnCheckedChangeListener { _, isChecked ->
            PlayerHelper.autoPlayEnabled = isChecked
        }

        binding.prev.setOnClickListener {
            playerController?.navigateVideo(PlayingQueue.getPrev() ?: return@setOnClickListener)
        }

        binding.next.setOnClickListener {
            playerController?.navigateVideo(PlayingQueue.getNext() ?: return@setOnClickListener)
        }

        binding.rewindBTN.setOnClickListener {
            playerController?.seekBy(-PlayerHelper.seekIncrement)
        }
        binding.forwardBTN.setOnClickListener {
            playerController?.seekBy(PlayerHelper.seekIncrement)
        }

        childFragmentManager.setFragmentResultListener(
            PlayingQueueSheet.PLAYING_QUEUE_REQUEST_KEY,
            viewLifecycleOwner,
        ) { _, args ->
            playerController?.navigateVideo(
                args.getString(IntentData.videoId) ?: return@setFragmentResultListener,
            )
        }
        binding.openQueue.setOnClickListener {
            PlayingQueueSheet().show(childFragmentManager)
        }

        binding.playbackOptions.setOnClickListener {
            playerController?.let {
                PlaybackOptionsSheet(it).show(childFragmentManager)
            }
        }

        binding.sleepTimer.setOnClickListener {
            SleepTimerSheet().show(childFragmentManager)
        }

        binding.openVideo.setOnClickListener {
            val currentId = PlayingQueue.getCurrent()?.url?.toID()
            switchToVideoMode(currentId ?: return@setOnClickListener)
        }

        childFragmentManager.setFragmentResultListener(
            ChaptersBottomSheet.SEEK_TO_POSITION_REQUEST_KEY,
            viewLifecycleOwner,
        ) { _, bundle ->
            playerController?.seekTo(bundle.getLong(IntentData.currentPosition))
        }

        chaptersModel.chaptersLiveData.observe(viewLifecycleOwner) { chapters ->
            _binding?.openChapters?.isVisible = !chapters.isNullOrEmpty()
            updateChapterIndex()
        }

        binding.openChapters.setOnClickListener {
            ChaptersBottomSheet()
                .apply {
                    arguments =
                        bundleOf(
                            IntentData.duration to playerController?.duration?.div(1000),
                        )
                }
                .show(childFragmentManager)
        }

        binding.miniPlayerClose.setOnClickListener {
            killFragment(true)
        }

        val listener = AudioPlayerThumbnailListener(requireContext(), this)
        binding.thumbnail.setOnTouchListener(listener)

        binding.playPause.setOnClickListener {
            playerController?.togglePlayPauseState()
        }

        binding.miniPlayerPause.setOnClickListener {
            playerController?.togglePlayPauseState()
        }

        binding.showMore.setOnClickListener {
            onLongTap()
        }

        // update the currently shown volume
        binding.volumeProgressBar.let { bar ->
            bar.progress = (audioHelper.deviceVolume * bar.max).toInt().coerceIn(0, bar.max)
        }

        if (!PlayerHelper.playAutomatically) updatePlayPauseButton()

        updateChapterIndex()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                PlaybackSession.instance.state.collect { sessionState ->
                    binding.musicRemovalOverloadIndicator.isVisible = sessionState.isOverloadMuted
                }
            }
        }

        val onBackPressedCallback =
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    binding.audioPlayerContainer.isClickable = false
                    binding.playerMotionLayout.transitionToEnd()
                    activity.minimizePlayerContainerLayout()
                    activity.requestOrientationChange()
                }

                override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                    binding.playerMotionLayout.progress = backEvent.progress
                }

                override fun handleOnBackCancelled() {
                    binding.playerMotionLayout.transitionToStart()
                }
            }
        setOnBackPressed(onBackPressedCallback)

        viewModel.isMiniPlayerVisible.observe(viewLifecycleOwner) { isMiniPlayerVisible ->
            // re-add the callback on top of the back pressed dispatcher listeners stack,
            // so that it's the first one to become called while the full player is visible
            if (!isMiniPlayerVisible) {
                onBackPressedCallback.remove()
                setOnBackPressed(onBackPressedCallback)
            }

            // if the player is minimized, the fragment behind the player should handle the event
            onBackPressedCallback.isEnabled = isMiniPlayerVisible != true
        }
    }

    fun switchToVideoMode(videoId: String) {
        playerController?.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand,
            bundleOf(PlayerCommand.TOGGLE_AUDIO_ONLY_MODE.name to false),
        )

        killFragment(false)

        NavigationHelper.openVideoPlayerFragment(
            context = requireContext(),
            playerData =
                PlayerData(
                    videoId = videoId,
                    isOffline = isOffline,
                ),
            alreadyStarted = true,
        )
    }

    private fun cleanupPlayerCallbacks() {
        timelineScheduler.cancelAll()
        handler.removeCallbacksAndMessages(null)
        playerListener?.let { playerController?.removeListener(it) }
        playerListener = null
    }

    private fun killFragment(stopPlayer: Boolean) {
        viewModel.isMiniPlayerVisible.value = false
        cleanupPlayerCallbacks()

        if (stopPlayer)
            playerController?.sendCustomCommand(
                AbstractPlayerService.stopServiceCommand,
                Bundle.EMPTY,
            )
        playerController?.release()
        playerController = null

        viewModel.isFullscreen.value = false
        binding.playerMotionLayout.transitionToEnd()
        activity.supportFragmentManager.commit {
            remove(this@AudioPlayerFragment)
        }
    }

    fun playNextVideo(videoId: String) {
        playerController?.navigateVideo(videoId)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initializeTransitionLayout() {
        activity.setPlayerContainerProgress(0f)

        binding.playerMotionLayout.addTransitionListener(
            object : TransitionAdapter() {
                override fun onTransitionChange(
                    motionLayout: MotionLayout?,
                    startId: Int,
                    endId: Int,
                    progress: Float,
                ) {
                    activity.setPlayerContainerProgress(progress.absoluteValue)
                    transitionEndId = endId
                    transitionStartId = startId
                }

                override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {
                    if (currentId == transitionEndId) {
                        viewModel.isMiniPlayerVisible.value = true
                        activity.minimizePlayerContainerLayout()
                    } else if (currentId == transitionStartId) {
                        viewModel.isMiniPlayerVisible.value = false
                        activity.maximizePlayerContainerLayout()
                    }
                }
            },
        )

        if (arguments?.getBoolean(IntentData.minimizeByDefault, false) != true) {
            binding.playerMotionLayout.progress = 1f
            binding.playerMotionLayout.transitionToStart()
        } else {
            binding.playerMotionLayout.progress = 0f
            binding.playerMotionLayout.transitionToEnd()
        }
    }

    /** Load the information from a new stream into the UI */
    private fun updateStreamInfo(metadata: MediaMetadata) {
        val binding = _binding ?: return

        binding.title.text = metadata.title
        binding.miniPlayerTitle.text = metadata.title

        binding.uploader.text = metadata.artist
        binding.uploader.setOnClickListener {
            val uploaderId = metadata.composer?.toString() ?: return@setOnClickListener
            NavigationHelper.navigateChannel(requireContext(), uploaderId)
        }

        metadata.artworkUri?.let { updateThumbnailAsync(it) }

        initializeSeekBar()
    }

    private fun updateThumbnailAsync(thumbnailUri: Uri) {
        if (DataSaverMode.isEnabled(requireContext()) && !isOffline) {
            binding.progress.isVisible = false
            binding.thumbnail.setImageResource(R.drawable.ic_launcher_monochrome)
            val primaryColor =
                ThemeHelper.getThemeColor(
                    requireContext(),
                    androidx.appcompat.R.attr.colorPrimary,
                )
            binding.thumbnail.setColorFilter(primaryColor)
            return
        }

        binding.progress.isVisible = true
        binding.thumbnail.isGone = true
        // reset color filter if data saver mode got toggled or conditions for it changed
        binding.thumbnail.setColorFilter(Color.TRANSPARENT)

        lifecycleScope.launch {
            val binding = _binding ?: return@launch
            val bitmap = ImageHelper.getImage(requireContext(), thumbnailUri)
            binding.thumbnail.setImageBitmap(bitmap)
            binding.miniPlayerThumbnail.setImageBitmap(bitmap)
            binding.thumbnail.isVisible = true
            binding.progress.isGone = true
        }
    }

    private fun initializeSeekBar() {
        binding.timeBar.clearOnChangeListeners()
        binding.timeBar.addOnChangeListener { _, value, fromUser ->
            if (fromUser) playerController?.seekTo(value.toLong() * 1000)
        }
        updateSeekBar()
    }

    private fun isSeekBarUpdateActive(): Boolean {
        val binding = _binding ?: return false
        return isAdded &&
            viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            isVisible &&
            binding.root.isShown
    }

    /** Update the position, duration and text views belonging to the seek bar */
    private fun updateSeekBar() {
        timelineScheduler.cancel(PlaybackTimelineScheduler.POSITION_TOKEN)
        val binding = _binding ?: return
        val controller = playerController ?: return
        val duration =
            controller.duration.takeIf { it > 0 }
                ?: run {
                    // if there's no duration available, clear everything
                    binding.timeBar.value = 0f
                    binding.duration.text = ""
                    binding.currentPosition.text = ""
                    return
                }
        val currentPositionMs = controller.currentPosition.coerceAtLeast(0L)

        // set the text for the indicators
        binding.duration.text = DateUtils.formatElapsedTime(duration / 1000)
        binding.currentPosition.text =
            DateUtils.formatElapsedTime(
                currentPositionMs / 1000,
            )

        // update the time bar current value and maximum value
        binding.timeBar.valueTo = (duration / 1000).toFloat()
        binding.timeBar.value =
            clamp(
                currentPositionMs.toFloat() / 1000,
                binding.timeBar.valueFrom,
                binding.timeBar.valueTo,
            )

        timelineScheduler.evaluateAndSchedulePosition(
            currentPositionMs = currentPositionMs,
            playbackSpeed = controller.playbackParameters.speed,
            isPlaying = controller.isPlaying,
            isViewActive = isSeekBarUpdateActive(),
            onReschedule = this::updateSeekBar,
        )
    }

    private fun updatePlayPauseButton() {
        playerController?.let {
            val binding = _binding ?: return

            val iconRes = PlayerHelper.getPlayPauseActionIcon(it)
            binding.playPause.setIconResource(iconRes)
            binding.miniPlayerPause.setImageResource(iconRes)
        }
    }

    private fun handleServiceConnection() {
        playerListener?.let { playerController?.removeListener(it) }
        val listener =
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    super.onIsPlayingChanged(isPlaying)

                    updatePlayPauseButton()
                    isPaused = !isPlaying
                    updateSeekBar()
                    updateChapterIndex()
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    super.onPositionDiscontinuity(oldPosition, newPosition, reason)
                    updateSeekBar()
                    updateChapterIndex()
                }

                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    super.onPlaybackParametersChanged(playbackParameters)
                    updateSeekBar()
                    updateChapterIndex()
                }

                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    super.onTimelineChanged(timeline, reason)
                    updateSeekBar()
                    updateChapterIndex()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    super.onPlaybackStateChanged(playbackState)
                    updatePlayPauseButton()
                    updateSeekBar()
                    updateChapterIndex()
                }

                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    super.onMediaMetadataChanged(mediaMetadata)

                    updateStreamInfo(mediaMetadata)
                    // JSON-encode as work-around for https://github.com/androidx/media/issues/564
                    val chapters: List<ChapterSegment>? =
                        mediaMetadata.extras?.getString(IntentData.chapters)?.let {
                            JsonHelper.json.decodeFromString(it)
                        }
                    chaptersModel.chaptersLiveData.value = chapters
                    updateChapterIndex()
                }
            }
        playerListener = listener
        playerController?.addListener(listener)
        playerController?.mediaMetadata?.let { updateStreamInfo(it) }
        // JSON-encode as work-around for https://github.com/androidx/media/issues/564
        chaptersModel.chaptersLiveData.value =
            playerController?.mediaMetadata?.extras?.getString(IntentData.chapters)?.let {
                JsonHelper.json.decodeFromString(it)
            }

        updatePlayPauseButton()
        initializeSeekBar()
        updateChapterIndex()
    }

    override fun onStart() {
        super.onStart()
        if (playerController != null && _binding != null) {
            updateSeekBar()
            updateChapterIndex()
        }
    }

    override fun onResume() {
        super.onResume()
        if (playerController != null && _binding != null) {
            updateSeekBar()
            updateChapterIndex()
        }
    }

    override fun onStop() {
        timelineScheduler.cancel(PlaybackTimelineScheduler.POSITION_TOKEN)
        timelineScheduler.cancel(PlaybackTimelineScheduler.CHAPTER_TOKEN)
        super.onStop()
    }

    override fun onDestroyView() {
        cleanupPlayerCallbacks()
        super.onDestroyView()
        _binding = null
    }

    override fun onSingleTap() {
        playerController?.togglePlayPauseState()
    }

    override fun onLongTap() {
        val current = PlayingQueue.getCurrent() ?: return
        VideoOptionsBottomSheet()
            .apply {
                arguments = bundleOf(IntentData.streamItem to current)
            }
            .show(childFragmentManager)
    }

    override fun onSwipe(distanceY: Float) {
        if (!PlayerHelper.swipeGestureEnabled) return

        binding.volumeControls.isVisible = true
        updateVolume(distanceY)
    }

    override fun onSwipeEnd() {
        if (!PlayerHelper.swipeGestureEnabled) return

        binding.volumeControls.isGone = true
    }

    private fun updateVolume(distance: Float) {
        val bar = binding.volumeProgressBar
        binding.volumeControls.apply {
            if (isGone) {
                isVisible = true
                // Volume could be changed using other mediums, sync progress
                // bar with new value.
                bar.progress = (audioHelper.deviceVolume * bar.max).toInt().coerceIn(0, bar.max)
            }
        }

        if (bar.progress == 0) {
            binding.volumeImageView.setImageResource(
                when {
                    distance > 0 -> R.drawable.ic_volume_up
                    else -> R.drawable.ic_volume_off
                },
            )
        }
        bar.incrementProgressBy(distance.toInt() / 3)
        audioHelper.deviceVolume = bar.progress.toFloat() / bar.max

        binding.volumeTextView.text = "${bar.progress.normalize(0, bar.max, 0, 100)}"
    }

    private fun updateChapterIndex() {
        if (_binding == null) {
            timelineScheduler.cancel(PlaybackTimelineScheduler.CHAPTER_TOKEN)
            return
        }
        val controller =
            playerController
                ?: run {
                    timelineScheduler.cancel(PlaybackTimelineScheduler.CHAPTER_TOKEN)
                    return
                }

        val currentIndex =
            timelineScheduler.evaluateAndScheduleChapters(
                currentPositionMs = controller.currentPosition,
                playbackSpeed = controller.playbackParameters.speed,
                isPlaying = controller.isPlaying,
                isViewAttached = _binding != null,
                chapters = chaptersModel.chapters,
                onReschedule = this::updateChapterIndex,
            )
        chaptersModel.currentChapterIndex.updateIfChanged(currentIndex ?: return)
    }
}
