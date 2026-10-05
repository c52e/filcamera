package io.github.c52e.filcamera

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class PreviewExposureTest {
    @Test fun responseIsIndependentOfFrameRateAndDoesNotOvershoot() {
        fun response(hz: Int): Float {
            val smoother=PreviewExposure();smoother.update(-1f,true,1_000_000_000L)
            for(i in 1..hz) {
                val ev=smoother.update(-1f,true,1_000_000_000L+i*1_000_000_000L/hz)
                assertTrue(ev in -1f..0f)
            }
            return smoother.value
        }
        assertEquals(response(30),response(60),1e-6f)
        assertTrue(abs(response(30)+1f)<.06f)
    }
    @Test fun disableAndResumeAreBounded() {
        val smoother=PreviewExposure();smoother.update(-20f,true,1_000_000_000L)
        assertTrue(smoother.update(-20f,true,20_000_000_000L) in -2.1f..0f)
        assertEquals(0f,smoother.update(-20f,false,21_000_000_000L),0f)
        val enabled=smoother.update(20f,true,21_033_333_333L)
        assertTrue(enabled in 0f..0.2f)
        assertEquals(enabled,smoother.update(20f,true,20_000_000_000L),0f)
    }
}
