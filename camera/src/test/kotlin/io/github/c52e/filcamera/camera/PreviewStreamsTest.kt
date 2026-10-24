package io.github.c52e.filcamera.camera

import org.junit.Assert.*
import org.junit.Test

class PreviewStreamsTest {
    private val streams=listOf(PreviewStream(640,480),PreviewStream(1280,960),PreviewStream(1920,1080),
        PreviewStream(1920,1440),PreviewStream(2560,1920),PreviewStream(4000,3000))
    @Test fun cropSelectsLargerSourceWithinInputBudget() {
        assertEquals(PreviewStream(1280,960),PreviewStreams.select(streams,4000,3000,1.0))
        assertEquals(PreviewStream(1920,1440),PreviewStreams.select(streams,4000,3000,24.0/35))
        assertEquals(PreviewStream(2560,1920),PreviewStreams.select(streams,4000,3000,24.0/50))
        assertEquals(PreviewStream(2560,1920),PreviewStreams.select(streams,4000,3000,24.0/85))
    }
    @Test fun matchingAspectOutranksPixelCount() {
        assertEquals(PreviewStream(1280,960),PreviewStreams.select(streams,4000,3000,.8))
    }
    @Test fun preferThirtyFpsOverSlowOrUnknownModes() {
        val fast=PreviewStream(1920,1440,33_333_334)
        val slow=PreviewStream(2560,1920,50_000_000)
        assertEquals(fast,PreviewStreams.select(listOf(fast,slow,PreviewStream(1280,960)),4000,3000,.48))
        assertEquals(PreviewStream(1280,960),PreviewStreams.select(listOf(slow,PreviewStream(1280,960)),4000,3000,.48))
        assertEquals(slow,PreviewStreams.select(listOf(slow),4000,3000,.48))
    }
    @Test fun softLimitsNeverRejectTheOnlyAvailableStream() {
        assertEquals(PreviewStream(1920,1440),PreviewStreams.select(listOf(PreviewStream(1920,1440)),4000,3000,1.0))
        assertEquals(PreviewStream(3000,2250),PreviewStreams.select(listOf(PreviewStream(4000,3000),PreviewStream(3000,2250)),4000,3000,.2))
    }
    @Test fun noCropWithoutUsableOpticsOrLongerTarget() {
        assertEquals(1.0,PreviewStreams.scale(null,85.0,4000,3000),0.0)
        assertEquals(1.0,PreviewStreams.scale(24.0,null,4000,3000),0.0)
        assertEquals(1.0,PreviewStreams.scale(50.0,35.0,4000,3000),0.0)
        assertEquals(.48,PreviewStreams.scale(24.0,50.0,4000,3000),1e-7)
    }
    @Test fun selectionUsesActualBayerAlignedCropRatherThanFocalRatio() {
        // A low GCD forces coarser steps; this must match CaptureFraming exactly.
        val scale=PreviewStreams.scale(25.39361679811951,35.0,9248,6944)
        val (w,h)=FocalCrop.dimensions(9248,6944,25.39361679811951,35.0)
        assertEquals(w.toFloat()/9248,scale.toFloat(),0f)
        assertEquals(w.toDouble()/h,9248.0/6944,1e-9)
    }
}
