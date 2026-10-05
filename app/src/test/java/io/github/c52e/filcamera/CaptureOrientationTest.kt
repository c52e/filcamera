package io.github.c52e.filcamera

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureOrientationTest {
    @Test fun lockedDisplayDoesNotDeterminePhotoOrientation() {
        val state=CaptureOrientation()
        for((sensor,expected) in listOf(0 to 0,90 to 270,180 to 180,270 to 90)) {
            state.update(sensor)
            for(display in listOf(0,90,180,270)) assertEquals(expected,state.displayDegreesForCapture(display))
            // Match Camera2's sensor+device rule for either sensor mount.
            for(mount in listOf(90,270)) {
                assertEquals((mount+sensor)%360,(mount-state.displayDegreesForCapture(0)+360)%360)
                assertEquals((mount-sensor+360)%360,(mount+state.displayDegreesForCapture(0))%360)
            }
        }
    }
    @Test fun diagonalJitterAndFlatDevicePreserveLastDirection() {
        val state=CaptureOrientation()
        assertEquals(90,state.displayDegreesForCapture(90))
        state.update(-1);assertEquals(270,state.displayDegreesForCapture(270))
        state.update(0)
        for(angle in listOf(44,46,49,359)) {state.update(angle);assertEquals(0,state.displayDegreesForCapture(90))}
        state.update(50);assertEquals(270,state.displayDegreesForCapture(0))
        for(angle in listOf(49,45,41,-1)) {state.update(angle);assertEquals(270,state.displayDegreesForCapture(0))}
        state.update(40);assertEquals(0,state.displayDegreesForCapture(90))
        state.update(310);assertEquals(90,state.displayDegreesForCapture(0))
        state.update(-1);assertEquals(90,state.displayDegreesForCapture(0))
    }
}
