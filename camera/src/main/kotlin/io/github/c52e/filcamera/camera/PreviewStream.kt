package io.github.c52e.filcamera.camera

import kotlin.math.abs

/** Buffer dimensions are in sensor coordinates, before display rotation. Zero duration is unknown. */
data class PreviewStream(val width: Int,val height: Int,val minFrameDurationNs: Long=0) {
    val pixels get()=width.toLong()*height
}

object PreviewStreams {
    fun scale(nativeMm: Double?,targetMm: Double?,width: Int,height: Int): Double =
        if(nativeMm!=null && targetMm!=null && nativeMm>0 && targetMm>nativeMm)
            FocalCrop.dimensions(width,height,nativeMm,targetMm).first.toFloat().div(width).toDouble() else 1.0

    /** Aspect first; bound input bandwidth independently of the visible, cropped image. */
    fun select(streams: List<PreviewStream>,stillWidth: Int,stillHeight: Int,scale: Double): PreviewStream {
        require(streams.isNotEmpty() && stillWidth>0 && stillHeight>0 && scale.isFinite() && scale>0 && scale<=1)
        val bounded=streams.filter {it.width<=2560 && it.height<=1920}
        // If even the smallest stream exceeds the budget, do not select a larger one for a deep crop.
        val inputs=bounded.ifEmpty {listOf(streams.minBy {it.pixels})}
        fun aspect(s: PreviewStream)=abs(s.width.toDouble()/s.height-stillWidth.toDouble()/stillHeight)
        val bestAspect=inputs.minOf(::aspect)
        val matching=inputs.filter {abs(aspect(it)-bestAspect)<1e-6}
        val visible=matching.filter {it.width*scale<=1920 && it.height*scale<=1080}.ifEmpty {matching}
        // Prefer advertised 30 fps support, then unknown timing, then slower modes as a fallback.
        val fast=visible.filter {it.minFrameDurationNs in 1..33_333_334L}
        val timed=fast.ifEmpty {visible.filter {it.minFrameDurationNs==0L}}.ifEmpty {visible}
        return timed.minWith(compareBy<PreviewStream> {abs(it.pixels*scale*scale-1280.0*960)}.thenBy {it.pixels})
    }
}
