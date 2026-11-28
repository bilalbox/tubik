package com.github.libretube.ui.fragments

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.os.Parcelable
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import androidx.annotation.StringRes
import androidx.core.os.bundleOf
import androidx.core.text.parseAsHtml
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.api.obj.Playlist
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentPlaylistBinding
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.db.obj.Series
import com.github.libretube.db.obj.SeriesOrder
import com.github.libretube.enums.PlaylistType
import com.github.libretube.enums.menuoption.PlayListSortModeBottomSheetItem
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.ceilHalf
import com.github.libretube.extensions.dpToPx
import com.github.libretube.extensions.setOnDismissListener
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.ProxyHelper
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.repo.SeriesRepository
import com.github.libretube.repo.seriesQueue
import com.github.libretube.repo.sortedBySeriesOrder
import com.github.libretube.ui.adapters.PlaylistAdapter
import com.github.libretube.ui.adapters.PlaylistItem
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.github.libretube.ui.extensions.addOnBottomReachedListener
import com.github.libretube.ui.models.CommonPlayerViewModel
import com.github.libretube.ui.models.PlaylistViewModel
import com.github.libretube.ui.sheets.BaseBottomSheet
import com.github.libretube.ui.sheets.PlaylistOptionsBottomSheet
import com.github.libretube.util.PlayingQueue
import com.github.libretube.util.TextUtils
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistFragment : DynamicLayoutManagerFragment(R.layout.fragment_playlist) {
    private var _binding: FragmentPlaylistBinding? = null
    private val binding
        get() = _binding!!

    private val args by navArgs<PlaylistFragmentArgs>()

    // general playlist information
    private lateinit var playlistId: String
    private var playlistName: String? = null
    private var playlistType = PlaylistType.PUBLIC

    // runtime variables
    private var playlistFeed = mutableListOf<StreamItem>()
    private var playlistAdapter: PlaylistAdapter? = null
    private var nextPage: String? = null
    private var isLoading = true
    /** The followed Series for this playlist, or null when it isn't followed. */
    private var series: Series? = null
    private val isFollowed
        get() = series != null

    // view models
    private val commonPlayerViewModel: CommonPlayerViewModel by activityViewModels()
    private val playlistViewModel: PlaylistViewModel by activityViewModels()
    private var selectedSortOrder = PreferenceHelper.getInt(PreferenceKeys.PLAYLIST_SORT_ORDER, 0)
        set(value) {
            PreferenceHelper.putInt(PreferenceKeys.PLAYLIST_SORT_ORDER, value)
            field = value
        }

    private val sortOptions = PlayListSortModeBottomSheetItem.entries

    private var recyclerViewState: Parcelable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playlistId = args.playlistId
        playlistType = args.playlistType
    }

    override fun setLayoutManagers(gridItems: Int) {
        _binding?.playlistRecView?.layoutManager = GridLayoutManager(context, gridItems.ceilHalf())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentPlaylistBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        binding.playlistProgress.isVisible = true

        lifecycleScope.launch(Dispatchers.IO) {
            series = runCatching { SeriesRepository.instance.store.get(playlistId) }.getOrNull()
            // opening a Series clears its new-episode dot
            if (isFollowed) SeriesRepository.instance.markOpened(playlistId)

            withContext(Dispatchers.Main) {
                if (_binding != null) updateFollowRes()
            }
        }

        commonPlayerViewModel.isMiniPlayerVisible.observe(viewLifecycleOwner) {
            binding.playlistRecView.updatePadding(bottom = if (it) 64f.dpToPx() else 0)
        }

        // manually restore the recyclerview state due to
        // https://github.com/material-components/material-components-android/issues/3473
        binding.playlistRecView.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    recyclerViewState = recyclerView.layoutManager?.onSaveInstanceState()
                }
            },
        )

        fetchPlaylist()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun updateFollowRes() {
        if (playlistType != PlaylistType.PUBLIC) return
        binding.bookmark.setIconResource(
            if (isFollowed) R.drawable.ic_bookmark else R.drawable.ic_bookmark_outlined,
        )
        binding.bookmark.text =
            getString(if (isFollowed) R.string.following_series else R.string.follow_series)
        updateSeriesOrderButton()
    }

    /** A followed Series has its own order (playlist order, newest or oldest first). */
    private fun updateSeriesOrderButton() {
        val followed = series ?: return run { binding.sortBTN.isGone = true }
        val orders = SeriesOrder.entries
        binding.sortBTN.isVisible = true
        binding.sortBTN.text = getString(followed.order.labelRes())
        binding.sortBTN.setOnClickListener {
            val titles = orders.map { getString(it.labelRes()) }
            BaseBottomSheet()
                .setSimpleItems(titles, preselectedItem = getString(followed.order.labelRes())) {
                    index ->
                    val order = orders[index]
                    val updated = series?.copy(seriesOrder = order.name) ?: return@setSimpleItems
                    series = updated
                    withContext(Dispatchers.IO) { SeriesRepository.instance.store.update(updated) }
                    if (_binding == null) return@setSimpleItems
                    updateSeriesOrderButton()
                    showPlaylistVideos()
                }
                .show(childFragmentManager)
        }
    }

    private fun fetchPlaylist() {
        lifecycleScope.launch {
            val response =
                try {
                    withContext(Dispatchers.IO) {
                        PlaylistsHelper.getPlaylist(playlistId, playlistType)
                    }
                } catch (e: Exception) {
                    Log.e(TAG(), e.toString())
                    return@launch
                }
            val binding = _binding ?: return@launch

            playlistFeed = response.relatedStreams.toMutableList()
            nextPage = response.nextpage
            playlistName = response.name
            isLoading = false

            setPlaylistThumbnail(response.thumbnailUrl)

            binding.playlistProgress.isGone = true
            binding.playlistAppBar.isVisible = true
            binding.playlistRecView.isVisible = true
            binding.playlistName.text = response.name

            binding.playlistInfo.text = getChannelAndVideoString(response, response.videos)
            binding.playlistInfo.setOnClickListener {
                NavigationHelper.navigateChannel(requireContext(), response.uploaderUrl)
            }

            binding.playlistDescription.text = response.description?.parseAsHtml()
            // hide playlist description text view if not provided
            binding.playlistDescription.isGone = response.description.orEmpty().isBlank()

            playlistAdapter =
                PlaylistAdapter(playlistId) { streamItem ->
                    startVideoItemPlayback(streamItem)
                }
            binding.playlistRecView.adapter = playlistAdapter

            // listen for playlist items to become deleted
            playlistAdapter!!.registerAdapterDataObserver(
                object : RecyclerView.AdapterDataObserver() {
                    override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                        if (positionStart == 0) {
                            ImageHelper.loadImage(
                                playlistFeed.firstOrNull()?.thumbnail.orEmpty(),
                                binding.thumbnail,
                            )
                        }

                        binding.playlistInfo.text =
                            getChannelAndVideoString(response, playlistFeed.size)
                    }
                },
            )

            binding.playlistRecView.addOnBottomReachedListener {
                if (isLoading) return@addOnBottomReachedListener

                // append more playlists to the recycler view
                if (playlistType == PlaylistType.PUBLIC) {
                    fetchNextPage()
                }
            }

            // listener for swiping to the left or right
            if (playlistType != PlaylistType.PUBLIC) {
                binding.playlistRecView.setOnDismissListener { position ->
                    removeFromPlaylist(position)
                }
            }

            showPlaylistVideos()

            playlistViewModel.searchQuery.observe(viewLifecycleOwner) {
                showPlaylistVideos()
            }

            // show playlist options
            binding.optionsMenu.setOnClickListener {
                val sheet = PlaylistOptionsBottomSheet()
                sheet.arguments =
                    bundleOf(
                        IntentData.playlistId to playlistId,
                        IntentData.playlistName to playlistName.orEmpty(),
                        IntentData.playlistType to playlistType,
                        IntentData.playlistThumbnail to response.thumbnailUrl,
                    )

                val fragmentManager = (context as BaseActivity).supportFragmentManager
                fragmentManager.setFragmentResultListener(
                    PlaylistOptionsBottomSheet.PLAYLIST_OPTIONS_REQUEST_KEY,
                    (context as BaseActivity),
                ) { _, resultBundle ->
                    val newPlaylistDescription =
                        resultBundle.getString(IntentData.playlistDescription)
                    val newPlaylistName = resultBundle.getString(IntentData.playlistName)
                    val isPlaylistToBeDeleted = resultBundle.getBoolean(IntentData.playlistTask)

                    newPlaylistDescription?.let {
                        binding.playlistDescription.text = it
                        response.description = it
                    }

                    newPlaylistName?.let {
                        binding.playlistName.text = it
                        playlistName = it
                    }

                    if (isPlaylistToBeDeleted) {
                        findNavController().popBackStack()
                        return@setFragmentResultListener
                    }
                }

                sheet.show(fragmentManager)
            }

            if (playlistFeed.isEmpty()) {
                binding.nothingHere.isVisible = true
                binding.playAll.isGone = true
            } else {
                binding.playAll.setOnClickListener {
                    startVideoItemPlayback(if (isFollowed) null else getSortedVideos().first().item)
                }
            }

            if (playlistType == PlaylistType.PUBLIC) {
                // with the Allowlist on, only playlists from Allowed Channels can be followed
                val canFollow =
                    isFollowed || SeriesRepository.instance.canFollow(response.uploaderUrl)
                binding.bookmark.isVisible = canFollow
                updateFollowRes()
                binding.bookmark.setOnClickListener {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            if (isFollowed) {
                                SeriesRepository.instance.store.delete(playlistId)
                            } else {
                                SeriesRepository.instance.follow(response.toSeries(playlistId))
                            }
                            series = SeriesRepository.instance.store.get(playlistId)
                        }
                        if (_binding == null) return@launch
                        updateFollowRes()
                        showPlaylistVideos()
                    }
                }
            } else {
                // private playlist, means shuffle is possible because all videos are received at
                // once
                if (playlistFeed.isEmpty()) {
                    binding.bookmark.isGone = true
                } else {
                    binding.bookmark.setIconResource(R.drawable.ic_shuffle)
                    binding.bookmark.text = getString(R.string.shuffle)
                    binding.bookmark.setOnClickListener {
                        val queue = playlistFeed.shuffled()
                        PlayingQueue.setStreams(queue)

                        navigateVideo(queue.firstOrNull() ?: return@setOnClickListener)
                    }
                }

                if (playlistFeed.isEmpty()) {
                    binding.sortBTN.isGone = true
                } else {
                    binding.sortBTN.isVisible = true
                    binding.sortBTN.setOnClickListener {
                        BaseBottomSheet()
                            .apply {
                                setItems(sortOptions) { index ->
                                    selectedSortOrder = index
                                    binding.sortBTN.text =
                                        sortOptions[index].title.getText(requireContext())
                                    showPlaylistVideos()
                                }
                            }
                            .show(childFragmentManager)
                    }
                }

                binding.sortBTN.text =
                    sortOptions[selectedSortOrder].title.getText(requireContext())
            }

            updateSeriesMetadata(response)
        }
    }

    private fun navigateVideo(streamItem: StreamItem) {
        NavigationHelper.navigateVideo(
            requireContext(),
            playerData =
                PlayerData(
                    streamItem.url!!.toID(),
                    playlistId = playlistId,
                    keepQueue = true,
                ),
            ownerChannelId = streamItem.uploaderUrl,
        )
    }

    private fun startVideoItemPlayback(streamItem: StreamItem?) {
        if (playlistFeed.isEmpty()) return

        val sortedStreams = getSortedVideos().map { it.item }
        if (!isFollowed) {
            PlayingQueue.setStreams(sortedStreams)
            navigateVideo(streamItem ?: sortedStreams.first())
            return
        }

        // a Series continues with the next unwatched video in its order
        lifecycleScope.launch {
            val watched =
                withContext(Dispatchers.IO) {
                    val positions =
                        DatabaseHelper.getWatchPositions(
                            sortedStreams.mapNotNull { it.url?.toID() },
                        )
                    sortedStreams
                        .mapNotNull { stream ->
                            val id = stream.url?.toID() ?: return@mapNotNull null
                            val position = positions[id] ?: return@mapNotNull null
                            id.takeIf {
                                DatabaseHelper.isVideoWatched(position, stream.duration ?: 0)
                            }
                        }
                        .toSet()
                }
            val queue = seriesQueue(sortedStreams, streamItem) { it.url?.toID() in watched }
            if (queue.isEmpty() || _binding == null) return@launch
            PlayingQueue.setStreams(queue)
            navigateVideo(queue.first())
        }
    }

    /** If the playlist is a followed Series, keep its name, thumbnail and count current. */
    private suspend fun updateSeriesMetadata(playlist: Playlist) {
        withContext(Dispatchers.IO) {
            val followed = SeriesRepository.instance.store.get(playlistId) ?: return@withContext
            val updated =
                followed.copy(
                    playlistName = playlist.name,
                    thumbnailUrl = playlist.thumbnailUrl?.let { ProxyHelper.unwrapUrl(it) },
                    videos = playlist.videos,
                )
            if (updated != followed) SeriesRepository.instance.store.update(updated)
        }
    }

    private fun getSortedVideos(): List<PlaylistItem> {
        // in addition to sorting, we need to make sure that the original index of the item
        // is still known. We solve this by wrapping the StreamItems into PlaylistItems that contain
        // an additional index attribute.
        val items = playlistFeed.mapIndexed { index, item -> PlaylistItem(item, index) }

        series?.let { followed ->
            return items.sortedBySeriesOrder(followed.order) { it.item.uploaded }
        }

        return when {
            selectedSortOrder in listOf(0, 1) || playlistType == PlaylistType.PUBLIC -> {
                items
            }

            selectedSortOrder in listOf(2, 3) -> {
                items.sortedBy { it.item.duration }
            }

            selectedSortOrder in listOf(4, 5) -> {
                items.sortedBy { it.item.title }
            }

            else -> throw IllegalArgumentException()
        }.let {
            if (selectedSortOrder % 2 == 0) it else it.reversed()
        }
    }

    private fun showPlaylistVideos() {
        var videos = getSortedVideos()

        val query = playlistViewModel.searchQuery.value
        if (!query.isNullOrEmpty()) {
            videos = videos.filter {
                it.item.title.orEmpty().contains(query, ignoreCase = true) ||
                    it.item.uploaderName.orEmpty().contains(query, ignoreCase = true)
            }
        }

        playlistAdapter?.submitList(videos)

        updatePlaylistDuration(videos)
    }

    @SuppressLint("StringFormatInvalid")
    private fun removeFromPlaylist(sortedFeedPosition: Int) {
        val playlistAdapter = playlistAdapter ?: return

        val (video, originalPlaylistPosition) = playlistAdapter.currentList[sortedFeedPosition]

        val updatedList = playlistAdapter.currentList.toMutableList()
        updatedList.removeAt(sortedFeedPosition)
        val fixedList = fixItemIndices(updatedList, originalPlaylistPosition, -1)
        playlistAdapter.submitList(fixedList)

        // try to remove the video from the playlist and show an undo snackbar if successful
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                PlaylistsHelper.removeFromPlaylist(
                    playlistId,
                    video.url!!.toID(),
                    originalPlaylistPosition,
                )

                val shortTitle = TextUtils.limitTextToLength(video.title.orEmpty(), 50)
                val snackBarText =
                    getString(
                        R.string.successfully_removed_from_playlist,
                        shortTitle,
                    )

                withContext(Dispatchers.Main) {
                    Snackbar.make(binding.root, snackBarText, Snackbar.LENGTH_LONG)
                        .setTextMaxLines(3)
                        .setAction(R.string.undo) {
                            reAddToPlaylist(
                                video,
                                sortedFeedPosition,
                                originalPlaylistPosition,
                            )
                        }
                        .show()
                    updateInfo(updatedList)
                }
            } catch (e: Exception) {
                Log.e(TAG(), e.toString())
                context?.toastFromMainDispatcher(R.string.unknown_error)
            }
        }
    }

    private fun updateInfo(updatedList: List<PlaylistItem>) {
        val playlistCount = updatedList.size
        binding.playlistInfo.text =
            getChannelAndVideoString(
                Playlist(name = playlistName, videos = playlistCount),
                playlistCount,
            )
        updatePlaylistDuration(updatedList)
        setPlaylistThumbnail(updatedList.firstOrNull()?.item?.thumbnail)
    }

    private fun reAddToPlaylist(
        streamItem: StreamItem,
        sortedFeedPosition: Int,
        originalFeedPosition: Int,
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                PlaylistsHelper.addToPlaylist(playlistId, streamItem)

                val playlistAdapter = playlistAdapter ?: return@launch
                val updatedList = playlistAdapter.currentList.toMutableList()
                updatedList.add(sortedFeedPosition, PlaylistItem(streamItem, originalFeedPosition))
                val fixedList = fixItemIndices(updatedList, originalFeedPosition, +1)

                withContext(Dispatchers.Main) {
                    playlistAdapter.submitList(fixedList)
                    updateInfo(fixedList)
                }
            } catch (e: Exception) {
                Log.e(TAG(), e.toString())
                context?.toastFromMainDispatcher(R.string.unknown_error)
            }
        }
    }

    /**
     * After removing or adding a video to a playlist, the original positions of all videos after
     * the removed/added one change by one.
     *
     * E.g., if you remove the video at index 7, all videos after it move one to the left (8 -> 7, 9
     * -> 8, ...). In this example, the offset would be 1.
     *
     * I.e., this method adds the given offset to all videos with an originalPlaylistIndex >
     * modifiedPosition.
     */
    private fun fixItemIndices(
        items: List<PlaylistItem>,
        modifiedPosition: Int,
        offset: Int,
    ): List<PlaylistItem> {
        return items.map {
            if (it.originalPlaylistIndex > modifiedPosition) {
                it.copy(originalPlaylistIndex = it.originalPlaylistIndex + offset)
            } else {
                it
            }
        }
    }

    @SuppressLint("StringFormatInvalid", "StringFormatMatches")
    private fun getChannelAndVideoString(playlist: Playlist, count: Int): String {
        if (count < 0) return playlist.uploader.orEmpty()
        if (playlist.uploader == null) return getString(R.string.videoCount, count)

        return getString(R.string.uploaderAndVideoCount, playlist.uploader, count)
    }

    private fun fetchNextPage() {
        if (nextPage == null || isLoading) return
        isLoading = true

        lifecycleScope.launch {
            val response =
                try {
                    withContext(Dispatchers.IO) {
                        // load locally stored playlists with the auth api
                        MediaServiceRepository.instance.getPlaylistNextPage(playlistId, nextPage!!)
                    }
                } catch (e: Exception) {
                    context?.toastFromMainDispatcher(e.localizedMessage.orEmpty())
                    Log.e(TAG(), e.toString())
                    return@launch
                }

            nextPage = response.nextpage
            val currentList = playlistAdapter?.currentList.orEmpty()
            val newList =
                currentList +
                    response.relatedStreams.mapIndexed { index, item ->
                        PlaylistItem(item, currentList.size + index)
                    }
            playlistAdapter?.submitList(newList)
            updatePlaylistDuration(newList)
            isLoading = false
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updatePlaylistDuration(updatedList: List<PlaylistItem>) {
        val totalDuration = updatedList.sumOf { it.item.duration ?: 0 }
        binding.playlistDuration.text =
            DateUtils.formatElapsedTime(totalDuration) + if (nextPage != null) "+" else ""
    }

    // Update the Cover/Thumbnail of the playlist if the first video was removed
    private fun setPlaylistThumbnail(thumbnailUrl: String?) {
        if (!thumbnailUrl.isNullOrEmpty()) {
            ImageHelper.loadImage(thumbnailUrl, binding.thumbnail)
        } else {
            binding.thumbnail.setImageResource(R.drawable.ic_empty_playlist)
            binding.thumbnail.setPadding(64f.dpToPx())
            binding.thumbnail.setBackgroundColor(com.google.android.material.R.attr.colorSurface)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // manually restore the recyclerview state due to
        // https://github.com/material-components/material-components-android/issues/3473
        binding.playlistRecView.layoutManager?.onRestoreInstanceState(recyclerViewState)
    }
}

@StringRes
private fun SeriesOrder.labelRes(): Int =
    when (this) {
        SeriesOrder.PLAYLIST -> R.string.series_order_playlist
        SeriesOrder.NEWEST_FIRST -> R.string.series_order_newest
        SeriesOrder.OLDEST_FIRST -> R.string.series_order_oldest
    }
