package com.github.libretube.api

import com.github.libretube.api.obj.Channel
import com.github.libretube.api.obj.ChannelTabResponse
import com.github.libretube.api.obj.Comment
import com.github.libretube.api.obj.CommentsPage
import com.github.libretube.api.obj.ContentItem
import com.github.libretube.api.obj.DeArrowContent
import com.github.libretube.api.obj.Playlist
import com.github.libretube.api.obj.SearchResult
import com.github.libretube.api.obj.SegmentData
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Streams
import com.github.libretube.api.obj.Subscription
import com.github.libretube.util.AccessControl
import com.github.libretube.util.AccessControlStorage
import com.github.libretube.util.InMemoryAccessControlStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AllowlistMediaServiceRepositoryTest {

    private val allowedIds = setOf("UC_ALLOWED_1", "UC_ALLOWED_2")

    private fun accessControl(
        enabled: () -> Boolean,
        ids: () -> Set<String> = { allowedIds },
    ): AccessControl {
        val storage = InMemoryAccessControlStorage()
        return AccessControl(
            storage =
                object : AccessControlStorage by storage {
                    override var allowlistEnabled: Boolean
                        get() = enabled()
                        set(value) {
                            storage.allowlistEnabled = value
                        }
                },
            getAllowedChannelIds = { if (enabled()) ids() else emptySet() },
        )
    }

    private class FakeMediaServiceRepository : MediaServiceRepository {
        var streamItems: List<StreamItem> = emptyList()
        var searchResult: SearchResult = SearchResult()
        var searchNextPageResult: SearchResult = SearchResult()
        var searchNextPagesByToken: Map<String, SearchResult> = emptyMap()
        var searchNextPageCalls: Int = 0
        var playlistResult: Playlist = Playlist()
        var playlistNextPageResult: Playlist = Playlist()
        var streamsByVideoId: Map<String, Streams> = emptyMap()
        var channelsById: Map<String, Channel> = emptyMap()
        var channelsByName: Map<String, Channel> = emptyMap()
        var channelTabResponse: ChannelTabResponse = ChannelTabResponse()
        var commentsPage: CommentsPage = CommentsPage()
        var suggestionCalls: Int = 0

        override suspend fun getStreams(videoId: String): Streams =
            streamsByVideoId.getValue(videoId)

        override suspend fun getComments(videoId: String): CommentsPage = commentsPage

        override suspend fun getSegments(
            videoId: String,
            category: List<String>,
            actionType: List<String>?,
        ): SegmentData = SegmentData()

        override suspend fun getDeArrowContent(videoId: String): DeArrowContent? = null

        override suspend fun getCommentsNextPage(videoId: String, nextPage: String): CommentsPage =
            commentsPage

        override suspend fun getSearchResults(searchQuery: String, filter: String): SearchResult =
            searchResult

        override suspend fun getSearchResultsNextPage(
            searchQuery: String,
            filter: String,
            nextPage: String,
        ): SearchResult {
            searchNextPageCalls++
            return searchNextPagesByToken[nextPage] ?: searchNextPageResult
        }

        override suspend fun getSuggestions(query: String): List<String> {
            suggestionCalls++
            return listOf("global-$query-1", "global-$query-2")
        }

        override suspend fun getChannel(channelId: String): Channel =
            channelsById.getValue(channelId)

        override suspend fun getChannelTab(data: String, nextPage: String?): ChannelTabResponse =
            channelTabResponse

        override suspend fun getChannelByName(channelName: String): Channel =
            channelsByName.getValue(channelName)

        override suspend fun getChannelNextPage(channelId: String, nextPage: String): Channel =
            channelsById.getValue(channelId)

        override suspend fun getPlaylist(playlistId: String): Playlist = playlistResult

        override suspend fun getPlaylistNextPage(playlistId: String, nextPage: String): Playlist =
            playlistNextPageResult
    }

    @Test
    fun `when Allowlist is on, search and playlists keep only Allowed Channels including Shorts, live streams, and channels`() =
        runBlocking {
            val fake =
                FakeMediaServiceRepository().apply {
                    streamItems =
                        listOf(
                            StreamItem(
                                url = "vid_allowed",
                                type = StreamItem.TYPE_STREAM,
                                uploaderUrl = "UC_ALLOWED_1",
                                duration = 120L,
                            ),
                            StreamItem(
                                url = "short_allowed",
                                type = StreamItem.TYPE_STREAM,
                                uploaderUrl = "UC_ALLOWED_2",
                                isShort = true,
                            ),
                            StreamItem(
                                url = "live_allowed",
                                type = StreamItem.TYPE_STREAM,
                                uploaderUrl = "UC_ALLOWED_1",
                                duration = 0L,
                            ),
                            StreamItem(
                                url = "vid_blocked",
                                type = StreamItem.TYPE_STREAM,
                                uploaderUrl = "UC_BLOCKED",
                                duration = 120L,
                            ),
                            StreamItem(
                                url = "short_blocked",
                                type = StreamItem.TYPE_STREAM,
                                uploaderUrl = "UC_BLOCKED",
                                isShort = true,
                            ),
                            StreamItem(
                                url = "live_blocked",
                                type = StreamItem.TYPE_STREAM,
                                uploaderUrl = "UC_BLOCKED",
                                duration = 0L,
                            ),
                        )
                    searchResult =
                        SearchResult(
                            items =
                                listOf(
                                    ContentItem(
                                        url = "s_vid_ok",
                                        type = StreamItem.TYPE_STREAM,
                                        thumbnail = "",
                                        uploaderUrl = "UC_ALLOWED_1",
                                        duration = 100L,
                                    ),
                                    ContentItem(
                                        url = "s_short_ok",
                                        type = StreamItem.TYPE_STREAM,
                                        thumbnail = "",
                                        uploaderUrl = "UC_ALLOWED_2",
                                        isShort = true,
                                    ),
                                    ContentItem(
                                        url = "s_live_ok",
                                        type = StreamItem.TYPE_STREAM,
                                        thumbnail = "",
                                        uploaderUrl = "UC_ALLOWED_1",
                                        duration = 0L,
                                    ),
                                    ContentItem(
                                        url = "UC_ALLOWED_1",
                                        type = StreamItem.TYPE_CHANNEL,
                                        thumbnail = "",
                                    ),
                                    ContentItem(
                                        url = "s_vid_no",
                                        type = StreamItem.TYPE_STREAM,
                                        thumbnail = "",
                                        uploaderUrl = "UC_BLOCKED",
                                        duration = 100L,
                                    ),
                                    ContentItem(
                                        url = "s_short_no",
                                        type = StreamItem.TYPE_STREAM,
                                        thumbnail = "",
                                        uploaderUrl = "UC_BLOCKED",
                                        isShort = true,
                                    ),
                                    ContentItem(
                                        url = "s_live_no",
                                        type = StreamItem.TYPE_STREAM,
                                        thumbnail = "",
                                        uploaderUrl = "UC_BLOCKED",
                                        duration = 0L,
                                    ),
                                    ContentItem(
                                        url = "UC_BLOCKED",
                                        type = StreamItem.TYPE_CHANNEL,
                                        thumbnail = "",
                                    ),
                                ),
                        )
                    searchNextPageResult = searchResult
                    playlistResult =
                        Playlist(
                            name = "Mixed Playlist",
                            uploaderUrl = "UC_ALLOWED_1",
                            relatedStreams = streamItems,
                        )
                    playlistNextPageResult = playlistResult
                }

            val repo =
                AllowlistMediaServiceRepository(
                    delegate = fake,
                    accessControl = accessControl(enabled = { true }),
                )

            assertEquals(
                listOf("s_vid_ok", "s_short_ok", "s_live_ok", "UC_ALLOWED_1"),
                repo.getSearchResults("test", "all").items.map { it.url },
            )
            assertEquals(
                listOf("s_vid_ok", "s_short_ok", "s_live_ok", "UC_ALLOWED_1"),
                repo.getSearchResultsNextPage("test", "all", "page2").items.map { it.url },
            )
            assertEquals(
                listOf("vid_allowed", "short_allowed", "live_allowed"),
                repo.getPlaylist("pl1").relatedStreams.map { it.url },
            )
            assertEquals(
                listOf("vid_allowed", "short_allowed", "live_allowed"),
                repo.getPlaylistNextPage("pl1", "page2").relatedStreams.map { it.url },
            )
        }

    @Test
    fun `when Allowlist is on, related videos and comments return empty`() = runBlocking {
        val fake =
            FakeMediaServiceRepository().apply {
                streamsByVideoId =
                    mapOf(
                        "v_ok" to
                            Streams(
                                title = "Allowed Video",
                                description = "Desc",
                                uploader = "Allowed Channel",
                                uploaderUrl = "UC_ALLOWED_1",
                                thumbnailUrl = "",
                                category = "Education",
                                uploaderVerified = false,
                                duration = 60L,
                                relatedStreams =
                                    listOf(
                                        StreamItem(url = "r1", uploaderUrl = "UC_ALLOWED_1"),
                                        StreamItem(url = "r2", uploaderUrl = "UC_BLOCKED"),
                                    ),
                            ),
                    )
                commentsPage =
                    CommentsPage(
                        comments =
                            listOf(
                                Comment(
                                    author = "User",
                                    commentId = "c1",
                                    commentText = "Hello",
                                    commentedTime = "1d",
                                    commentorUrl = "UC_ALLOWED_1",
                                    hearted = false,
                                    likeCount = 0L,
                                    pinned = false,
                                    thumbnail = "",
                                    verified = false,
                                    replyCount = 0L,
                                ),
                            ),
                        disabled = false,
                        nextpage = "np",
                        commentCount = 1L,
                    )
            }

        val repo =
            AllowlistMediaServiceRepository(
                delegate = fake,
                accessControl = accessControl(enabled = { true }),
            )

        val streams = repo.getStreams("v_ok")
        assertTrue(streams.relatedStreams.isEmpty())

        val comments = repo.getComments("v_ok")
        assertTrue(comments.comments.isEmpty())
        assertTrue(comments.disabled)

        val commentsNext = repo.getCommentsNextPage("v_ok", "np")
        assertTrue(commentsNext.comments.isEmpty())
        assertTrue(commentsNext.disabled)
    }

    @Test
    fun `when Allowlist is on, fetching non-allowed channel or video from non-allowed channel throws ChannelNotAllowedException`() =
        runBlocking {
            val fake =
                FakeMediaServiceRepository().apply {
                    streamsByVideoId =
                        mapOf(
                            "v_blocked" to
                                Streams(
                                    title = "Blocked Video",
                                    description = "Desc",
                                    uploader = "Blocked Channel",
                                    uploaderUrl = "UC_BLOCKED",
                                    thumbnailUrl = "",
                                    category = "Entertainment",
                                    uploaderVerified = false,
                                    duration = 60L,
                                ),
                        )
                    channelsById =
                        mapOf(
                            "UC_ALLOWED_1" to Channel(id = "UC_ALLOWED_1", name = "Allowed"),
                            "UC_BLOCKED" to Channel(id = "UC_BLOCKED", name = "Blocked"),
                        )
                    channelsByName =
                        mapOf(
                            "blocked_handle" to Channel(id = "UC_BLOCKED", name = "Blocked"),
                            "allowed_handle" to Channel(id = "UC_ALLOWED_1", name = "Allowed"),
                        )
                }

            val repo =
                AllowlistMediaServiceRepository(
                    delegate = fake,
                    accessControl = accessControl(enabled = { true }),
                )

            assertEquals("UC_ALLOWED_1", repo.getChannel("UC_ALLOWED_1").id)
            assertEquals("UC_ALLOWED_1", repo.getChannelByName("allowed_handle").id)

            try {
                repo.getChannel("UC_BLOCKED")
                fail("Expected ChannelNotAllowedException")
            } catch (_: ChannelNotAllowedException) {}

            try {
                repo.getChannelNextPage("UC_BLOCKED", "np")
                fail("Expected ChannelNotAllowedException")
            } catch (_: ChannelNotAllowedException) {}

            try {
                repo.getChannelByName("blocked_handle")
                fail("Expected ChannelNotAllowedException")
            } catch (_: ChannelNotAllowedException) {}

            try {
                repo.getStreams("v_blocked")
                fail("Expected ChannelNotAllowedException")
            } catch (_: ChannelNotAllowedException) {}
        }

    @Test
    fun `when Allowlist is off, everything passes through unchanged`() = runBlocking {
        val fake =
            FakeMediaServiceRepository().apply {
                playlistResult =
                    Playlist(
                        relatedStreams =
                            listOf(
                                StreamItem(url = "vid_blocked", uploaderUrl = "UC_BLOCKED"),
                            ),
                    )
                searchResult =
                    SearchResult(
                        items =
                            listOf(
                                ContentItem(
                                    url = "s_blocked",
                                    type = StreamItem.TYPE_STREAM,
                                    thumbnail = "",
                                    uploaderUrl = "UC_BLOCKED",
                                ),
                            ),
                    )
                streamsByVideoId =
                    mapOf(
                        "v_blocked" to
                            Streams(
                                title = "Blocked Video",
                                description = "Desc",
                                uploader = "Blocked Channel",
                                uploaderUrl = "UC_BLOCKED",
                                thumbnailUrl = "",
                                category = "Entertainment",
                                uploaderVerified = false,
                                duration = 60L,
                                relatedStreams =
                                    listOf(StreamItem(url = "r1", uploaderUrl = "UC_BLOCKED")),
                            ),
                    )
                channelsById =
                    mapOf(
                        "UC_BLOCKED" to Channel(id = "UC_BLOCKED", name = "Blocked"),
                    )
                commentsPage =
                    CommentsPage(
                        comments =
                            listOf(
                                Comment(
                                    author = "User",
                                    commentId = "c1",
                                    commentText = "Hello",
                                    commentedTime = "1d",
                                    commentorUrl = "UC_BLOCKED",
                                    hearted = false,
                                    likeCount = 0L,
                                    pinned = false,
                                    thumbnail = "",
                                    verified = false,
                                    replyCount = 0L,
                                ),
                            ),
                        disabled = false,
                    )
            }

        val repo =
            AllowlistMediaServiceRepository(
                delegate = fake,
                accessControl = accessControl(enabled = { false }),
            )

        assertEquals(listOf("vid_blocked"), repo.getPlaylist("pl").relatedStreams.map { it.url })
        assertEquals(listOf("s_blocked"), repo.getSearchResults("q", "all").items.map { it.url })
        val streams = repo.getStreams("v_blocked")
        assertEquals(1, streams.relatedStreams.size)
        assertEquals("UC_BLOCKED", repo.getChannel("UC_BLOCKED").id)
        assertEquals(1, repo.getComments("v_blocked").comments.size)
    }

    @Test
    fun `when Allowlist is on, getSuggestions returns only matching Allowed Channel names, allowed local feed titles, and local search history without global autocomplete calls`() =
        runBlocking {
            val fake = FakeMediaServiceRepository()
            val subscriptions =
                listOf(
                    Subscription(url = "UC_ALLOWED_1", name = "SciShow Kids", verified = true),
                    Subscription(
                        url = "UC_ALLOWED_2",
                        name = "National Geographic Kids",
                        verified = true,
                    ),
                    Subscription(
                        url = "UC_BLOCKED",
                        name = "Sci-Fi Music Channel",
                        verified = false,
                    ),
                )
            val localFeed =
                listOf(
                    StreamItem(
                        url = "v1",
                        title = "Science of Volcanoes",
                        uploaderUrl = "UC_ALLOWED_1",
                    ),
                    StreamItem(
                        url = "v2",
                        title = "Fun with Magnets and Science",
                        uploaderUrl = "UC_ALLOWED_2",
                    ),
                    StreamItem(url = "v3", title = "SciShow Kids", uploaderUrl = "UC_ALLOWED_1"),
                    StreamItem(
                        url = "v4",
                        title = "Blocked Science Video",
                        uploaderUrl = "UC_BLOCKED",
                    ),
                    StreamItem(url = "v5", title = "Cute Puppies", uploaderUrl = "UC_ALLOWED_1"),
                )
            val localHistory = listOf("science experiments", "dinosaur facts")

            val allowlistRepo =
                AllowlistMediaServiceRepository(
                    delegate = fake,
                    accessControl = accessControl(enabled = { true }),
                    getAllowedSubscriptions = { subscriptions },
                    getLocalFeedItems = { localFeed },
                    getLocalSearchHistory = { localHistory },
                )

            val suggestions = allowlistRepo.getSuggestions("sci")
            assertEquals(0, fake.suggestionCalls)
            assertEquals(
                listOf(
                    "SciShow Kids",
                    "Science of Volcanoes",
                    "science experiments",
                    "Fun with Magnets and Science",
                ),
                suggestions,
            )
            assertTrue(allowlistRepo.getSuggestions("   ").isEmpty())
            assertEquals(0, fake.suggestionCalls)

            val unrestrictedRepo =
                AllowlistMediaServiceRepository(
                    delegate = fake,
                    accessControl = accessControl(enabled = { false }),
                    getAllowedSubscriptions = { subscriptions },
                    getLocalFeedItems = { localFeed },
                    getLocalSearchHistory = { localHistory },
                )
            assertEquals(
                listOf("global-sci-1", "global-sci-2"),
                unrestrictedRepo.getSuggestions("sci"),
            )
            assertEquals(1, fake.suggestionCalls)
        }

    @Test
    fun `when Allowlist is on, search combines local Allowed Channel and feed matches with multi-page upstream prefetching when early pages filter to empty`() =
        runBlocking {
            val fake =
                FakeMediaServiceRepository().apply {
                    searchResult =
                        SearchResult(
                            items =
                                listOf(
                                    ContentItem(
                                        url = "blocked_p1_1",
                                        type = StreamItem.TYPE_STREAM,
                                        thumbnail = "",
                                        uploaderUrl = "UC_BLOCKED",
                                    ),
                                    ContentItem(
                                        url = "blocked_p1_2",
                                        type = StreamItem.TYPE_STREAM,
                                        thumbnail = "",
                                        uploaderUrl = "UC_BLOCKED_2",
                                    ),
                                ),
                            nextpage = "page2",
                            suggestion = "unfiltered global suggestion",
                            corrected = true,
                        )
                    searchNextPagesByToken =
                        mapOf(
                            "page2" to
                                SearchResult(
                                    items =
                                        listOf(
                                            ContentItem(
                                                url = "blocked_p2_1",
                                                type = StreamItem.TYPE_STREAM,
                                                thumbnail = "",
                                                uploaderUrl = "UC_BLOCKED",
                                            ),
                                        ),
                                    nextpage = "page3",
                                ),
                            "page3" to
                                SearchResult(
                                    items =
                                        listOf(
                                            ContentItem(
                                                url = "feed_match_1",
                                                type = StreamItem.TYPE_STREAM,
                                                thumbnail = "",
                                                title = "Space Volcanoes",
                                                uploaderUrl = "UC_ALLOWED_1",
                                            ),
                                            ContentItem(
                                                url = "upstream_allowed_p3",
                                                type = StreamItem.TYPE_STREAM,
                                                thumbnail = "",
                                                title = "Underwater Volcanoes",
                                                uploaderUrl = "UC_ALLOWED_2",
                                            ),
                                            ContentItem(
                                                url = "blocked_p3",
                                                type = StreamItem.TYPE_STREAM,
                                                thumbnail = "",
                                                uploaderUrl = "UC_BLOCKED",
                                            ),
                                        ),
                                    nextpage = "page4",
                                ),
                            "page4" to
                                SearchResult(
                                    items =
                                        listOf(
                                            ContentItem(
                                                url = "blocked_p4",
                                                type = StreamItem.TYPE_STREAM,
                                                thumbnail = "",
                                                uploaderUrl = "UC_BLOCKED",
                                            ),
                                        ),
                                    nextpage = "page5",
                                ),
                            "page5" to
                                SearchResult(
                                    items =
                                        listOf(
                                            ContentItem(
                                                url = "upstream_allowed_p5",
                                                type = StreamItem.TYPE_STREAM,
                                                thumbnail = "",
                                                title = "Mars Volcano",
                                                uploaderUrl = "UC_ALLOWED_1",
                                            ),
                                        ),
                                    nextpage = null,
                                ),
                        )
                }

            val subscriptions =
                listOf(
                    Subscription(url = "UC_ALLOWED_1", name = "Volcano Explorers", verified = true),
                    Subscription(url = "UC_ALLOWED_2", name = "Ocean Science", verified = false),
                )
            val localFeed =
                listOf(
                    StreamItem(
                        url = "feed_match_1",
                        title = "Space Volcanoes",
                        uploaderUrl = "UC_ALLOWED_1",
                        duration = 180L,
                    ),
                    StreamItem(
                        url = "feed_non_match",
                        title = "Baking Bread",
                        uploaderUrl = "UC_ALLOWED_1",
                        duration = 90L,
                    ),
                    StreamItem(
                        url = "feed_blocked",
                        title = "Blocked Volcanoes",
                        uploaderUrl = "UC_BLOCKED",
                        duration = 120L,
                    ),
                )

            val repo =
                AllowlistMediaServiceRepository(
                    delegate = fake,
                    accessControl = accessControl(enabled = { true }),
                    getAllowedSubscriptions = { subscriptions },
                    getLocalFeedItems = { localFeed },
                )

            val firstLoad = repo.getSearchResults("volcano", "all")
            assertEquals(2, fake.searchNextPageCalls)
            assertEquals(
                listOf("UC_ALLOWED_1", "feed_match_1", "upstream_allowed_p3"),
                firstLoad.items.map { it.url },
            )
            assertEquals("page4", firstLoad.nextpage)
            assertEquals(null, firstLoad.suggestion)
            assertFalse(firstLoad.corrected)

            val nextLoad = repo.getSearchResultsNextPage("volcano", "all", firstLoad.nextpage!!)
            assertEquals(listOf("upstream_allowed_p5"), nextLoad.items.map { it.url })
            assertEquals(null, nextLoad.nextpage)
        }

    @Test
    fun `when Allowlist is on and all upstream pages have only blocked items, multi-page prefetching stops at bounded maxSearchPrefetchPages`() =
        runBlocking {
            val fake =
                FakeMediaServiceRepository().apply {
                    searchResult =
                        SearchResult(
                            items =
                                listOf(
                                    ContentItem(
                                        url = "blocked_0",
                                        type = StreamItem.TYPE_STREAM,
                                        thumbnail = "",
                                        uploaderUrl = "UC_BLOCKED",
                                    ),
                                ),
                            nextpage = "next_token",
                        )
                    searchNextPageResult =
                        SearchResult(
                            items =
                                listOf(
                                    ContentItem(
                                        url = "blocked_n",
                                        type = StreamItem.TYPE_STREAM,
                                        thumbnail = "",
                                        uploaderUrl = "UC_BLOCKED",
                                    ),
                                ),
                            nextpage = "next_token",
                        )
                }

            val repo =
                AllowlistMediaServiceRepository(
                    delegate = fake,
                    accessControl = accessControl(enabled = { true }),
                    maxSearchPrefetchPages = 3,
                )

            val res = repo.getSearchResults("nothing_allowed", "videos")
            assertTrue(res.items.isEmpty())
            assertEquals(3, fake.searchNextPageCalls)
        }

    @Test
    fun `repository resolves a fresh AllowedCatalog snapshot per operation`() = runBlocking {
        val fake =
            FakeMediaServiceRepository().apply {
                playlistResult =
                    Playlist(
                        relatedStreams =
                            listOf(
                                StreamItem(
                                    url = "vid_1",
                                    type = StreamItem.TYPE_STREAM,
                                    uploaderUrl = "UC_ALLOWED_1",
                                ),
                                StreamItem(
                                    url = "vid_2",
                                    type = StreamItem.TYPE_STREAM,
                                    uploaderUrl = "UC_ALLOWED_2",
                                ),
                            ),
                    )
            }

        var dynamicAllowedIds = setOf("UC_ALLOWED_1")
        val dynamicRepo =
            AllowlistMediaServiceRepository(
                delegate = fake,
                accessControl = accessControl(enabled = { true }, ids = { dynamicAllowedIds }),
            )
        assertEquals(
            listOf("vid_1"),
            dynamicRepo.getPlaylist("pl").relatedStreams.map { it.url },
        )
        dynamicAllowedIds = setOf("UC_ALLOWED_2")
        assertEquals(
            listOf("vid_2"),
            dynamicRepo.getPlaylist("pl").relatedStreams.map { it.url },
        )
    }

    @Test
    fun `repository propagates CancellationException from AllowedCatalog snapshot`() = runBlocking {
        val fake =
            FakeMediaServiceRepository().apply {
                playlistResult =
                    Playlist(
                        relatedStreams =
                            listOf(
                                StreamItem(
                                    url = "vid_1",
                                    type = StreamItem.TYPE_STREAM,
                                    uploaderUrl = "UC_ALLOWED_1",
                                ),
                            ),
                    )
            }

        var fallbackSubscriptionsCalled = false
        val cancelledAccessControl =
            AccessControl(
                storage = InMemoryAccessControlStorage(allowlistEnabled = true),
                getAllowedChannelIds = { throw CancellationException("Coroutine cancelled") },
                getAllowedSubscriptions = {
                    fallbackSubscriptionsCalled = true
                    listOf(
                        Subscription(
                            url = "UC_ALLOWED_1",
                            name = "Allowed",
                            verified = true,
                        ),
                    )
                },
            )
        val repo =
            AllowlistMediaServiceRepository(
                delegate = fake,
                accessControl = cancelledAccessControl,
            )

        try {
            repo.getPlaylist("pl")
            fail("Expected CancellationException to propagate")
        } catch (_: CancellationException) {
            // Expected
        }
        assertFalse(
            "Expected getAllowedSubscriptions fallback not to run on CancellationException",
            fallbackSubscriptionsCalled,
        )
    }

    private fun localSearchRepo(): AllowlistMediaServiceRepository =
        AllowlistMediaServiceRepository(
            delegate = FakeMediaServiceRepository(),
            accessControl = accessControl(enabled = { true }),
            getAllowedSubscriptions = {
                listOf(
                    Subscription(url = "UC_ALLOWED_1", name = "SciShow Kids", verified = true),
                    Subscription(url = "UC_ALLOWED_2", name = "Kids Science Lab", verified = true),
                    Subscription(url = "UC_BLOCKED", name = "Science Blocked", verified = false),
                )
            },
            getLocalFeedItems = {
                listOf(
                    StreamItem(
                        url = "v1",
                        title = "Science of Volcanoes",
                        uploaderName = "SciShow Kids",
                        shortDescription = "Learn about eruptions on Mars",
                        uploaderUrl = "UC_ALLOWED_1",
                        duration = 180L,
                    ),
                    StreamItem(
                        url = "v2",
                        title = "Fun with Magnets and Science",
                        uploaderName = "Kids Science Lab",
                        uploaderUrl = "UC_ALLOWED_2",
                        duration = 90L,
                    ),
                    StreamItem(url = "v3", title = "scishow kids", uploaderUrl = "UC_ALLOWED_1"),
                    StreamItem(
                        url = "v4",
                        title = "Science Blocked Video",
                        uploaderUrl = "UC_BLOCKED",
                    ),
                )
            },
            getLocalSearchHistory = {
                listOf("science experiments", "kids science", "dinosaur facts")
            },
        )

    @Test
    fun `local search matches Allowed Channels and feed items across tokens and filters`() =
        runBlocking {
            val repo = localSearchRepo()
            assertEquals(
                listOf("v1"),
                repo.getSearchResults("  mars   volcanoes ", "all").items.map { it.url },
            )

            val channelsOnly = repo.getSearchResults("scishow", "channels")
            assertEquals(listOf("UC_ALLOWED_1"), channelsOnly.items.map { it.url })
            assertEquals(StreamItem.TYPE_CHANNEL, channelsOnly.items.single().type)

            val videosOnly = repo.getSearchResults("volcanoes", "videos")
            assertEquals(listOf("v1"), videosOnly.items.map { it.url })
            assertEquals(StreamItem.TYPE_STREAM, videosOnly.items.single().type)

            assertTrue(repo.getSearchResults("   ", "all").items.isEmpty())
        }

    @Test
    fun `local suggestions rank prefix matches first and deduplicate case-insensitively`() =
        runBlocking {
            val repo = localSearchRepo()
            assertEquals(
                listOf(
                    "SciShow Kids",
                    "Science of Volcanoes",
                    "science experiments",
                    "Kids Science Lab",
                    "Fun with Magnets and Science",
                    "kids science",
                ),
                repo.getSuggestions("  sci  "),
            )
            assertEquals(
                listOf("Fun with Magnets and Science"),
                repo.getSuggestions("magnets science"),
            )
            assertTrue(repo.getSuggestions("   ").isEmpty())
        }
}
