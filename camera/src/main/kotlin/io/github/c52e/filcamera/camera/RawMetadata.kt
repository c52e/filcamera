package io.github.c52e.filcamera.camera

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.CaptureResult as R
import io.github.c52e.filcamera.imaging.BayerPattern
import io.github.c52e.filcamera.imaging.RawFrame
import io.github.c52e.filcamera.imaging.ShadingGrid

object RawMetadata {
    /** Fail closed on unknown color metadata; DNG remains useful even if this developer cannot run. */
    fun frame(capture: CapturedFrame): RawFrame {
        val image=capture.image; val c=capture.characteristics; val r=capture.result
        val pattern=when(c[C.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT]) {
            C.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> BayerPattern.RGGB
            C.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> BayerPattern.GRBG
            C.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> BayerPattern.GBRG
            C.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> BayerPattern.BGGR
            else -> error(tr(Texts.string.only_standard_bayer_is_supported_dng_retained))
        }
        val pixels=requireNotNull(c[C.SENSOR_INFO_PIXEL_ARRAY_SIZE])
        val active=requireNotNull(c[C.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE])
        val fullArray=image.width==pixels.width && image.height==pixels.height
        val activeArray=image.width==active.width() && image.height==active.height()
        require(fullArray || activeArray) { tr(Texts.string.unknown_raw_array_coordinates_dng_retained) }
        // Odd cropped CFA origins need explicit remapping of both gains and black pattern.
        require(fullArray || (active.left % 2 == 0 && active.top % 2 == 0)) { tr(Texts.string.odd_cfa_crop_origins_are_not_supported) }
        val black=r[R.SENSOR_DYNAMIC_BLACK_LEVEL] ?: requireNotNull(c[C.SENSOR_BLACK_LEVEL_PATTERN]).let { p -> FloatArray(4) { p.getOffsetForIndex(it%2,it/2).toFloat() } }
        val white=(r[R.SENSOR_DYNAMIC_WHITE_LEVEL] ?: requireNotNull(c[C.SENSOR_INFO_WHITE_LEVEL])).toFloat()
        val wb=requireNotNull(r[R.COLOR_CORRECTION_GAINS]) { tr(Texts.string.missing_white_balance_gains) }
        val transform=requireNotNull(r[R.COLOR_CORRECTION_TRANSFORM]) { tr(Texts.string.missing_camera_color_matrix) }
        val matrix=FloatArray(9) { transform.getElement(it%3,it/3).toFloat() }
        val plane=image.planes[0]
        // Camera2 supplies residual correction even when the sensor applies some shading itself.
        val shading=r[R.STATISTICS_LENS_SHADING_CORRECTION_MAP]?.let { map ->
            ShadingGrid(map.columnCount,map.rowCount,FloatArray(map.gainFactorCount).also { map.copyGainFactors(it,0) })
        }
        return RawFrame(image.width,image.height,plane.rowStride,plane.pixelStride,plane.buffer.duplicate(),pattern,black,white,
            floatArrayOf(wb.red,wb.greenEven,wb.greenOdd,wb.blue),matrix,
            if(fullArray) active.left else 0,if(fullArray) active.top else 0,
            if(fullArray) active.right else image.width,if(fullArray) active.bottom else image.height,shading)
    }
}
