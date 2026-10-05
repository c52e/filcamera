package io.github.c52e.filcamera

/** Sensor orientation is independent of the display rotation lock. Main-thread only. */
internal class CaptureOrientation {
    private var quadrant: Int?=null

    fun update(sensorDegrees: Int) {
        // A flat device has no reliable up direction. Keep the last known one.
        if(sensorDegrees !in 0..359) return
        val previous=quadrant
        if(previous!=null) {
            val distance=kotlin.math.abs(sensorDegrees-previous)
            if(minOf(distance,360-distance)<50) return // 5-degree boundary hysteresis
        }
        quadrant=((sensorDegrees+45)/90*90)%360
    }

    fun displayDegreesForCapture(displayFallback: Int): Int {
        // CameraController uses sensorRotation - displayDegrees for rear cameras.
        // OrientationEventListener has the opposite sign to Surface rotation:
        // the equivalent JPEG formula is sensorRotation + deviceOrientation.
        return quadrant?.let {(360-it)%360} ?: displayFallback
    }
}
