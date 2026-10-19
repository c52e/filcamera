package io.github.c52e.filcamera

import org.junit.Assert.*
import org.junit.Test

class CaptureFramingLimitTest {
    @Test fun nativeSixtyMegapixelFramingKeepsAllPixels() {
        val framing=CaptureFraming.create(10000,6000,null,null)
        assertEquals(10000,framing.cropWidth)
        assertEquals(6000,framing.cropHeight)
        assertEquals(0,framing.left)
        assertEquals(0,framing.top)
    }
    @Test(expected=IllegalArgumentException::class)
    fun overSixtyMegapixelsIsRejected() {
        CaptureFraming.create(10000,6001,null,null)
    }
}
