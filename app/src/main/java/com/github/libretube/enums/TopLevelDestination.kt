package com.github.libretube.enums

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.github.libretube.R

enum class TopLevelDestination(
    val route: String,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
) {
    Subscriptions("subscriptions", R.string.subscriptions, R.drawable.ic_subscriptions),
    Library("library", R.string.library, R.drawable.ic_library),
}
