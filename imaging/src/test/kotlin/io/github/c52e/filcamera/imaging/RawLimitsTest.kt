package io.github.c52e.filcamera.imaging

import org.junit.Assert.*
import org.junit.Test

class RawLimitsTest {
    @Test fun acceptsNativeFiftyAndSixtyMegapixelFrames() {
        assertTrue(RawLimits.supports(8192,6144))
        assertTrue(RawLimits.supports(10000,6000))
        assertTrue(RawLimits.supports(6000,10000))
        assertEquals(720_000_000L,RawLimits.MAX_PIXELS.toLong()*12)
    }
    @Test fun rejectsOverLimitAndInvalidDimensions() {
        assertFalse(RawLimits.supports(10000,6001))
        assertFalse(RawLimits.supports(0,6000))
        assertFalse(RawLimits.supports(-10000,6000))
        assertFalse(RawLimits.supports(16385,2))
        assertFalse(RawLimits.supports(Int.MAX_VALUE,Int.MAX_VALUE))
    }
}
