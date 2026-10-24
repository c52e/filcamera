package io.github.c52e.filcamera.camera

import org.junit.Assert.*
import org.junit.Test

class PreviewFallbacksTest {
    private val route=LensRoute("0","0")
    private val streams=listOf(PreviewStream(640,480),PreviewStream(1280,960),PreviewStream(1600,1200),PreviewStream(1920,1440))
    @Test fun failureKeepsSameLensAndOnlySmallerBuffers() {
        val policy=PreviewFallbacks()
        policy.reject(route,4000,3000,PreviewStream(1920,1440))
        assertEquals(streams.dropLast(1),policy.available(route,4000,3000,streams))
        // Frame timing metadata must not affect remembering a rejected size.
        assertTrue(policy.available(route,4000,3000,listOf(PreviewStream(1920,1440,33_333_333))).isEmpty())
        policy.reject(route,4000,3000,PreviewStream(1600,1200))
        assertEquals(streams.take(2),policy.available(route,4000,3000,streams))
    }
    @Test fun rejectionIsScopedToExactRouteAndRawSize() {
        val policy=PreviewFallbacks()
        policy.reject(route,4000,3000,streams.last())
        assertEquals(streams,policy.available(LensRoute("logical","0","0"),4000,3000,streams))
        assertEquals(streams,policy.available(route,8000,6000,streams))
    }
    @Test fun exhaustionTerminatesInsteadOfRetryingRejectedSizes() {
        val policy=PreviewFallbacks()
        val attempts=mutableListOf<PreviewStream>()
        while(true) {
            val remaining=policy.available(route,4000,3000,streams)
            if(remaining.isEmpty()) break
            val next=PreviewStreams.select(remaining,4000,3000,.5)
            assertFalse(attempts.contains(next));attempts+=next
            policy.reject(route,4000,3000,next)
            assertTrue(attempts.size<=streams.size)
        }
        assertEquals(streams.reversed(),attempts)
        assertTrue(policy.available(route,4000,3000,streams).isEmpty())
    }
    @Test fun rememberedFallbackIsReusedWhenTargetChanges() {
        val policy=PreviewFallbacks()
        policy.reject(route,4000,3000,streams.last())
        policy.reject(route,4000,3000,streams[2])
        for(scale in listOf(1.0,.7,.5,.25)) {
            assertEquals(streams[1],PreviewStreams.select(policy.available(route,4000,3000,streams),4000,3000,scale))
        }
    }
}
