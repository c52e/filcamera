package io.github.c52e.filcamera.imaging

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.log2

class DisplayExposureTest {
    @Test fun darkDiffuseWhiteIsMappedToDisplayTarget() {
        val image=LinearImage(10,10,FloatArray(300){.225f})
        val ev=DisplayExposure.estimateEv(image)
        assertEquals(2f,ev,.025f)
        DisplayExposure.applyInPlace(image,ev)
        assertEquals(.9f,image.rgb[0],.02f)
    }
    @Test fun brightBlackAndNonfiniteFramesAreNotLifted() {
        for(v in listOf(0f,1e-6f,1f,2f,Float.NaN))
            assertEquals(0f,DisplayExposure.estimateEv(LinearImage(1,1,FloatArray(3){v})),0f)
    }
    @Test fun smallSpecularDoesNotControlTheMeter() {
        val rgb=FloatArray(3000){.1f}; rgb[0]=8f; rgb[1]=8f; rgb[2]=8f
        assertEquals(log2(9f),DisplayExposure.estimateEv(LinearImage(1000,1,rgb)),.025f)
    }
    @Test fun liftIsBoundedAndOnlyPositive() {
        val dark=LinearImage(2,2,FloatArray(12){.002f})
        assertEquals(4f,DisplayExposure.estimateEv(dark),0f)
        assertThrows(IllegalArgumentException::class.java) {DisplayExposure.applyInPlace(dark,-1f)}
    }
}
