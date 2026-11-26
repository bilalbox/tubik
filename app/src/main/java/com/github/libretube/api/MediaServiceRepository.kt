package com.github.libretube.api

import com.github.libretube.api.obj.Channel
import com.github.libretube.api.obj.ChannelTabResponse
import com.github.libretube.api.obj.CommentsPage
import com.github.libretube.api.obj.DeArrowContent
import com.github.libretube.api.obj.Playlist
import com.github.libretube.api.obj.SearchResult
import com.github.libretube.api.obj.SegmentData
import com.github.libretube.api.obj.Streams
import com.github.libretube.player.StreamTransportContext

interface MediaServiceRepository {
    suspend fun getStreams(videoId: String): Streams

    fun getStreamTransportContext(videoId: String): StreamTransportContext =
        StreamTransportContext(videoId = videoId)

    fun invalidateStreamsCache(videoId: String) {}

    suspend fun getComments(videoId: String): CommentsPage

    suspend fun getSegments(
        videoId: String,
        category: List<String>,
        actionType: List<String>? = null,
    ): SegmentData

    suspend fun getDeArrowContent(videoId: String): DeArrowContent?

    suspend fun getCommentsNextPage(videoId: String, nextPage: String): CommentsPage

    suspend fun getSearchResults(searchQuery: String, filter: String): SearchResult

    suspend fun getSearchResultsNextPage(
        searchQuery: String,
        filter: String,
        nextPage: String,
    ): SearchResult

    suspend fun getSuggestions(query: String): List<String>

    suspend fun getChannel(channelId: String): Channel

    suspend fun getChannelTab(data: String, nextPage: String? = null): ChannelTabResponse

    suspend fun getChannelByName(channelName: String): Channel

    suspend fun getChannelNextPage(channelId: String, nextPage: String): Channel

    suspend fun getPlaylist(playlistId: String): Playlist

    suspend fun getPlaylistNextPage(playlistId: String, nextPage: String): Playlist

    companion object {
        @Volatile private var sharedInstance: MediaServiceRepository? = null

        val instance: MediaServiceRepository
            get() =
                sharedInstance
                    ?: synchronized(this) {
                        sharedInstance
                            ?: AllowlistMediaServiceRepository().also { sharedInstance = it }
                    }

        internal fun resetForTesting(overrideInstance: MediaServiceRepository? = null) {
            synchronized(this) {
                sharedInstance = overrideInstance
            }
        }
    }
}
