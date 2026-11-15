package com.github.libretube.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import kotlin.math.min
import okhttp3.ConnectionPool
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * An [HttpDataSource] backed by an explicit [StreamTransportContext] that propagates the
 * video-scoped `User-Agent`, transport headers, and shared OkHttp connection pool for media segment
 * requests.
 */
@OptIn(UnstableApi::class)
class OkHttpMediaDataSource
internal constructor(
    private val transportContext: StreamTransportContext = StreamTransportContext(),
    private val defaultRequestProperties: HttpDataSource.RequestProperties? = null,
    private val dataSpecUriToString: (DataSpec) -> String = { it.uri.toString() },
) : BaseDataSource(true), HttpDataSource {

    constructor(
        transportContext: StreamTransportContext = StreamTransportContext(),
        defaultRequestProperties: HttpDataSource.RequestProperties? = null,
    ) : this(
        transportContext = transportContext,
        defaultRequestProperties = defaultRequestProperties,
        dataSpecUriToString = { it.uri.toString() },
    )

    class Factory
    internal constructor(
        private val transportContext: StreamTransportContext = StreamTransportContext(),
        private val dataSpecUriToString: (DataSpec) -> String = { it.uri.toString() },
    ) : HttpDataSource.Factory {
        constructor(
            transportContext: StreamTransportContext = StreamTransportContext(),
        ) : this(
            transportContext = transportContext,
            dataSpecUriToString = { it.uri.toString() },
        )

        private val defaultRequestProperties = HttpDataSource.RequestProperties()

        val connectionPool: ConnectionPool
            get() = transportContext.httpClient.connectionPool

        override fun setDefaultRequestProperties(
            defaultRequestProperties: Map<String, String>,
        ): HttpDataSource.Factory {
            this.defaultRequestProperties.clearAndSet(defaultRequestProperties)
            return this
        }

        override fun createDataSource(): OkHttpMediaDataSource {
            return OkHttpMediaDataSource(
                transportContext = transportContext,
                defaultRequestProperties = defaultRequestProperties,
                dataSpecUriToString = dataSpecUriToString,
            )
        }
    }

    private val requestProperties = HttpDataSource.RequestProperties()
    private var dataSpec: DataSpec? = null
    private var response: Response? = null
    private var responseStream: InputStream? = null
    private var opened = false
    private var bytesToRead: Long = 0L
    private var bytesRead: Long = 0L

    override fun getUri(): Uri? {
        val currentResponse = response ?: return dataSpec?.uri
        return runCatching { Uri.parse(currentResponse.request.url.toString()) }.getOrNull()
            ?: dataSpec?.uri
    }

    override fun getResponseCode(): Int {
        val code = response?.code ?: -1
        return if (code <= 0) -1 else code
    }

    override fun getResponseHeaders(): Map<String, List<String>> {
        return response?.headers?.toMultimap() ?: emptyMap()
    }

    override fun setRequestProperty(name: String, value: String) {
        requestProperties.set(name, value)
    }

    override fun clearRequestProperty(name: String) {
        requestProperties.remove(name)
    }

    override fun clearAllRequestProperties() {
        requestProperties.clear()
    }

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        transferInitializing(dataSpec)
        val length =
            openInternal(
                url = dataSpecUriToString(dataSpec),
                httpMethod = dataSpec.httpMethod,
                httpBody = dataSpec.httpBody,
                position = dataSpec.position,
                length = dataSpec.length,
                requestHeaders = dataSpec.httpRequestHeaders,
                dataSpec = dataSpec,
            )
        opened = true
        transferStarted(dataSpec)
        return length
    }

    private fun openInternal(
        url: String,
        httpMethod: Int,
        httpBody: ByteArray?,
        position: Long,
        length: Long,
        requestHeaders: Map<String, String>,
        dataSpec: DataSpec,
    ): Long {
        bytesRead = 0L
        bytesToRead = 0L

        val request =
            buildRequest(
                url = url,
                httpMethod = httpMethod,
                httpBody = httpBody,
                position = position,
                length = length,
                requestHeaders = requestHeaders,
            )

        val executedResponse =
            try {
                transportContext.httpClient.newCall(request).execute()
            } catch (e: IOException) {
                throw HttpDataSource.HttpDataSourceException(
                    e,
                    dataSpec,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN,
                )
            }

        this.response = executedResponse
        val body = executedResponse.body
        this.responseStream = body.byteStream()
        val responseCode = executedResponse.code

        if (!executedResponse.isSuccessful) {
            val errorBody = runCatching { body.bytes() }.getOrDefault(ByteArray(0))
            val headers = executedResponse.headers.toMultimap()
            closeConnectionQuietly()
            throw HttpDataSource.InvalidResponseCodeException(
                responseCode,
                executedResponse.message,
                null,
                headers,
                dataSpec,
                errorBody,
            )
        }

        val bytesToSkip = if (responseCode == 200 && position != 0L) position else 0L
        if (bytesToSkip > 0L) {
            try {
                skipFully(bytesToSkip, dataSpec)
            } catch (e: IOException) {
                closeConnectionQuietly()
                if (e is HttpDataSource.HttpDataSourceException) throw e
                throw HttpDataSource.HttpDataSourceException(
                    e,
                    dataSpec,
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN,
                )
            }
        }

        bytesToRead =
            if (length != C.LENGTH_UNSET.toLong()) {
                length
            } else {
                val contentLength = body.contentLength()
                if (contentLength != -1L) contentLength - bytesToSkip else C.LENGTH_UNSET.toLong()
            }

        return bytesToRead
    }

    private fun buildRequest(
        url: String,
        httpMethod: Int,
        httpBody: ByteArray?,
        position: Long,
        length: Long,
        requestHeaders: Map<String, String>,
    ): Request {
        val builder = Request.Builder().url(url)

        val combinedHeaders = LinkedHashMap<String, String>()
        combinedHeaders.putAll(transportContext.headers)
        defaultRequestProperties?.snapshot?.let { combinedHeaders.putAll(it) }
        combinedHeaders.putAll(requestProperties.snapshot)
        combinedHeaders.putAll(requestHeaders)

        for ((k, v) in combinedHeaders) {
            builder.header(k, v)
        }

        val rangeHeader = buildRangeHeader(position, length)
        if (rangeHeader != null) {
            builder.header("Range", rangeHeader)
            if (!combinedHeaders.keys.any { it.equals("Accept-Encoding", ignoreCase = true) }) {
                builder.header("Accept-Encoding", "identity")
            }
        }

        val resolvedUserAgent = transportContext.resolveUserAgent(url)
        builder.header("User-Agent", resolvedUserAgent)

        val methodStr = DataSpec.getStringForHttpMethod(httpMethod)
        val body =
            if (httpBody != null) {
                httpBody.toRequestBody()
            } else if (httpMethod == DataSpec.HTTP_METHOD_POST) {
                ByteArray(0).toRequestBody()
            } else {
                null
            }
        builder.method(methodStr, body)

        return builder.build()
    }

    private fun buildRangeHeader(position: Long, length: Long): String? {
        if (position == 0L && length == C.LENGTH_UNSET.toLong()) {
            return null
        }
        val sb = StringBuilder("bytes=").append(position).append('-')
        if (length != C.LENGTH_UNSET.toLong()) {
            sb.append(position + length - 1L)
        }
        return sb.toString()
    }

    private fun skipFully(bytesToSkip: Long, dataSpec: DataSpec) {
        val stream = responseStream ?: return
        var remaining = bytesToSkip
        val skipBuffer = ByteArray(4096)
        while (remaining > 0L) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedIOException()
            }
            val toRead = min(remaining, skipBuffer.size.toLong()).toInt()
            val read = stream.read(skipBuffer, 0, toRead)
            if (read == -1) {
                throw HttpDataSource.HttpDataSourceException(
                    EOFException(),
                    dataSpec,
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN,
                )
            }
            remaining -= read.toLong()
            bytesTransferred(read)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            val bytesRemaining = bytesToRead - bytesRead
            if (bytesRemaining == 0L) {
                return C.RESULT_END_OF_INPUT
            }
        }

        val stream = responseStream ?: return C.RESULT_END_OF_INPUT
        val maxToRead =
            if (bytesToRead == C.LENGTH_UNSET.toLong()) {
                length
            } else {
                min(length.toLong(), bytesToRead - bytesRead).toInt()
            }

        val read =
            try {
                stream.read(buffer, offset, maxToRead)
            } catch (e: IOException) {
                throw HttpDataSource.HttpDataSourceException(
                    e,
                    dataSpec ?: DataSpec(Uri.EMPTY),
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    HttpDataSource.HttpDataSourceException.TYPE_READ,
                )
            }

        if (read == -1) {
            return C.RESULT_END_OF_INPUT
        }

        bytesRead += read.toLong()
        bytesTransferred(read)
        return read
    }

    override fun close() {
        try {
            closeConnectionQuietly()
        } finally {
            if (opened) {
                opened = false
                transferEnded()
            }
            dataSpec = null
        }
    }

    private fun closeConnectionQuietly() {
        runCatching { responseStream?.close() }
        responseStream = null
        runCatching { response?.close() }
        response = null
    }

    private class InterruptedIOException : IOException("Thread interrupted while skipping bytes")
}
