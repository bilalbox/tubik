package com.github.libretube.api.poToken

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import com.github.libretube.BuildConfig
import com.github.libretube.LibreTubeApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

class PoTokenGenerator(
    private var supportsWebViewProvider: () -> Boolean = {
        runCatching { CookieManager.getInstance() != null }.getOrDefault(false)
    },
    private var visitorDataProvider: suspend () -> String = {
        val innertubeClientRequestInfo = InnertubeClientRequestInfo.ofWebClient()
        innertubeClientRequestInfo.clientInfo.clientVersion =
            YoutubeParsingHelper.getClientVersion()

        YoutubeParsingHelper.getVisitorDataFromInnertube(
            innertubeClientRequestInfo,
            NewPipe.getPreferredLocalization(),
            NewPipe.getPreferredContentCountry(),
            YoutubeParsingHelper.getYouTubeHeaders(),
            YoutubeParsingHelper.YOUTUBEI_V1_URL,
            null,
            false,
        )
    },
    private var sessionFactory: suspend () -> WebPoTokenSession = {
        PoTokenWebView.newPoTokenGenerator(LibreTubeApp.instance)
    },
    private var generationTimeoutMs: Long = DEFAULT_GENERATION_TIMEOUT_MS,
) : PoTokenProvider {
    private val TAG = PoTokenGenerator::class.simpleName

    private object WebPoTokenGenLock

    private var webPoTokenVisitorData: String? = null
    private var webPoTokenGenerator: WebPoTokenSession? = null

    @Volatile private var poToken: PoTokenResult? = null

    fun getCachedWebClientPoToken(): PoTokenResult? =
        synchronized(WebPoTokenGenLock) {
            val generator = webPoTokenGenerator
            if (generator == null || generator.isExpired()) {
                generator?.let { closeSessionSafely(it) }
                webPoTokenGenerator = null
                webPoTokenVisitorData = null
                poToken = null
                null
            } else {
                poToken
            }
        }

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        if (!supportsWebViewProvider()) {
            return null
        }

        return synchronized(WebPoTokenGenLock) {
            getWebClientPoTokenLocked(videoId, forceRecreate = false)
        }
    }

    private fun closeSessionSafely(session: WebPoTokenSession) {
        val posted = runCatching {
            val mainLooper = Looper.getMainLooper()
            if (mainLooper != null && Looper.myLooper() != mainLooper) {
                Handler(mainLooper).post {
                    runCatching { session.close() }
                }
                true
            } else {
                false
            }
        }
            .getOrDefault(false)

        if (!posted) {
            runCatching { session.close() }
        }
    }

    private fun <T> runBounded(block: suspend () -> T): T = runBlocking {
        val deferred =
            CoroutineScope(Dispatchers.IO).async {
                block()
            }
        try {
            withTimeout(generationTimeoutMs) {
                deferred.await()
            }
        } catch (throwable: Throwable) {
            deferred.cancel()
            throw throwable
        }
    }

    /**
     * @param forceRecreate whether to force the recreation of [webPoTokenGenerator], to be used in
     *   case the current [webPoTokenGenerator] threw an error last time
     *   [PoTokenGenerator.getWebClientPoToken] was called
     */
    private fun getWebClientPoTokenLocked(videoId: String, forceRecreate: Boolean): PoTokenResult? {
        val existingGenerator = webPoTokenGenerator
        val shouldRecreate =
            existingGenerator == null ||
                forceRecreate ||
                existingGenerator.isExpired() ||
                webPoTokenVisitorData == null ||
                poToken == null

        if (!shouldRecreate) {
            poToken?.let {
                return it
            }
        }

        var candidateSession: WebPoTokenSession? = existingGenerator
        try {
            if (shouldRecreate) {
                existingGenerator?.let { closeSessionSafely(it) }
                webPoTokenGenerator = null
                webPoTokenVisitorData = null
                poToken = null
                candidateSession = null

                runBounded {
                    val fetchedVisitorData = visitorDataProvider()
                    val createdSession = sessionFactory()
                    candidateSession = createdSession
                    webPoTokenVisitorData = fetchedVisitorData
                    webPoTokenGenerator = createdSession
                }
            }

            val poTokenGenerator = webPoTokenGenerator!!
            val visitorData = webPoTokenVisitorData!!

            val generatedToken = runBounded {
                poTokenGenerator.generatePoToken(videoId)
            }

            if (BuildConfig.DEBUG) {
                runCatching {
                    Log.d(
                        TAG,
                        "poToken for $videoId: $generatedToken, visitor_data=$visitorData",
                    )
                }
            }

            return PoTokenResult(visitorData, generatedToken, generatedToken).also {
                poToken = it
            }
        } catch (throwable: Throwable) {
            candidateSession?.let { closeSessionSafely(it) }
            webPoTokenGenerator?.let {
                if (it !== candidateSession) {
                    closeSessionSafely(it)
                }
            }
            webPoTokenGenerator = null
            webPoTokenVisitorData = null
            poToken = null

            return if (!forceRecreate) {
                runCatching { Log.e(TAG, "Failed to obtain poToken, retrying", throwable) }
                getWebClientPoTokenLocked(videoId = videoId, forceRecreate = true)
            } else {
                runCatching { Log.e(TAG, "Failed to obtain poToken after recreation", throwable) }
                null
            }
        }
    }

    companion object {
        const val DEFAULT_GENERATION_TIMEOUT_MS = 10_000L
        val instance: PoTokenGenerator = PoTokenGenerator()
    }

    internal fun configureForTesting(
        supportsWebView: () -> Boolean = { true },
        visitorData: suspend () -> String,
        session: suspend () -> WebPoTokenSession,
        timeoutMs: Long = DEFAULT_GENERATION_TIMEOUT_MS,
    ) {
        synchronized(WebPoTokenGenLock) {
            webPoTokenGenerator?.let { runCatching { it.close() } }
            webPoTokenGenerator = null
            webPoTokenVisitorData = null
            poToken = null
            supportsWebViewProvider = supportsWebView
            visitorDataProvider = visitorData
            sessionFactory = session
            generationTimeoutMs = timeoutMs
        }
    }
}
