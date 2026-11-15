package com.github.libretube.helpers

import com.github.libretube.api.poToken.PoTokenGenerator
import com.github.libretube.util.ClientUserAgentRegistry
import com.github.libretube.util.NewPipeDownloaderImpl
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor

object NewPipeExtractorInstance {
    val userAgentRegistry: ClientUserAgentRegistry = ClientUserAgentRegistry()

    val httpClient: OkHttpClient = NewPipeDownloaderImpl.httpClient

    val extractor: StreamingService by lazy {
        NewPipe.getService(ServiceList.YouTube.serviceId)
    }

    fun init() {
        NewPipe.init(NewPipeDownloaderImpl(httpClient, userAgentRegistry))
        YoutubeStreamExtractor.setPoTokenProvider(PoTokenGenerator.instance)
    }
}
