package com.github.libretube.player

import com.github.libretube.api.poToken.PoTokenGenerator
import com.github.libretube.util.NewPipeDownloaderImpl
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider

/**
 * Video-scoped HTTP/SABR transport context propagated from extraction through stream refresh
 * recovery into playback and download data sources.
 */
data class StreamTransportContext(
    val videoId: String? = null,
    val userAgent: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val httpClient: OkHttpClient = NewPipeDownloaderImpl.httpClient,
    val poTokenProvider: PoTokenProvider = PoTokenGenerator.instance,
) {
    fun resolveUserAgent(url: String? = null): String {
        val headerUserAgent =
            headers.entries
                .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
                ?.value
                ?.takeIf { it.isNotBlank() }

        return NewPipeDownloaderImpl.resolveUrlClientUserAgent(url)
            ?: userAgent?.takeIf { it.isNotBlank() }
            ?: headerUserAgent
            ?: NewPipeDownloaderImpl.USER_AGENT
    }
}
