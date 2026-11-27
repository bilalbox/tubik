package com.github.libretube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.databinding.EpisodeRowBinding
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.ui.adapters.callbacks.DiffUtilItemCallback
import com.github.libretube.ui.extensions.setWatchProgressLength

class EpisodeViewHolder(val binding: EpisodeRowBinding) : RecyclerView.ViewHolder(binding.root)

/**
 * Audio-style rows for the Episodes view. Tapping a row opens it the usual way ([onOpen]); the play
 * button always starts the audio player ([onListen]).
 */
class EpisodesAdapter(
    private val onOpen: (StreamItem) -> Unit,
    private val onListen: (StreamItem) -> Unit,
) :
    ListAdapter<StreamItem, EpisodeViewHolder>(
        DiffUtilItemCallback(areItemsTheSame = { old, new -> old.url == new.url }),
    ) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        EpisodeViewHolder(
            EpisodeRowBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        val episode = getItem(holder.bindingAdapterPosition)
        with(holder.binding) {
            episodeTitle.text = episode.title
            episodeSubtitle.text = episodeSubtitle(episode)
            ImageHelper.loadImage(episode.thumbnail, episodeThumbnail)
            watchProgress.setWatchProgressLength(
                episode.url.orEmpty().toID(),
                episode.duration ?: 0L,
            )
            root.setOnClickListener { onOpen(episode) }
            episodePlay.setOnClickListener { onListen(episode) }
        }
    }

    fun itemAt(position: Int): StreamItem? = currentList.getOrNull(position)
}

/** "Series · 48:12", or the channel name when the video isn't from a Series. */
internal fun episodeSubtitle(episode: StreamItem): String =
    listOfNotNull(
            episode.seriesName ?: episode.uploaderName,
            episode.duration?.takeIf { it > 0 }?.let(::formatEpisodeDuration),
        )
        .joinToString(" · ")

/** "48:12" or "1:02:03", like the duration on video thumbnails. */
internal fun formatEpisodeDuration(seconds: Long): String {
    val hours = seconds / SecondsPerHour
    val minutes = seconds % SecondsPerHour / SecondsPerMinute
    val secs = seconds % SecondsPerMinute
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs)
    else "%d:%02d".format(minutes, secs)
}

private const val SecondsPerMinute = 60
private const val SecondsPerHour = 3600
