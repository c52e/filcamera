package io.github.c52e.filcamera.camera

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.CaptureRequest as Q
import android.hardware.camera2.params.ColorSpaceTransform
import io.github.c52e.filcamera.imaging.CalibratedWhiteBalance as W

/** Camera2 matrices are XYZ->reference sensor, then reference->individual sensor. */
object CameraWhiteBalanceCalibration {
    fun read(c: C): W.Calibration? = runCatching {
        fun matrix(m: ColorSpaceTransform?) = requireNotNull(m).let {W.Matrix(List(9) {i->it.getElement(i%3,i/3).toDouble()})}
        fun anchor(light: Int?,cm: ColorSpaceTransform?,cc: ColorSpaceTransform?,fm: ColorSpaceTransform?) =
            W.Anchor(temperature(requireNotNull(light)),matrix(cm),matrix(cc),fm?.let(::matrix))
        W.Calibration(anchor(c[C.SENSOR_REFERENCE_ILLUMINANT1],c[C.SENSOR_COLOR_TRANSFORM1],c[C.SENSOR_CALIBRATION_TRANSFORM1],c[C.SENSOR_FORWARD_MATRIX1]),
            anchor(c[C.SENSOR_REFERENCE_ILLUMINANT2]?.toInt(),c[C.SENSOR_COLOR_TRANSFORM2],c[C.SENSOR_CALIBRATION_TRANSFORM2],c[C.SENSOR_FORWARD_MATRIX2]))
    }.getOrNull()

    fun controllable(sensor: C,owner: C): Boolean {
        val required=listOf(Q.CONTROL_AWB_MODE,Q.COLOR_CORRECTION_MODE,Q.COLOR_CORRECTION_GAINS,Q.COLOR_CORRECTION_TRANSFORM)
        return sensor[C.REQUEST_AVAILABLE_CAPABILITIES]?.contains(C.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)==true &&
            sensor[C.CONTROL_AWB_AVAILABLE_MODES]?.contains(Q.CONTROL_AWB_MODE_OFF)==true &&
            owner.availableCaptureRequestKeys.orEmpty().containsAll(required)
    }

    // Match the pinned DNG SDK's named-illuminant interpolation temperatures (A is 2850).
    // Generic fluorescent/unknown whites are deliberately not guessed in this first version.
    fun temperature(illuminant: Int): Double = when(illuminant) {
        3,17->2850.0;24->3200.0;23->5000.0;1,4,9,18,20->5500.0
        10,19,21->6500.0;11,22->7500.0
        else->error(tr(Texts.string.unknown_calibration_illuminant_value, illuminant))
    }
}
