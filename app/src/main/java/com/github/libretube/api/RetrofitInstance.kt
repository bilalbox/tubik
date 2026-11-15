package com.github.libretube.api

import com.github.libretube.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create

typealias HeadersAccessor = () -> Map<String, String>

object RetrofitInstance {
    val kotlinxConverterFactory =
        JsonHelper.json.asConverterFactory("application/json".toMediaType())

    // the url provided here isn't actually used anywhere in the external api
    val externalApi = buildRetrofitInstance<ExternalApi>("https://localhost/")

    /**
     * Build a new [OkHttpClient] with logging support.
     *
     * @param headersAccessor Method that returns a list of headers to inject into each request. Can
     *   e.g. be used for injecting authorization tokens to the client.
     */
    fun buildClient(headersAccessor: HeadersAccessor = { mapOf() }): OkHttpClient {
        val httpClient = OkHttpClient().newBuilder()

        // add provided headers to all requests
        httpClient.addInterceptor { interceptorChain ->
            val request = interceptorChain.request().newBuilder()
            for ((key, value) in headersAccessor()) {
                request.addHeader(key, value)
            }

            interceptorChain.proceed(request.build())
        }

        if (BuildConfig.DEBUG) {
            val loggingInterceptor =
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                }

            httpClient.addInterceptor(loggingInterceptor)
        }

        return httpClient.build()
    }

    inline fun <reified T : Any> buildRetrofitInstance(
        apiUrl: String,
        noinline headersAccessor: HeadersAccessor = { mapOf() },
    ): T =
        Retrofit.Builder()
            .baseUrl(apiUrl)
            .client(buildClient(headersAccessor))
            .addConverterFactory(kotlinxConverterFactory)
            .build()
            .create<T>()
}
