package com.github.libretube.util

import com.github.libretube.security.NativeTransportBridge
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

class NewPipeDownloaderImpl(
    private val client: OkHttpClient = httpClient,
    val userAgentRegistry: ClientUserAgentRegistry = ClientUserAgentRegistry(),
) : Downloader() {

    @Volatile private var cachedIosVisitorData: String? = null

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBuilder =
            okhttp3.Request.Builder()
                .method(httpMethod, dataToSend?.toRequestBody())
                .url(url)
                .addHeader("User-Agent", USER_AGENT)

        for ((headerKey, headerValues) in headers) {
            requestBuilder.removeHeader(headerKey)
            for (headerValue in headerValues) {
                requestBuilder.addHeader(headerKey, headerValue)
            }
        }
        val builtRequest = requestBuilder.build()
        val response = client.newCall(builtRequest).execute()

        return when (response.code) {
            429 -> {
                response.close()
                throw ReCaptchaException("reCaptcha Challenge requested", url)
            }

            else -> {
                val responseBodyToReturn = response.body.string()
                val playerPathToken = NativeTransportBridge.playerPathToken()
                val isPlayerRequest =
                    dataToSend != null &&
                        playerPathToken.isNotEmpty() &&
                        url.contains(playerPathToken)
                val videoId = if (isPlayerRequest) extractVideoId(dataToSend) else null

                if (shouldRetryPlayerWithFallbackClient(url, dataToSend, responseBodyToReturn)) {
                    retryPlayerWithFallbackClients(url, dataToSend!!, videoId)?.let {
                        return it
                    }
                } else if (isPlayerRequest) {
                    val primaryUserAgent =
                        resolvePrimaryRequestUserAgent(
                            dataToSend = dataToSend,
                            headerUserAgent = builtRequest.header("User-Agent"),
                        )
                    userAgentRegistry.record(videoId, primaryUserAgent)
                }
                Response(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    responseBodyToReturn,
                    response.request.url.toString(),
                )
            }
        }
    }

    internal fun shouldRetryPlayerWithFallbackClient(
        url: String,
        dataToSend: ByteArray?,
        responseBody: String,
    ): Boolean {
        val playerPathToken = NativeTransportBridge.playerPathToken()
        if (dataToSend == null || playerPathToken.isEmpty() || !url.contains(playerPathToken)) {
            return false
        }
        return try {
            val json = Json.parseToJsonElement(responseBody).jsonObject
            !hasUsablePlayableStreams(json)
        } catch (_: Exception) {
            false
        }
    }

    internal fun hasUsablePlayableStreams(responseJson: JsonObject): Boolean {
        val status =
            responseJson[NativeTransportBridge.playabilityStatusKey()]
                ?.jsonObject
                ?.get("status")
                ?.jsonPrimitive
                ?.contentOrNull
        if (status == null || !status.equals("OK", ignoreCase = true)) {
            return false
        }

        val streamingData =
            responseJson[NativeTransportBridge.streamingDataKey()]?.jsonObject ?: return false
        val hlsUrl = streamingData["hlsManifestUrl"]?.jsonPrimitive?.contentOrNull
        if (!hlsUrl.isNullOrBlank()) return true
        val dashUrl = streamingData["dashManifestUrl"]?.jsonPrimitive?.contentOrNull
        if (!dashUrl.isNullOrBlank()) return true

        fun hasPlayableFormat(arrayKey: String): Boolean {
            val array = streamingData[arrayKey] as? JsonArray ?: return false
            return array.any { element ->
                val obj = element as? JsonObject ?: return@any false
                val directUrl = obj["url"]?.jsonPrimitive?.contentOrNull
                val sigCipher =
                    obj["signatureCipher"]?.jsonPrimitive?.contentOrNull
                        ?: obj["cipher"]?.jsonPrimitive?.contentOrNull
                !directUrl.isNullOrBlank() || !sigCipher.isNullOrBlank()
            }
        }

        return hasPlayableFormat("adaptiveFormats") || hasPlayableFormat("formats")
    }

    private fun extractVideoId(bodyBytes: ByteArray): String? {
        return try {
            val payload =
                Json.parseToJsonElement(String(bodyBytes, StandardCharsets.UTF_8)).jsonObject
            payload["videoId"]?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) {
            null
        }
    }

    private fun resolvePrimaryRequestUserAgent(
        dataToSend: ByteArray,
        headerUserAgent: String?,
    ): String {
        val clientName =
            try {
                val payload =
                    Json.parseToJsonElement(String(dataToSend, StandardCharsets.UTF_8)).jsonObject
                payload["context"]
                    ?.jsonObject
                    ?.get("client")
                    ?.jsonObject
                    ?.get("clientName")
                    ?.jsonPrimitive
                    ?.contentOrNull
            } catch (_: Exception) {
                null
            }
        return userAgentForClientName(clientName) ?: headerUserAgent ?: USER_AGENT
    }

    private fun retryPlayerWithFallbackClients(
        url: String,
        originalBodyBytes: ByteArray,
        videoId: String?,
    ): Response? {
        // Prefer ANDROID_KIDS over untokened IOS: IOS streams often fail with HTTP 403
        // on mid-stream byte ranges without an iOS PoToken, whereas ANDROID_KIDS succeeds.
        return retryPlayerWithAndroidKidsClient(url, originalBodyBytes, videoId)
            ?: retryPlayerWithIosClient(url, originalBodyBytes, videoId)
    }

    private fun retryPlayerWithIosClient(
        url: String,
        originalBodyBytes: ByteArray,
        videoId: String?,
    ): Response? {
        return try {
            val payload =
                Json.parseToJsonElement(String(originalBodyBytes, StandardCharsets.UTF_8))
                    .jsonObject
            val clientObj = payload["context"]?.jsonObject?.get("client")?.jsonObject ?: return null
            val hl = clientObj["hl"]?.jsonPrimitive?.contentOrNull ?: "en"
            val gl = clientObj["gl"]?.jsonPrimitive?.contentOrNull ?: "US"

            val visitorData =
                cachedIosVisitorData
                    ?: fetchIosVisitorData(hl, gl)?.also {
                        cachedIosVisitorData = it
                    }

            val updatedPayload = buildIosClientPayload(payload, visitorData) ?: return null
            executePlayerFallbackRequest(url, updatedPayload, IOS_USER_AGENT, videoId)
        } catch (_: Exception) {
            null
        }
    }

    private fun retryPlayerWithAndroidKidsClient(
        url: String,
        originalBodyBytes: ByteArray,
        videoId: String?,
    ): Response? {
        return try {
            val payload =
                Json.parseToJsonElement(String(originalBodyBytes, StandardCharsets.UTF_8))
                    .jsonObject
            val updatedPayload = buildAndroidKidsClientPayload(payload) ?: return null
            executePlayerFallbackRequest(url, updatedPayload, ANDROID_KIDS_USER_AGENT, videoId)
        } catch (_: Exception) {
            null
        }
    }

    internal fun buildIosClientPayload(payload: JsonObject, visitorData: String?): JsonObject? {
        val context = payload["context"]?.jsonObject ?: return null
        val clientObj = context["client"]?.jsonObject ?: return null
        val updatedClient = buildJsonObject {
            for ((k, v) in clientObj) put(k, v)
            put("clientName", JsonPrimitive(IOS_CLIENT_NAME))
            put("clientVersion", JsonPrimitive(IOS_CLIENT_VERSION))
            put("deviceMake", JsonPrimitive("Apple"))
            put("deviceModel", JsonPrimitive(IOS_DEVICE_MODEL))
            put("osName", JsonPrimitive("iOS"))
            put("osVersion", JsonPrimitive(IOS_OS_VERSION))
            if (!visitorData.isNullOrBlank()) {
                put("visitorData", JsonPrimitive(visitorData))
            }
        }
        val updatedContext = buildJsonObject {
            for ((k, v) in context) put(k, v)
            put("client", updatedClient)
        }
        return buildJsonObject {
            for ((k, v) in payload) put(k, v)
            put("context", updatedContext)
        }
    }

    internal fun buildAndroidKidsClientPayload(payload: JsonObject): JsonObject? {
        val context = payload["context"]?.jsonObject ?: return null
        val clientObj = context["client"]?.jsonObject ?: return null
        val updatedClient = buildJsonObject {
            for ((k, v) in clientObj) put(k, v)
            put("clientName", JsonPrimitive(ANDROID_KIDS_CLIENT_NAME))
            put("clientVersion", JsonPrimitive(ANDROID_KIDS_CLIENT_VERSION))
            put("osName", JsonPrimitive("Android"))
            put("osVersion", JsonPrimitive("15"))
            put("androidSdkVersion", JsonPrimitive(35))
        }
        val updatedContext = buildJsonObject {
            for ((k, v) in context) put(k, v)
            put("client", updatedClient)
        }
        return buildJsonObject {
            for ((k, v) in payload) put(k, v)
            put("context", updatedContext)
        }
    }

    private fun executePlayerFallbackRequest(
        url: String,
        payload: JsonObject,
        userAgent: String,
        videoId: String?,
    ): Response? {
        val newBodyBytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
        val req =
            okhttp3.Request.Builder()
                .post(newBodyBytes.toRequestBody())
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", userAgent)
                .addHeader("X-Goog-Api-Format-Version", "2")
                .build()

        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bodyStr = resp.body.string()
            val json = Json.parseToJsonElement(bodyStr).jsonObject
            if (hasUsablePlayableStreams(json)) {
                val resolvedVideoId = videoId ?: payload["videoId"]?.jsonPrimitive?.contentOrNull
                userAgentRegistry.record(resolvedVideoId, userAgent)
                Response(
                    resp.code,
                    resp.message,
                    resp.headers.toMultimap(),
                    bodyStr,
                    resp.request.url.toString(),
                )
            } else {
                null
            }
        }
    }

    private fun fetchIosVisitorData(hl: String, gl: String): String? {
        return try {
            val root = buildJsonObject {
                put(
                    "context",
                    buildJsonObject {
                        put(
                            "client",
                            buildJsonObject {
                                put("clientName", JsonPrimitive(IOS_CLIENT_NAME))
                                put("clientVersion", JsonPrimitive(IOS_CLIENT_VERSION))
                                put("deviceMake", JsonPrimitive("Apple"))
                                put("deviceModel", JsonPrimitive(IOS_DEVICE_MODEL))
                                put("osName", JsonPrimitive("iOS"))
                                put("osVersion", JsonPrimitive(IOS_OS_VERSION))
                                put("hl", JsonPrimitive(hl))
                                put("gl", JsonPrimitive(gl))
                            },
                        )
                    },
                )
            }
            val bodyBytes = root.toString().toByteArray(StandardCharsets.UTF_8)
            val req =
                okhttp3.Request.Builder()
                    .post(bodyBytes.toRequestBody())
                    .url(NativeTransportBridge.visitorIdEndpoint())
                    .addHeader("Content-Type", "application/json")
                    .addHeader("User-Agent", IOS_USER_AGENT)
                    .addHeader("X-Goog-Api-Format-Version", "2")
                    .build()
            client.newCall(req).execute().use { resp ->
                val json = Json.parseToJsonElement(resp.body.string()).jsonObject
                json["responseContext"]
                    ?.jsonObject
                    ?.get("visitorData")
                    ?.jsonPrimitive
                    ?.contentOrNull
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0"

        internal const val IOS_CLIENT_NAME = "IOS"
        internal const val IOS_CLIENT_VERSION = "21.03.2"
        internal const val IOS_DEVICE_MODEL = "iPhone16,2"
        internal const val IOS_OS_VERSION = "18.7.2.22H124"
        const val IOS_USER_AGENT =
            "com.google.ios.youtube/21.03.2(iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X; US)"

        internal const val ANDROID_KIDS_CLIENT_NAME = "ANDROID_KIDS"
        internal const val ANDROID_KIDS_CLIENT_VERSION = "9.44.2"
        const val ANDROID_KIDS_USER_AGENT =
            "com.google.android.apps.youtube.kids/9.44.2 (Linux; U; Android 15; US) gzip"

        const val VISIONOS_USER_AGENT =
            "com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; GB)"

        val sharedConnectionPool = ConnectionPool(10, 5, TimeUnit.MINUTES)

        val httpClient: OkHttpClient =
            OkHttpClient.Builder()
                .connectionPool(sharedConnectionPool)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()

        fun userAgentForClientName(clientName: String?): String? {
            return when (clientName?.uppercase()) {
                "IOS" -> IOS_USER_AGENT
                "ANDROID_KIDS" -> ANDROID_KIDS_USER_AGENT
                "WEB",
                "MWEB",
                "TVHTML5",
                "WEB_REMIX",
                "WEB_EMBEDDED_PLAYER",
                "WEB_CREATOR" -> USER_AGENT
                "VISIONOS" -> VISIONOS_USER_AGENT
                else -> null
            }
        }

        internal fun extractQueryParam(url: String, paramName: String): String? {
            val parsedUrl = url.toHttpUrlOrNull()
            return parsedUrl?.queryParameter(paramName) ?: extractRawQueryParam(url, paramName)
        }

        internal fun resolveUrlClientUserAgent(url: String?): String? {
            if (url.isNullOrBlank()) return null
            return userAgentForClientName(extractQueryParam(url, "c"))
        }

        private fun extractRawQueryParam(url: String, paramName: String): String? {
            val queryStart = url.indexOf('?')
            if (queryStart < 0 || queryStart + 1 >= url.length) return null
            val query = url.substring(queryStart + 1).substringBefore('#')
            return query.split('&').firstNotNullOfOrNull { pair ->
                val eqIdx = pair.indexOf('=')
                if (eqIdx > 0 && pair.substring(0, eqIdx) == paramName) {
                    pair.substring(eqIdx + 1)
                } else {
                    null
                }
            }
        }
    }
}
