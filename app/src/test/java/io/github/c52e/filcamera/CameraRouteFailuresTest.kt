package io.github.c52e.filcamera

import io.github.c52e.filcamera.camera.*
import org.junit.Assert.*
import org.junit.Test

class CameraRouteFailuresTest {
    private val main=RawLens(LensRoute("main","main"),24.0,null)
    private val tele=RawLens(LensRoute("tele","tele"),70.0,null)
    private val alias=tele.copy(route=LensRoute("logical","tele","tele"))
    private val wide=RawLens(LensRoute("wide","wide"),12.0,null)
    private val entries=listOf(main,tele,alias,wide)
    private fun native(lens: RawLens)=FocalOption("native:${lens.route.lensId}",lens,null,lens.equivalentMm)

    @Test fun sameLensAliasTakesPriorityAndPreservesCrop() {
        val failures=CameraRouteFailures()
        val crop=FocalOptions.create(entries).first {it.id=="crop:85"}
        assertEquals(crop.copy(lens=alias),failures.fallback(crop,native(main),entries))
    }
    @Test fun exhaustedAliasesRestoreExactlyTheConfirmedFocalOption() {
        val failures=CameraRouteFailures()
        val previous=FocalOptions.create(entries).first {it.id=="crop:50"}
        val alternative=failures.fallback(native(tele),previous,entries)!!
        assertEquals(previous,failures.fallback(alternative,previous,entries))
    }
    @Test fun noConfirmedLensMeansStopRatherThanChooseAnotherLens() {
        val failures=CameraRouteFailures()
        assertNull(failures.fallback(native(tele),null,listOf(main,tele,wide)))
    }
    @Test fun failedRestorationCannotLoopBackAndManualRetryResetsAttempts() {
        val failures=CameraRouteFailures()
        val previous=native(main)
        val alternative=failures.fallback(native(tele),previous,entries)!!
        val restored=failures.fallback(alternative,previous,entries)!!
        assertEquals(previous,restored)
        assertNull(failures.fallback(restored,previous,entries))
        failures.resetAttempts()
        assertEquals(alias,failures.fallback(native(tele),previous,entries)!!.lens)
    }
    @Test fun unavailableConfirmedRouteDoesNotSelectAnUnrelatedLens() {
        val failures=CameraRouteFailures()
        assertNull(failures.fallback(native(tele),native(main),listOf(tele,wide)))
    }
    @Test fun allSameLensAliasesAreTriedBeforeRestoringPreviousLens() {
        val failures=CameraRouteFailures()
        val second=tele.copy(route=LensRoute("z-logical","tele","tele"))
        val routes=entries+second
        val previous=native(main)
        val firstFallback=failures.fallback(native(tele),previous,routes)!!
        assertEquals(alias,firstFallback.lens)
        val secondFallback=failures.fallback(firstFallback,previous,routes)!!
        assertEquals(second,secondFallback.lens)
        assertEquals(previous,failures.fallback(secondFallback,previous,routes))
    }
}
