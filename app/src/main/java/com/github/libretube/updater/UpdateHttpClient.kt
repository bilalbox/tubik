package com.github.libretube.updater

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Provides a dedicated [OkHttpClient] for OTA manifest fetches and APK downloads.
 *
 * Unlike `RetrofitInstance.buildClient()`, this omits `HttpLoggingInterceptor` (so debug builds
 * never buffer or log ~48 MB APK streams) and disables SSL downgrade redirects.
 */
object UpdateHttpClient {
    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 60L
    private const val CALL_TIMEOUT_SECONDS = 300L

    val shared: OkHttpClient by lazy { build() }

    /** Builds a configured [OkHttpClient] enforcing HTTPS across all network hops. */
    fun build(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                if (!request.url.isHttps) {
                    throw IOException("Refusing non-HTTPS OTA request: ${request.url}")
                }
                chain.proceed(request)
            }
            .build()
}
