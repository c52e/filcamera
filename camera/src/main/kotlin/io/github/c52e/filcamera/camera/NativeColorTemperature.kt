package io.github.c52e.filcamera.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.Build
import android.util.Range

/** API 36 public metadata, discovered via public inventories to retain compileSdk 35.
 * No reflection, vendor tags or inferred Kelvin conversion. Names/types and CCT=3 follow:
 * https://developer.android.com/reference/android/hardware/camera2/CameraMetadata#COLOR_CORRECTION_MODE_CCT
 */
object NativeColorTemperature {
    const val MODE_CCT = 3
    private const val TEMPERATURE = "android.colorCorrection.colorTemperature"
    @Suppress("UNCHECKED_CAST")
    fun capability(sensor: CameraCharacteristics, logical: CameraCharacteristics? = null): Capability? {
        if (Build.VERSION.SDK_INT < 36) return null
        return runCatching {
            val modesKey = sensor.keys.firstOrNull { it.name == "android.colorCorrection.availableModes" }
                as? CameraCharacteristics.Key<IntArray> ?: return null
            if (sensor[modesKey]?.contains(MODE_CCT) != true) return null
            val rangeKey = sensor.keys.firstOrNull { it.name == "android.colorCorrection.colorTemperatureRange" }
                as? CameraCharacteristics.Key<Range<Int>> ?: return null
            val range = sensor[rangeKey] ?: return null
            if (sensor[CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES]?.contains(CaptureRequest.CONTROL_AWB_MODE_OFF) != true) return null
            val owner = logical ?: sensor
            val keys = owner.availableCaptureRequestKeys.orEmpty()
            val request = keys.firstOrNull { it.name == TEMPERATURE } as? CaptureRequest.Key<Int> ?: return null
            if (!keys.containsAll(listOf(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.COLOR_CORRECTION_MODE))) return null
            if (logical != null) {
                // A routed physical control must be explicitly targetable at that sensor.
                val physical = logical.availablePhysicalCameraRequestKeys.orEmpty()
                if (!physical.containsAll(listOf(request, CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.COLOR_CORRECTION_MODE))) return null
            }
            if (sensor.availableCaptureResultKeys?.any { it.name == TEMPERATURE } != true) return null
            Capability(TemperatureRange(range.lower, range.upper), request)
        }.getOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    fun actual(result: CaptureResult): Int? {
        if (Build.VERSION.SDK_INT < 36) return null
        val key = result.keys.firstOrNull { it.name == TEMPERATURE } as? CaptureResult.Key<Int> ?: return null
        return result[key]
    }

    data class Capability(val range: TemperatureRange, val request: CaptureRequest.Key<Int>)
}
