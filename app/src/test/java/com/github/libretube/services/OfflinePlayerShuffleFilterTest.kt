package com.github.libretube.services

import com.github.libretube.db.obj.Download
import com.github.libretube.util.AllowedSnapshot
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePlayerShuffleFilterTest {

    @Test
    fun `skips non-allowed downloads when allowlist is enabled and returns allowed channel download`() {
        val downloads =
            listOf(
                Download(videoId = "blocked-1", uploaderUrl = "/channel/UCblocked1"),
                Download(videoId = "allowed-1", uploaderUrl = "/channel/UCallowed1"),
                Download(videoId = "missing-uploader", uploaderUrl = null),
                Download(
                    videoId = "allowed-2",
                    uploaderUrl = "https://youtube.com/channel/UCallowed2",
                ),
            )
        val snapshot =
            AllowedSnapshot(
                allowlistEnabled = true,
                allowedChannelIds = setOf("UCallowed1", "UCallowed2"),
            )

        for (seed in 0 until 25) {
            val selected = selectAllowedShuffleDownloadId(downloads, snapshot, Random(seed))
            assertTrue(
                "Expected allowed video id, got $selected",
                selected == "allowed-1" || selected == "allowed-2",
            )
        }
    }

    @Test
    fun `returns null when allowlist is enabled and only non-allowed downloads exist`() {
        val downloads =
            listOf(
                Download(videoId = "blocked-1", uploaderUrl = "/channel/UCblocked1"),
                Download(videoId = "missing-uploader", uploaderUrl = null),
            )
        val snapshot =
            AllowedSnapshot(
                allowlistEnabled = true,
                allowedChannelIds = setOf("UCallowed1"),
            )

        assertNull(selectAllowedShuffleDownloadId(downloads, snapshot, Random(0)))
    }

    @Test
    fun `allows any download when allowlist is disabled`() {
        val downloads =
            listOf(
                Download(videoId = "any-1", uploaderUrl = "/channel/UCblocked1"),
            )
        val snapshot =
            AllowedSnapshot(
                allowlistEnabled = false,
                allowedChannelIds = emptySet(),
            )

        assertEquals("any-1", selectAllowedShuffleDownloadId(downloads, snapshot, Random(0)))
    }
}
