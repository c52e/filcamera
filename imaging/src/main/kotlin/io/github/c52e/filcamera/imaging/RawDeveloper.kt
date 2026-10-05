package io.github.c52e.filcamera.imaging

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** CFA positions are relative to the full pixel array, not the crop. */
enum class BayerPattern(val channels: IntArray) {
    RGGB(intArrayOf(0,1,2,3)), GRBG(intArrayOf(1,0,3,2)),
    GBRG(intArrayOf(1,3,0,2)), BGGR(intArrayOf(3,1,2,0))
}
data class RawFrame(
    val width: Int, val height: Int, val rowStride: Int, val pixelStride: Int,
    val bytes: ByteBuffer, val pattern: BayerPattern,
    val black: FloatArray, val white: Float,
    /** R, green on even row, green on odd row, B; before the color transform. */
    val gains: FloatArray, val cameraToSrgb: FloatArray,
    val left: Int = 0, val top: Int = 0, val right: Int = width, val bottom: Int = height,
    val shading: ShadingGrid? = null
)
interface RawDeveloper { fun develop(frame: RawFrame): LinearImage }

/** Shared checks also protect absolute ByteBuffer indexing from integer overflow. */
fun RawFrame.validate() {
    require(width > 1 && height > 1 && pixelStride >= 2)
    require(rowStride.toLong() >= (width-1L)*pixelStride+2)
    require(bytes.remaining().toLong() >= (height-1L)*rowStride+(width-1L)*pixelStride+2)
    require(left >= 0 && top >= 0 && right <= width && bottom <= height && right-left >= 2 && bottom-top >= 2)
    require(black.size == 4 && gains.size == 4 && cameraToSrgb.size == 9)
    require(white.isFinite() && black.all { it.isFinite() && white > it })
    require(gains.all { it.isFinite() && it > 0 } && cameraToSrgb.all { it.isFinite() })
}

/** Bounded area-bin Bayer developer. Each output pixel integrates complete 2x2 CFA cells.
 * This deliberately trades spatial resolution for memory and robustness; it is not a full demosaic.
 */
class BinnedRawDeveloper(private val maxPixels: Int = 3_000_000) : RawDeveloper {
    init { require(maxPixels in 1..12_000_000) }
    override fun develop(frame: RawFrame): LinearImage = with(frame) {
        require(width > 1 && height > 1 && pixelStride >= 2)
        require(rowStride.toLong() >= (width-1L)*pixelStride+2)
        require(bytes.remaining().toLong() >= (height-1L)*rowStride+(width-1L)*pixelStride+2)
        require(left >= 0 && top >= 0 && right <= width && bottom <= height && right-left >= 2 && bottom-top >= 2)
        require(black.size == 4 && gains.size == 4 && cameraToSrgb.size == 9)
        require(white.isFinite() && black.all { it.isFinite() && white > it })
        require(gains.all { it.isFinite() && it > 0 } && cameraToSrgb.all { it.isFinite() })
        val src = bytes.slice().order(ByteOrder.LITTLE_ENDIAN)
        val cropWidth = right-left; val cropHeight = bottom-top
        var step = 2
        while ((cropWidth/step).toLong() * (cropHeight/step) > maxPixels) step += 2
        val w = cropWidth/step; val h = cropHeight/step
        require(w > 0 && h > 0)
        val dst = FloatArray(w*h*3)
        val matrix = ColorMath.multiply(ColorMath.srgbTo2020, cameraToSrgb)
        val sums = FloatArray(4)
        val count = (step*step/4).toFloat()
        for (oy in 0 until h) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            for (ox in 0 until w) {
                sums.fill(0f)
                for (dy in 0 until step) for (dx in 0 until step) {
                    val sx = left+ox*step+dx; val sy = top+oy*step+dy
                    val site = (sy and 1)*2 + (sx and 1)
                    val channel = pattern.channels[site]
                    val raw = src.getShort(sy*rowStride+sx*pixelStride).toInt() and 65535
                    sums[channel] += max(0f, (raw-black[site])/(white-black[site])) * gains[channel]
                }
                val r = sums[0]/count; val g = (sums[1]+sums[2])/(2*count); val b = sums[3]/count
                val i = (oy*w+ox)*3
                for (c in 0..2) dst[i+c] = matrix[c*3]*r + matrix[c*3+1]*g + matrix[c*3+2]*b
            }
        }
        LinearImage(w,h,dst)
    }
}
