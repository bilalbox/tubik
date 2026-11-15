package com.github.libretube.ui.fragments

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentSubscriptionsBinding
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.obj.SelectableOption
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.repo.FeedRefresh
import com.github.libretube.repo.UserDataRepositoryHelper
import com.github.libretube.repo.shouldFetchFeedOnOpen
import com.github.libretube.ui.adapters.VideoCardsAdapter
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.github.libretube.ui.models.SubscriptionsViewModel
import com.github.libretube.ui.sheets.ChannelGroupsSheet
import com.github.libretube.ui.sheets.FilterSortBottomSheet
import com.github.libretube.ui.sheets.FilterSortBottomSheet.Companion.FILTER_SORT_REQUEST_KEY
import com.github.libretube.ui.sheets.SubscriptionsBottomSheet
import com.github.libretube.util.PlayingQueue
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SubscriptionsFragment : DynamicLayoutManagerFragment(R.layout.fragment_subscriptions) {
    private var _binding: FragmentSubscriptionsBinding? = null
    private val binding
        get() = _binding!!

    private val viewModel: SubscriptionsViewModel by activityViewModels()

    private var selectedFilterGroup
        set(value) = PreferenceHelper.putInt(PreferenceKeys.SELECTED_CHANNEL_GROUP, value)
        get() = PreferenceHelper.getInt(PreferenceKeys.SELECTED_CHANNEL_GROUP, FILTER_GROUP_ALL)

    private var isAppBarFullyExpanded = true
    private var isUpdatingChannelGroups = false

    private var feedAdapter = VideoCardsAdapter()
    private var selectedSortOrder = PreferenceHelper.getInt(PreferenceKeys.FEED_SORT_ORDER, 0)
        set(value) {
            PreferenceHelper.putInt(PreferenceKeys.FEED_SORT_ORDER, value)
            field = value
        }

    private var hideWatched =
        PreferenceHelper.getBoolean(PreferenceKeys.HIDE_WATCHED_FROM_FEED, false)
        set(value) {
            PreferenceHelper.putBoolean(PreferenceKeys.HIDE_WATCHED_FROM_FEED, value)
            field = value
        }

    private var showUpcoming =
        PreferenceHelper.getBoolean(PreferenceKeys.SHOW_UPCOMING_IN_FEED, true)
        set(value) {
            PreferenceHelper.putBoolean(PreferenceKeys.SHOW_UPCOMING_IN_FEED, value)
            field = value
        }

    override fun setLayoutManagers(gridItems: Int) {
        _binding?.subFeed?.layoutManager = GridLayoutManager(context, gridItems)
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentSubscriptionsBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        setupSortAndFilter()

        binding.subFeed.adapter = feedAdapter

        // Check if the AppBarLayout is fully expanded
        binding.subscriptionsAppBar.addOnOffsetChangedListener { _, verticalOffset ->
            isAppBarFullyExpanded = verticalOffset == 0
        }

        // Determine if the child can scroll up
        binding.subRefresh.setOnChildScrollUpCallback { _, _ ->
            !isAppBarFullyExpanded || binding.subFeed.canScrollVertically(-1)
        }

        binding.subRefresh.isEnabled = true

        val subscriptionsChanged = viewModel.hasPendingSubscriptionChanges()
        if (viewModel.subscriptions.value == null || subscriptionsChanged) {
            viewModel.fetchSubscriptions(requireContext())
        }
        val needsRefresh = viewModel.needsSubscriptionRefresh()
        if (viewModel.isFeedFetchInFlight && !needsRefresh) {
            binding.subProgress.isVisible = viewModel.videoFeed.value.isNullOrEmpty()
        } else if (shouldFetchFeedOnOpen(viewModel.videoFeed.value, needsRefresh)) {
            binding.subProgress.isVisible = viewModel.videoFeed.value.isNullOrEmpty()
            viewModel.fetchFeed(requireContext(), FeedRefresh.Automatically)
        } else {
            binding.subProgress.isGone = true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                SubscriptionHelper.subscriptionsRevision.collect { revision ->
                    if (revision != 0L && viewModel.needsSubscriptionRefresh(revision)) {
                        viewModel.fetchSubscriptions(requireContext())
                        viewModel.fetchFeed(requireContext(), FeedRefresh.Automatically)
                    }
                }
            }
        }

        viewModel.feedError.observe(viewLifecycleOwner) { hasError ->
            if (!hasError) return@observe
            binding.subProgress.isGone = true
            binding.subRefresh.isRefreshing = false
            if (viewModel.videoFeed.value.isNullOrEmpty()) {
                binding.subFeed.isGone = true
                binding.emptyFeed.isVisible = true
            }
        }

        // only restore the previous state (i.e. scroll position) the first time the feed is shown
        // any other feed updates are caused by manual refreshing and thus should reset the scroll
        // position to zero
        var alreadyShowedFeedOnce = false
        viewModel.videoFeed.observe(viewLifecycleOwner) { feed ->
            if (feed != null) {
                lifecycleScope.launch {
                    showFeed(!alreadyShowedFeedOnce)
                }
                alreadyShowedFeedOnce = true
            }

            feed
                ?.firstOrNull { !it.isUpcoming }
                ?.uploaded
                ?.let {
                    PreferenceHelper.updateLastFeedWatchedTime(it, true)
                }

            // ungrouped chip is hidden if the user doesn't use channel groups
            binding.chipUngrouped.isVisible =
                !viewModel.groups.value.isNullOrEmpty() &&
                    filterUngroupedStreamItems(feed.orEmpty()).isNotEmpty()
        }

        viewModel.feedProgress.observe(viewLifecycleOwner) { progress ->
            if (progress == null || progress.currentProgress == progress.total) {
                // the automatic animation by setting animateLayoutChanges looks very buggy
                // so we display a custom animation when the feed finished loading
                // https://stackoverflow.com/questions/37704046/animatelayoutchanges-buggy-when-changing-visibility-to-gone
                binding.feedProgressContainer
                    .animate()
                    .alpha(0.5f)
                    .scaleY(0.5f)
                    .withEndAction {
                        val binding = _binding ?: return@withEndAction
                        binding.feedProgressContainer.isGone = true
                        binding.feedProgressContainer.scaleY = 1f
                        binding.feedProgressContainer.alpha = 1f
                    }
                    .setDuration(200)
                    .start()
            } else {
                binding.feedProgressContainer.isVisible = true
                binding.feedProgressText.text = "${progress.currentProgress}/${progress.total}"
                binding.feedProgressBar.max = progress.total
                binding.feedProgressBar.progress = progress.currentProgress
            }
        }

        binding.subRefresh.setOnRefreshListener {
            viewModel.fetchFeed(
                requireContext(),
                when (selectedFilterGroup) {
                    FILTER_GROUP_ALL -> FeedRefresh.All
                    FILTER_GROUP_UNGROUPED ->
                        viewModel.subscriptions.value?.let { subscriptions ->
                            FeedRefresh.OnlySome(subscriptions.map { it.url.toID() })
                        } ?: FeedRefresh.All // fallback if subscriptions weren't loaded yet
                    else ->
                        FeedRefresh.OnlySome(
                            viewModel.groups.value
                                ?.getOrNull(selectedFilterGroup)
                                ?.channels
                                .orEmpty(),
                        )
                },
            )
        }

        binding.toggleSubs.isVisible = true

        binding.toggleSubs.setOnClickListener {
            SubscriptionsBottomSheet().show(childFragmentManager)
        }

        binding.channelGroups.setOnCheckedStateChangeListener { group, _ ->
            if (isUpdatingChannelGroups) return@setOnCheckedStateChangeListener

            val groupChipIds =
                group.children
                    .map { it.id }
                    .filter { it != binding.chipAll.id && it != binding.chipUngrouped.id }
                    .toList()
            selectedFilterGroup =
                resolveSelectedFilterGroup(
                    checkedChipId = group.checkedChipId,
                    allChipId = binding.chipAll.id,
                    ungroupedChipId = binding.chipUngrouped.id,
                    groupChipIds = groupChipIds,
                )

            lifecycleScope.launch {
                showFeed(restoreScrollState = false)
            }
        }

        viewModel.groups.observe(viewLifecycleOwner) {
            lifecycleScope.launch {
                initChannelGroups()
                if (viewModel.videoFeed.value != null) {
                    showFeed()
                }
            }
        }

        binding.editGroups.setOnClickListener {
            ChannelGroupsSheet().show(childFragmentManager, null)
        }

        binding.subFeed.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    viewModel.subFeedRecyclerViewState =
                        recyclerView.layoutManager?.onSaveInstanceState()?.takeIf {
                            recyclerView.computeVerticalScrollOffset() != 0
                        }
                }
            },
        )

        lifecycleScope.launch(Dispatchers.IO) {
            val groups =
                try {
                    UserDataRepositoryHelper.userDataRepository.getSubscriptionGroups()
                } catch (e: Exception) {
                    context?.toastFromMainDispatcher(e.message.orEmpty())
                    return@launch
                }
            viewModel.groups.postValue(groups)
        }
    }

    private fun setupSortAndFilter() {
        binding.filterSort.setOnClickListener {
            childFragmentManager.setFragmentResultListener(
                FILTER_SORT_REQUEST_KEY,
                viewLifecycleOwner,
            ) { _, resultBundle ->
                selectedSortOrder = resultBundle.getInt(IntentData.sortOptions)
                hideWatched = resultBundle.getBoolean(IntentData.hideWatched)
                showUpcoming = resultBundle.getBoolean(IntentData.showUpcoming)
                lifecycleScope.launch { showFeed() }
            }

            FilterSortBottomSheet()
                .apply {
                    arguments =
                        bundleOf(
                            IntentData.sortOptions to fetchSortOptions(),
                            IntentData.hideWatched to hideWatched,
                            IntentData.showUpcoming to showUpcoming,
                        )
                }
                .show(childFragmentManager)
        }
    }

    private fun fetchSortOptions(): List<SelectableOption> {
        return resources.getStringArray(R.array.sortOptions).mapIndexed { index, option ->
            SelectableOption(isSelected = index == selectedSortOrder, name = option)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private suspend fun playByGroup(groupIndex: Int) {
        val streams =
            viewModel.videoFeed.value
                .orEmpty()
                .filterByGroup(groupIndex)
                .let {
                    DatabaseHelper.filterByStreamTypeAndWatchPosition(it, hideWatched, showUpcoming)
                }
                .sortedBySelectedOrder()

        if (streams.isEmpty()) return

        PlayingQueue.setStreams(streams)

        NavigationHelper.navigateVideo(
            requireContext(),
            playerData =
                PlayerData(
                    videoId = streams.first().url,
                    keepQueue = true,
                ),
        )
    }

    private fun filterUngroupedStreamItems(streamItems: List<StreamItem>): List<StreamItem> {
        val groups = viewModel.groups.value.orEmpty()

        return streamItems.filter { streamItem ->
            groups.none { it.channels.contains(streamItem.uploaderUrl.orEmpty().toID()) }
        }
    }

    @SuppressLint("InflateParams")
    private fun initChannelGroups() {
        val binding = _binding ?: return

        val groups = viewModel.groups.value.orEmpty()
        if (
            selectedFilterGroup >= groups.size ||
                (selectedFilterGroup == FILTER_GROUP_UNGROUPED && groups.isEmpty())
        ) {
            selectedFilterGroup = FILTER_GROUP_ALL
        }

        binding.chipAll.setOnLongClickListener {
            lifecycleScope.launch { playByGroup(FILTER_GROUP_ALL) }
            true
        }

        binding.chipUngrouped.setOnLongClickListener {
            lifecycleScope.launch { playByGroup(FILTER_GROUP_UNGROUPED) }
            true
        }

        isUpdatingChannelGroups = true
        try {
            binding.channelGroups.removeAllViews()
            binding.channelGroups.addView(binding.chipAll)

            var checkedChipId =
                when (selectedFilterGroup) {
                    FILTER_GROUP_UNGROUPED -> binding.chipUngrouped.id
                    else -> binding.chipAll.id
                }

            groups.forEachIndexed { index, group ->
                val chip = layoutInflater.inflate(R.layout.filter_chip, null) as Chip
                chip.apply {
                    id = View.generateViewId()
                    isCheckable = true
                    text = group.name
                    setOnLongClickListener {
                        lifecycleScope.launch { playByGroup(index) }
                        true
                    }
                }

                binding.channelGroups.addView(chip)

                if (index == selectedFilterGroup) {
                    checkedChipId = chip.id
                }
            }

            // only show "ungrouped" chip category if there actually are any ungrouped subscriptions
            // not sure if it's worth loading them here
            binding.channelGroups.addView(binding.chipUngrouped)
            binding.channelGroups.check(checkedChipId)
        } finally {
            isUpdatingChannelGroups = false
        }
    }

    private fun List<StreamItem>.filterByGroup(groupIndex: Int): List<StreamItem> {
        if (groupIndex == FILTER_GROUP_ALL) return this
        if (groupIndex == FILTER_GROUP_UNGROUPED) return filterUngroupedStreamItems(this)

        val group = viewModel.groups.value?.getOrNull(groupIndex)
        return filter {
            val channelId = it.uploaderUrl.orEmpty().toID()
            group?.channels?.contains(channelId) != false
        }
    }

    private fun List<StreamItem>.sortedBySelectedOrder() =
        when (selectedSortOrder) {
            0 -> this
            1 -> this.reversed()
            2 -> this.sortedBy { it.views }.reversed()
            3 -> this.sortedBy { it.views }
            4 -> this.sortedBy { it.uploaderName }
            5 -> this.sortedBy { it.uploaderName }.reversed()
            else -> this
        }

    private suspend fun showFeed(restoreScrollState: Boolean = true) {
        val binding = _binding ?: return
        val videoFeed = viewModel.videoFeed.value ?: return

        val feed =
            videoFeed.filterByGroup(selectedFilterGroup).let {
                DatabaseHelper.filterByStreamTypeAndWatchPosition(it, hideWatched, showUpcoming)
            }

        val sortedFeed = feed.sortedBySelectedOrder().toMutableList()

        // add an "all caught up item"
        if (selectedSortOrder == 0) {
            val lastCheckedFeedTime = PreferenceHelper.getLastCheckedFeedTime(seenByUser = true)
            val caughtUpIndex = feed.indexOfFirst {
                it.uploaded <= lastCheckedFeedTime && !it.isUpcoming
            }
            if (caughtUpIndex > 0 && !feed[caughtUpIndex - 1].isUpcoming) {
                sortedFeed.add(
                    caughtUpIndex,
                    StreamItem(type = VideoCardsAdapter.CAUGHT_UP_STREAM_TYPE),
                )
            }
        }

        val isFetchingEmpty = sortedFeed.isEmpty() && viewModel.isFeedFetchInFlight
        binding.subProgress.isVisible = isFetchingEmpty

        val isFeedEmpty = sortedFeed.isEmpty() && !isFetchingEmpty
        binding.subFeed.isGone = isFeedEmpty
        binding.emptyFeed.isVisible = isFeedEmpty

        binding.toggleSubs.text = getString(R.string.subscriptions)

        binding.subRefresh.isRefreshing = false

        feedAdapter.submitList(sortedFeed) {
            if (restoreScrollState) {
                // manually restore the previous feed state
                binding.subFeed.layoutManager?.onRestoreInstanceState(
                    viewModel.subFeedRecyclerViewState,
                )
            } else {
                binding.subFeed.scrollToPosition(0)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // manually restore the recyclerview state after rotation due to
        // https://github.com/material-components/material-components-android/issues/3473
        binding.subFeed.layoutManager?.onRestoreInstanceState(viewModel.subFeedRecyclerViewState)
    }

    fun removeItem(videoId: String) {
        feedAdapter.removeItemById(videoId)
    }

    companion object {
        const val FILTER_GROUP_UNGROUPED = -2
        const val FILTER_GROUP_ALL = -1

        internal fun resolveSelectedFilterGroup(
            checkedChipId: Int,
            allChipId: Int,
            ungroupedChipId: Int,
            groupChipIds: List<Int>,
        ): Int {
            return when (checkedChipId) {
                View.NO_ID,
                allChipId -> FILTER_GROUP_ALL
                ungroupedChipId -> FILTER_GROUP_UNGROUPED
                else -> groupChipIds.indexOf(checkedChipId).takeIf { it >= 0 } ?: FILTER_GROUP_ALL
            }
        }
    }
}
