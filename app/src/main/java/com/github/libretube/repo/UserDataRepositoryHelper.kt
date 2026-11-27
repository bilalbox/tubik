package com.github.libretube.repo

object UserDataRepositoryHelper {
    @Deprecated(
        "DO NOT use this directly, use the wrappers from PlaylistHelper and SubscriptionHelper instead!",
    )
    val userDataRepository: UserDataRepository
        get() = LocalUserDataRepository()

    @Deprecated("DO NOT use this directly, use the wrappers from SubscriptionHelper instead!")
    val feedRepository: FeedRepository
        get() = SeriesAwareFeedRepository()
}
