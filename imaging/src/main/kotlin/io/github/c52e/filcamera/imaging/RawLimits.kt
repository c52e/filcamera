package io.github.c52e.filcamera.imaging

/** Product ceiling; device buffer and allocation limits are checked separately. */
object RawLimits {
    const val MAX_PIXELS = 60_000_000
    const val MAX_DIMENSION = 16_384
    fun supports(width: Int, height: Int): Boolean =
        width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION &&
            width.toLong() * height <= MAX_PIXELS
}
