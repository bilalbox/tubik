package com.github.libretube.repo

import android.util.Log
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.extensions.TAG
import com.github.libretube.helpers.DownloadHelper
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
class RawByteStreamDownloadProvider(val url: HttpUrl) : DownloadProvider {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(Duration.ofMillis(DownloadHelper.DEFAULT_TIMEOUT.toLong()))
            .readTimeout(Duration.ofMillis(DownloadHelper.DEFAULT_TIMEOUT.toLong()))
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder().header("User-Agent", USER_AGENT).build()
                chain.proceed(request)
            }
            .build()
    }

    private sealed interface ConnectionResult {
        data class Success(val response: Response) : ConnectionResult

        data object Completed : ConnectionResult

        data object Failed : ConnectionResult
    }

    override suspend fun downloadNextChunk(
        item: DownloadItem,
        sink: BufferedSink,
    ): DownloadProgressResult {
        val startByteOffset = item.path.fileSize()
        if (item.downloadSize > 0L && startByteOffset >= item.downloadSize) {
            return DownloadProgressResult.DownloadComplete
        }

        val conn = startConnection(url, startByteOffset, item)
        val response =
            when (conn) {
                ConnectionResult.Completed -> return DownloadProgressResult.DownloadComplete
                ConnectionResult.Failed -> return DownloadProgressResult.Failed
                is ConnectionResult.Success -> conn.response
            }

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
                try {
                    sourceByte.close()
                } catch (_: Exception) {}
                try {
                    body.close()
                } catch (_: Exception) {}
                try {
                    response.close()
                } catch (_: Exception) {}
            }
        }

        return if (item.downloadSize > 0L) {
            if (startByteOffset + totalRead >= item.downloadSize) {
                DownloadProgressResult.DownloadComplete
            } else {
                DownloadProgressResult.Progressed(totalRead)
            }
        } else {
            if (totalRead == 0L) {
                DownloadProgressResult.DownloadComplete
            } else {
                DownloadProgressResult.Progressed(totalRead)
            }
        }
    }

    private suspend fun startConnection(
        url: HttpUrl,
        alreadyRead: Long,
        item: DownloadItem,
    ): ConnectionResult {
        val readLimit = item.downloadSize.takeIf { it > 0L }
        val rangeHeader =
            if (readLimit != null) {
                if (alreadyRead >= readLimit) {
                    return ConnectionResult.Completed
                }
                val endByte = min(readLimit - 1L, alreadyRead + BYTES_PER_REQUEST - 1L)
                "bytes=$alreadyRead-$endByte"
            } else {
                val endByte = alreadyRead + BYTES_PER_REQUEST - 1L
                "bytes=$alreadyRead-$endByte"
            }

        val request =
            Request.Builder().url(url).method("GET", null).header("Range", rangeHeader).build()

        return withContext(Dispatchers.IO) {
            try {
                val call = httpClient.newCall(request)
                val response = call.execute()

                if (response.code == 416) {
                    response.close()
                    // 416 Range Not Satisfiable: file is already fully downloaded
                    return@withContext if (alreadyRead > 0L) {
                        ConnectionResult.Completed
                    } else {
                        ConnectionResult.Failed
                    }
                }

                if (!response.isSuccessful) {
                    Log.e(TAG(), "HTTP error ${response.code} downloading: ${response.message}")
                    response.close()
                    return@withContext ConnectionResult.Failed
                }

                // Parse real content length from Content-Range header if present (e.g. "bytes
                // 0-524287/15234567")
                response.header("Content-Range")?.let { contentRange ->
                    val total = contentRange.substringAfterLast("/", "").toLongOrNull()
                    if (total != null && total > 0L && total != item.downloadSize) {
                        item.downloadSize = total
                        try {
                            DatabaseHolder.Database.downloadDao().updateDownloadItem(item)
                        } catch (_: Throwable) {
                            // Database might not be initialized in test environments
                        }
                    }
                }

                ConnectionResult.Success(response)
            } catch (e: IOException) {
                Log.e(TAG(), "IO error while downloading chunk: ${e.message}", e)
                ConnectionResult.Failed
            }
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0"
        private const val BYTES_PER_REQUEST = 512 * 1024L
    }
}
