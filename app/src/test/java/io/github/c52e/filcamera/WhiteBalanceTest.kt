package io.github.c52e.filcamera
import io.github.c52e.filcamera.camera.*
import org.junit.Assert.*
import org.junit.Test
class WhiteBalanceTest {
    @Test fun nativeTemperatureDoesNotImplyUniversalSupport() {
        assertFalse(WhiteBalance().manual)
        assertFalse(WhiteBalanceStatus().supported)
        val range=TemperatureRange(2856,6500)
        assertTrue(WhiteBalanceStatus(range=range).supported)
        assertTrue(range.contains(2856));assertTrue(range.contains(6500))
        assertFalse(range.contains(2855));assertFalse(range.contains(6501))
        assertEquals(5500,range.default)
        assertEquals(5500,WhiteBalance(5500).kelvin)
    }
    @Test fun defaultRespectsDeviceRange() {assertEquals(4000,TemperatureRange(2000,4000).default);assertEquals(6000,TemperatureRange(6000,10000).default)}
    @Test(expected=IllegalArgumentException::class) fun invalidRange() {TemperatureRange(6500,2856)}
    @Test(expected=IllegalArgumentException::class) fun invalidKelvin() {WhiteBalance(0)}
}
