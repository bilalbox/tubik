package com.github.libretube.api

import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Subscription
import com.github.libretube.util.AllowedSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowedLocalSearchIndexTest {

    private val snapshot =
        AllowedSnapshot(
            allowlistEnabled = true,
            allowedChannelIds = setOf("UC_ALLOWED_1", "UC_ALLOWED_2"),
        )

    @Test
    fun `search matches Allowed Channels and feed items across multiple tokens and respects filter`() =
        runBlocking {
            val index =
                AllowedLocalSearchIndex(
                    getAllowedSubscriptions = {
                        listOf(
                            Subscription(
                                url = "UC_ALLOWED_1",
                                name = "Volcano Explorers",
                                verified = true,
                            ),
                            Subscription(
                                url = "UC_BLOCKED",
                                name = "Volcano Music",
                                verified = false,
                            ),
                        )
                    },
                    getLocalFeedItems = {
                        listOf(
                            StreamItem(
                                url = "v1",
                                title = "Amazing Space Documentary",
                                uploaderName = "Volcano Explorers",
                                shortDescription = "Learn about eruptions on Mars",
                                uploaderUrl = "UC_ALLOWED_1",
                                duration = 180L,
                            ),
                            StreamItem(
                                url = "v2",
                                title = "Baking Bread",
                                uploaderName = "Chef Channel",
                                uploaderUrl = "UC_ALLOWED_2",
                                duration = 90L,
                            ),
                            StreamItem(
                                url = "v3",
                                title = "Volcanoes on Mars",
                                uploaderUrl = "UC_BLOCKED",
                                duration = 120L,
                            ),
                        )
                    },
                )

            // Multi-token match across title and description
            val allResults = index.search("  mars   documentary ", "all", snapshot)
            assertEquals(listOf("v1"), allResults.map { it.url })

            // Channel-only filter returns only matching Allowed Channel
            val channelResults = index.search("volcano", "channels", snapshot)
            assertEquals(listOf("UC_ALLOWED_1"), channelResults.map { it.url })
            assertEquals(StreamItem.TYPE_CHANNEL, channelResults.single().type)

            // Video-only filter returns only matching allowed feed item
            val videoResults = index.search("volcano", "videos", snapshot)
            assertEquals(listOf("v1"), videoResults.map { it.url })
            assertEquals(StreamItem.TYPE_STREAM, videoResults.single().type)

            // Blank query returns empty
            assertTrue(index.search("   ", "all", snapshot).isEmpty())
        }

    private fun suggestionIndex(): AllowedLocalSearchIndex =
        AllowedLocalSearchIndex(
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
                        uploaderUrl = "UC_ALLOWED_1",
                    ),
                    StreamItem(
                        url = "v2",
                        title = "Fun with Magnets and Science",
                        uploaderUrl = "UC_ALLOWED_2",
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
    fun `suggest ranks prefix matches first and deduplicates case-insensitively`() = runBlocking {
        val index = suggestionIndex()

        assertEquals(
            listOf(
                "SciShow Kids",
                "Science of Volcanoes",
                "science experiments",
                "Kids Science Lab",
                "Fun with Magnets and Science",
                "kids science",
            ),
            index.suggest("  sci  ", snapshot),
        )
        assertEquals(
            listOf("Fun with Magnets and Science"),
            index.suggest("magnets science", snapshot),
        )
        assertTrue(index.suggest("   ", snapshot).isEmpty())
    }
}
