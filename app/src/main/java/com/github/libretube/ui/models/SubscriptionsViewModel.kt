package com.github.libretube.ui.models

import android.content.Context
import android.os.Parcelable
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.libretube.R
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Subscription
import com.github.libretube.db.obj.SubscriptionGroup
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.repo.FeedProgress
import com.github.libretube.repo.FeedRefresh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SubscriptionsViewModel : ViewModel() {
    var videoFeed = MutableLiveData<List<StreamItem>?>()

    var subscriptions = MutableLiveData<List<Subscription>?>()
    val feedProgress = MutableLiveData<FeedProgress?>()
    val feedError = MutableLiveData(false)

    var subFeedRecyclerViewState: Parcelable? = null

    val groups = MutableLiveData<List<SubscriptionGroup>>()
    var groupToEdit: SubscriptionGroup? = null

    private val feedJobLock = Any()
    private var fetchFeedJob: Job? = null
    private var feedGeneration: Long = 0L

    @Volatile
    var lastLoadedSubscriptionsRevision: Long = -1L
        private set

    @Volatile
    var isFeedFetchInFlight: Boolean = false
        private set

    @Volatile private var inFlightSubscriptionsRevision: Long = -1L

    fun hasPendingSubscriptionChanges(): Boolean =
        lastLoadedSubscriptionsRevision != SubscriptionHelper.subscriptionsRevision.value

    fun needsSubscriptionRefresh(
        revision: Long = SubscriptionHelper.subscriptionsRevision.value,
    ): Boolean =
        lastLoadedSubscriptionsRevision != revision &&
            (!isFeedFetchInFlight || inFlightSubscriptionsRevision != revision)

    fun fetchFeed(context: Context, refresh: FeedRefresh) {
        launchFeedFetch(context, refresh)
    }

    suspend fun awaitOrFetchFeed(): List<StreamItem> {
        val existing = videoFeed.value
        if (existing != null && !hasPendingSubscriptionChanges()) {
            return existing
        }
        val job =
            synchronized(feedJobLock) {
                val active = fetchFeedJob
                val revision = SubscriptionHelper.subscriptionsRevision.value
                if (
                    active != null && active.isActive && inFlightSubscriptionsRevision == revision
                ) {
                    active
                } else {
                    launchFeedFetchLocked(context = null, refresh = FeedRefresh.Automatically)
                }
            }
        job.join()
        return videoFeed.value.orEmpty()
    }

    private fun launchFeedFetch(context: Context?, refresh: FeedRefresh): Job =
        synchronized(feedJobLock) {
            launchFeedFetchLocked(context, refresh)
        }

    private fun launchFeedFetchLocked(context: Context?, refresh: FeedRefresh): Job {
        fetchFeedJob?.cancel()
        val generation = ++feedGeneration
        val targetRevision = SubscriptionHelper.subscriptionsRevision.value
        isFeedFetchInFlight = true
        inFlightSubscriptionsRevision = targetRevision
        feedError.postValue(false)

        lateinit var job: Job
        job =
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val loadedFeed =
                        try {
                            SubscriptionHelper.getFeed(refresh) { feedProgress ->
                                if (feedGeneration == generation) {
                                    this@SubscriptionsViewModel.feedProgress.postValue(feedProgress)
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (feedGeneration == generation) {
                                context?.toastFromMainDispatcher(R.string.server_error)
                                Log.e(TAG(), e.toString())
                                this@SubscriptionsViewModel.feedProgress.postValue(null)
                                this@SubscriptionsViewModel.feedError.postValue(true)
                            }
                            return@launch
                        }

                    synchronized(feedJobLock) {
                        if (feedGeneration != generation) return@launch
                        isFeedFetchInFlight = false
                        inFlightSubscriptionsRevision = -1L
                        if (fetchFeedJob === job) fetchFeedJob = null
                        lastLoadedSubscriptionsRevision = targetRevision
                    }
                    this@SubscriptionsViewModel.feedProgress.postValue(null)
                    this@SubscriptionsViewModel.videoFeed.postValue(loadedFeed)
                    loadedFeed
                        .firstOrNull { !it.isUpcoming }
                        ?.uploaded
                        ?.let {
                            PreferenceHelper.updateLastFeedWatchedTime(it, false)
                        }
                } finally {
                    synchronized(feedJobLock) {
                        if (feedGeneration == generation) {
                            isFeedFetchInFlight = false
                            inFlightSubscriptionsRevision = -1L
                            if (fetchFeedJob === job) fetchFeedJob = null
                        }
                    }
                }
            }
        fetchFeedJob = job
        return job
    }

    fun fetchSubscriptions(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val subscriptions =
                try {
                    SubscriptionHelper.getSubscriptions()
                } catch (e: Exception) {
                    context.toastFromMainDispatcher(R.string.server_error)
                    Log.e(TAG(), e.toString())
                    return@launch
                }
            this@SubscriptionsViewModel.subscriptions.postValue(subscriptions)
        }
    }
}
