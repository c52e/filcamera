package io.github.c52e.filcamera.imaging

import kotlin.math.*

/** Versioned, original analytic model. No measured stock data or Spektrafilm implementation. */
data class FilmRecipe(val exposureEv: Float = 0f, val contrast: Float = 1.12f, val monochrome: Boolean = false, val autoDisplayExposure: Boolean = true,
                      val rawDetail: Boolean = true) {
    init { require(exposureEv.isFinite() && exposureEv in -4f..4f); require(contrast in 0.5f..2f) }
    val engineId: String get() = "analytic-response-v1"
}

enum class LinearSpace { REC2020_D65 }
data class LinearImage(val width: Int, val height: Int, val rgb: FloatArray, val space: LinearSpace = LinearSpace.REC2020_D65) {
    init { require(width > 0 && height > 0 && width.toLong() * height * 3 == rgb.size.toLong()) }
}
interface FilmEngine {
    /** Consumes scene-linear Rec.2020 and returns display-encoded sRGB ARGB pixels. */
    fun render(image: LinearImage, recipe: FilmRecipe): IntArray
}

object ColorMath {
    fun decodeSrgb(v: Float): Float = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    fun encodeSrgb(v: Float): Float = if (v <= 0.0031308f) 12.92f * v else 1.055f * v.pow(1f / 2.4f) - 0.055f
    val srgbTo2020 = floatArrayOf(.627404f,.329283f,.043313f, .069097f,.919540f,.011362f, .016391f,.088013f,.895595f)
    val rec2020ToSrgb = floatArrayOf(1.660491f,-.587641f,-.072850f, -.124550f,1.132900f,-.008349f, -.018151f,-.100579f,1.118730f)
    fun multiply(a: FloatArray, b: FloatArray): FloatArray = FloatArray(9) { i ->
        (0..2).sumOf { k -> (a[i / 3 * 3 + k] * b[k * 3 + i % 3]).toDouble() }.toFloat()
    }
}

class AnalyticFilmEngine(private val useQuantizedCurve: Boolean = true) : FilmEngine {
    @Volatile private var cachedCurve: QuantizedFilmCurve?=null
    companion object {
        /** Logistic log-exposure response, anchored at 18% gray; shoulder is bounded. */
        fun response(x: Float, contrast: Float): Float {
            val p = (max(x, 0f) / .18f).pow(contrast)
            return if (p.isInfinite()) 1f else p / (p + 4.5555556f)
        }
    }
    override fun render(image: LinearImage, recipe: FilmRecipe): IntArray {
        val curve=if(useQuantizedCurve) cachedCurve?.takeIf {it.contrast==recipe.contrast}
            ?: QuantizedFilmCurve(recipe.contrast).also {cachedCurve=it} else null
        val out = IntArray(image.width * image.height)
        val m = ColorMath.rec2020ToSrgb
        val gain = 2f.pow(recipe.exposureEv)
        for (i in out.indices) {
            if (i % image.width == 0 && Thread.currentThread().isInterrupted) throw InterruptedException()
            val j = i * 3
            val x = image.rgb[j]; val y = image.rgb[j + 1]; val z = image.rgb[j + 2]
            var r = (m[0]*x + m[1]*y + m[2]*z) * gain
            var g = (m[3]*x + m[4]*y + m[5]*z) * gain
            var b = (m[6]*x + m[7]*y + m[8]*z) * gain
            if (recipe.monochrome) { val l = .2126f*r + .7152f*g + .0722f*b; r=l; g=l; b=l }
            fun channel(v: Float) = curve?.encode(v) ?: (ColorMath.encodeSrgb(response(v, recipe.contrast)).coerceIn(0f,1f)*255f + .5f).toInt()
            out[i] = (255 shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
        }
        return out
    }
}

/** The existing 8-bit monotone response has only 255 transitions. Find each on the
 * positive Float lattice using the original expression, then classify in at most 8 comparisons.
 * No approximate sampled LUT, output range clipping, or loss of scene-linear headroom.
 * This is an output encoder optimization, not a general film engine / spectral LUT.
 */
internal class QuantizedFilmCurve(val contrast: Float) {
    private val thresholds=FloatArray(255) {index ->
        val target=index+1
        var low=0; var high=Float.POSITIVE_INFINITY.toRawBits()
        while(low<high) {
            val middle=low+(high-low)/2
            val value=Float.fromBits(middle)
            val code=(ColorMath.encodeSrgb(AnalyticFilmEngine.response(value,contrast)).coerceIn(0f,1f)*255f+.5f).toInt()
            if(code>=target) high=middle else low=middle+1
        }
        Float.fromBits(low)
    }
    fun encode(value: Float): Int {
        if(value<=0f || value.isNaN()) return 0
        var low=0; var high=255
        while(low<high) {
            val middle=(low+high) ushr 1
            if(value>=thresholds[middle]) low=middle+1 else high=middle
        }
        // Float rounding can make the scalar expression wobble by one code near a transition.
        // Preserve it exactly in a narrow ULP neighborhood; the vast majority avoid pow entirely.
        val bits=value.toRawBits()
        if((low>0 && bits-thresholds[low-1].toRawBits()<512) ||
            (low<255 && thresholds[low].toRawBits()-bits<512)) {
            return (ColorMath.encodeSrgb(AnalyticFilmEngine.response(value,contrast)).coerceIn(0f,1f)*255f+.5f).toInt()
        }
        return low
    }
}
