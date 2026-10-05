package io.github.c52e.filcamera.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.TonemapCurve
import kotlin.math.pow

/** Known display transfer for the preview shader's sRGB decoding. No creative curve. */
object PreviewToneCurve {
    fun error(requested: TonemapCurve, actual: TonemapCurve?): Float {
        if(actual==null) return Float.POSITIVE_INFINITY
        var error=0f
        for(channel in 0..2) {
            val a=FloatArray(actual.getPointCount(channel)*2);actual.copyColorCurve(channel,a,0)
            if(a.size<4 || a.any {!it.isFinite()} || a[0]!=0f || a[a.size-2]!=1f) return Float.POSITIVE_INFINITY
            if((2 until a.size step 2).any {a[it]<=a[it-2] || a[it+1]<a[it-1]}) return Float.POSITIVE_INFINITY
            val r=FloatArray(requested.getPointCount(channel)*2);requested.copyColorCurve(channel,r,0)
            var j=0
            for(i in r.indices step 2) {
                val x=r[i]
                while(j+4<a.size && a[j+2]<x) j+=2
                val y=a[j+1]+(a[j+3]-a[j+1])*(x-a[j])/(a[j+2]-a[j])
                error=maxOf(error,kotlin.math.abs(y-r[i+1]))
            }
        }
        return error
    }
    fun create(characteristics: CameraCharacteristics): TonemapCurve? {
        if(characteristics[CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES]
                ?.contains(CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)!=true) return null
        val keys=characteristics.availableCaptureRequestKeys
        if(CaptureRequest.TONEMAP_MODE !in keys || CaptureRequest.TONEMAP_CURVE !in keys) return null
        val n=(characteristics[CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS] ?: return null).coerceAtMost(128)
        if(n<16) return null
        // Uniform input positions: some HALs resample/ignore nonuniform abscissae.
        val points=FloatArray(n*2)
        for(i in 0 until n) {
            val linear=i.toDouble()/(n-1)
            points[2*i]=linear.toFloat()
            points[2*i+1]=(if(linear<=.0031308) linear*12.92 else 1.055*linear.pow(1/2.4)-.055).toFloat()
        }
        return TonemapCurve(points,points,points)
    }
}
