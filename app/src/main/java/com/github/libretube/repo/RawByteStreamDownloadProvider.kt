package com.github.libretube.repo

import android.util.Log
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.extensions.TAG
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.player.StreamTransportContext
import java.net.HttpURLConnection
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Duration
import kotlin.io.path.fileSize
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSink
import okio.IOException
import okio.source

/** Download from RAW HTTP stream. */
class RawByteStreamDownloadProvider(
    val url: HttpUrl,
    private val transportContext: StreamTransportContext? = null,
    private val httpClient: OkHttpClient = transportContext?.httpClient ?: buildDefaultHttpClient(),
) : DownloadProvider {

    private sealed interface ConnectionResult {
        data class Success(val response: Response, val isFullBody: Boolean) : ConnectionResult

        data object Completed : ConnectionResult

        data object Failed : ConnectionResult
    }

    override suspend fun downloadNextChunk(
        item: DownloadItem,
        sink: BufferedSink,
    ): DownloadProgressResult {
        val startByteOffset = if (Files.exists(item.path)) item.path.fileSize() else 0L
        if (item.downloadSize > 0L && startByteOffset >= item.downloadSize) {
            return DownloadProgressResult.DownloadComplete
        }

        val conn = startConnection(url, startByteOffset, item)
        val (response, isFullBody) =
            when (conn) {
                ConnectionResult.Completed -> return DownloadProgressResult.DownloadComplete
                ConnectionResult.Failed -> return DownloadProgressResult.Failed
                is ConnectionResult.Success -> conn.response to conn.isFullBody
            }

        if (isFullBody && startByteOffset > 0L) {
            truncateExistingFile(item, sink)
        }

        val totalRead = streamBodyToSink(response, sink)
        return computeProgressResult(isFullBody, item, startByteOffset, totalRead)
    }

    private suspend fun truncateExistingFile(item: DownloadItem, sink: BufferedSink) {
        withContext(Dispatchers.IO) {
            sink.flush()
            Files.newByteChannel(
                    item.path,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                )
                .close()
        }
    }

    private suspend fun streamBodyToSink(response: Response, sink: BufferedSink): Long {
        val body = response.body
        val sourceByte = body.byteStream().source()
        var totalRead = 0L
        var lastRead = 0L
        try {
            while (
                sourceByte.read(sink.buffer, DownloadHelper.DOWNLOAD_CHUNK_SIZE).also {
                    lastRead = it
                } != -1L
            ) {
                sink.emit()
                totalRead += lastRead
            }
        } finally {
            withContext(Dispatchers.IO) {
                runCatching { sourceByte.close() }
                runCatching { body.close() }
                runCatching { response.close() }
            }
        }
        return totalRead
    }

    private fun computeProgressResult(
        isFullBody: Boolean,
        item: DownloadItem,
        startByteOffset: Long,
        totalRead: Long,
    ): DownloadProgressResult =
        when {
            isFullBody -> DownloadProgressResult.DownloadComplete
            item.downloadSize > 0L ->
                if (startByteOffset + totalRead >= item.downloadSize) {
                    DownloadProgressResult.DownloadComplete
                } else {
                    DownloadProgressResult.Progressed(totalRead)
                }
            totalRead == 0L -> DownloadProgressResult.DownloadComplete
            else -> DownloadProgressResult.Progressed(totalRead)
        }

    private suspend fun startConnection(
        url: HttpUrl,
        alreadyRead: Long,
        item: DownloadItem,
    ): ConnectionResult {
        val readLimit = item.downloadSize.takeIf { it > 0L }
        if (readLimit != null && alreadyRead >= readLimit) {
            return ConnectionResult.Completed
        }
        val request = buildRangeRequest(url, alreadyRead, readLimit)

        return withContext(Dispatchers.IO) {
            try {
                val response = httpClient.newCall(request).execute()
                handleResponse(response, alreadyRead, item)
            } catch (e: IOException) {
                runCatching { Log.e(TAG(), "IO error while downloading chunk: ${e.message}", e) }
                ConnectionResult.Failed
            }
        }
    }

    private fun buildRangeRequest(url: HttpUrl, alreadyRead: Long, readLimit: Long?): Request {
        val endByte =
            if (readLimit != null) {
                min(readLimit - 1L, alreadyRead + BYTES_PER_REQUEST - 1L)
            } else {
                alreadyRead + BYTES_PER_REQUEST - 1L
            }
        val requestBuilder = Request.Builder().url(url).method("GET", null)
        transportContext?.headers?.forEach { (key, value) -> requestBuilder.header(key, value) }
        val userAgent = transportContext?.resolveUserAgent(url.toString()) ?: USER_AGENT
        return requestBuilder
            .header("User-Agent", userAgent)
            .header("Range", "bytes=$alreadyRead-$endByte")
            .build()
    }

    private suspend fun handleResponse(
        response: Response,
        alreadyRead: Long,
        item: DownloadItem,
    ): ConnectionResult {
        if (response.code == HTTP_RANGE_NOT_SATISFIABLE) {
            response.close()
            // 416 Range Not Satisfiable: file is already fully downloaded
            return if (alreadyRead > 0L) ConnectionResult.Completed else ConnectionResult.Failed
        }

        return if (!response.isSuccessful) {
            runCatching {
                Log.e(TAG(), "HTTP error ${response.code} downloading: ${response.message}")
            }
            response.close()
            ConnectionResult.Failed
        } else {
            val isFullBody = response.code == HttpURLConnection.HTTP_OK
            updateItemDownloadSize(response, isFullBody, item)
            ConnectionResult.Success(response, isFullBody)
        }
    }

    private suspend fun updateItemDownloadSize(
        response: Response,
        isFullBody: Boolean,
        item: DownloadItem,
    ) {
        val detectedTotal =
            if (isFullBody) {
                response.body.contentLength()
            } else {
                // Parse real content length from Content-Range header (e.g. "bytes
                // 0-524287/15234567")
                response.header("Content-Range")?.substringAfterLast("/", "")?.toLongOrNull() ?: -1L
            }
        if (detectedTotal > 0L && detectedTotal != item.downloadSize) {
            item.downloadSize = detectedTotal
            runCatching { DatabaseHolder.Database.downloadDao().updateDownloadItem(item) }
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0"
        private const val BYTES_PER_REQUEST = 512 * 1024L
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        private fun buildDefaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(DownloadHelper.DEFAULT_TIMEOUT.toLong()))
                .readTimeout(Duration.ofMillis(DownloadHelper.DEFAULT_TIMEOUT.toLong()))
                .retryOnConnectionFailure(true)
                .build()
    }
}
