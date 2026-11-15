package com.github.libretube.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import sun.misc.Unsafe

/**
 * Creates a [DataSpec] instance for pure-JVM unit tests where `android.net.Uri` stubs are
 * unavailable. Stores [url] in [DataSpec.key] so tests can read it via `dataSpecUriToString = {
 * it.key!! }`.
 */
@OptIn(UnstableApi::class)
internal fun createTestDataSpec(
    url: String,
    position: Long = 0L,
    length: Long = C.LENGTH_UNSET.toLong(),
): DataSpec {
    val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
    val unsafe = unsafeField.get(null) as Unsafe
    val spec = unsafe.allocateInstance(DataSpec::class.java) as DataSpec

    fun setField(name: String, value: Any?) {
        DataSpec::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(spec, value)
        }
    }

    setField("httpMethod", DataSpec.HTTP_METHOD_GET)
    setField("httpRequestHeaders", emptyMap<String, String>())
    setField("uriPositionOffset", 0L)
    setField("absoluteStreamPosition", position)
    setField("position", position)
    setField("length", length)
    setField("key", url)
    setField("flags", 0)
    return spec
}
