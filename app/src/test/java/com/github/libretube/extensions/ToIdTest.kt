package com.github.libretube.extensions

import org.junit.Assert.assertEquals
import org.junit.Test

class ToIdTest {
    @Test
    fun `watch urls with extra query params reduce to the video id`() {
        assertEquals(
            "dQw4w9WgXcQ",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&pp=ygUG".toID(),
        )
    }

    @Test
    fun `a bare video id is unchanged`() {
        assertEquals("dQw4w9WgXcQ", "dQw4w9WgXcQ".toID())
    }

    @Test
    fun `channel urls reduce to the channel id`() {
        assertEquals(
            "UCxxxxxxxxxxxxxxxxxxxxxx",
            "https://www.youtube.com/channel/UCxxxxxxxxxxxxxxxxxxxxxx".toID(),
        )
    }

    @Test
    fun `channel shorts urls with query parameters reduce to the channel id`() {
        assertEquals(
            "UCxxxxxxxxxxxxxxxxxxxxxx",
            "https://www.youtube.com/channel/UCxxxxxxxxxxxxxxxxxxxxxx/shorts?app=desktop".toID(),
        )
    }

    @Test
    fun `mobile and non-www youtube channel urls reduce to the channel id`() {
        assertEquals(
            "UCxxxxxxxxxxxxxxxxxxxxxx",
            "https://m.youtube.com/channel/UCxxxxxxxxxxxxxxxxxxxxxx".toID(),
        )
        assertEquals(
            "UCxxxxxxxxxxxxxxxxxxxxxx",
            "https://youtube.com/channel/UCxxxxxxxxxxxxxxxxxxxxxx".toID(),
        )
    }

    @Test
    fun `handle and custom channel urls strip leading slashes and build canonical channel urls`() {
        assertEquals(
            "@braincandytv",
            "https://www.youtube.com/@braincandytv".toID(),
        )
        assertEquals(
            "@braincandytv",
            "https://www.youtube.com/@braincandytv/videos".toID(),
        )
        assertEquals(
            "dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ?t=12".toID(),
        )
        assertEquals(
            "https://www.youtube.com/@braincandytv",
            "https://www.youtube.com/@braincandytv".toChannelUrl(),
        )
        assertEquals(
            "https://www.youtube.com/channel/UCxxxxxxxxxxxxxxxxxxxxxx",
            "UCxxxxxxxxxxxxxxxxxxxxxx".toChannelUrl(),
        )
    }
}
