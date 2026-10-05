package io.github.c52e.filcamera.imaging

/** Single-threaded bounded ownership transfer. Unmatched images are always disposed. */
class TimestampPairer<I, R>(private val capacity: Int = 2, private val dispose: (I) -> Unit, private val paired: (I,R) -> Unit) {
    private val images = linkedMapOf<Long,I>()
    private val results = linkedMapOf<Long,R>()
    init { require(capacity > 0) }
    fun image(timestamp: Long, image: I) {
        val result = results.remove(timestamp)
        if (result != null) { paired(image,result); return }
        images.put(timestamp,image)?.let(dispose)
        while (images.size > capacity) images.remove(images.keys.first())?.let(dispose)
    }
    fun result(timestamp: Long, result: R) {
        val image = images.remove(timestamp)
        if (image != null) { paired(image,result); return }
        results[timestamp] = result
        while (results.size > capacity) results.remove(results.keys.first())
    }
    fun clear() { images.values.forEach(dispose); images.clear(); results.clear() }
}
