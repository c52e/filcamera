package io.github.c52e.filcamera.camera

import android.hardware.camera2.CameraCharacteristics as C
import kotlin.math.hypot

/** Physical sensor geometry in the normal pixel mode; never substitutes a device-specific focal length. */
data class CameraOptics(val focalMm: Double?,val sensorWidthMm: Double,val sensorHeightMm: Double,
    val pixelWidth: Int,val pixelHeight: Int,val left: Int,val top: Int,val width: Int,val height: Int) {
    val equivalentMm: Double? get() {
        val focal=focalMm ?: return null
        if(!focal.isFinite() || focal<=0 || !sensorWidthMm.isFinite() || !sensorHeightMm.isFinite() || sensorWidthMm<=0 || sensorHeightMm<=0 || pixelWidth<=0 || pixelHeight<=0 || width<=0 || height<=0 || left<0 || top<0 || left.toLong()+width>pixelWidth || top.toLong()+height>pixelHeight) return null
        return focal*hypot(36.0,24.0)/hypot(sensorWidthMm*width/pixelWidth,sensorHeightMm*height/pixelHeight)
    }
    companion object {
        fun read(c: C): CameraOptics? {
            val size=c[C.SENSOR_INFO_PHYSICAL_SIZE] ?: return null
            val pixels=c[C.SENSOR_INFO_PIXEL_ARRAY_SIZE] ?: return null
            val active=c[C.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE] ?: return null
            val focal=c[C.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.singleOrNull()?.toDouble()
            return CameraOptics(focal,size.width.toDouble(),size.height.toDouble(),pixels.width,pixels.height,active.left,active.top,active.width(),active.height())
        }
    }
}
