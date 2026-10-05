package io.github.c52e.filcamera.imaging

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class QuantizedFilmCurveTest {
    @Test fun scalarAndThresholdEncoderAgreeAcrossFloatRange() {
        val random=Random(1937)
        for(contrast in listOf(.5f,.75f,1f,1.12f,1.5f,2f)) {
            val curve=QuantizedFilmCurve(contrast)
            fun check(v: Float) {
                val reference=(ColorMath.encodeSrgb(AnalyticFilmEngine.response(v,contrast)).coerceIn(0f,1f)*255f+.5f).toInt()
                assertEquals("contrast=$contrast input=$v",reference,curve.encode(v))
            }
            for(v in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,-1f,0f,Float.MIN_VALUE,Float.MAX_VALUE)) check(v)
            repeat(150_000) {check(Float.fromBits(random.nextInt(0,0x7f800000)))}
            for(i in 0..100_000) check(i/10000f)
        }
    }
    @Test fun rendererMatchesReferenceForExposureMonoAndContrastChanges() {
        val random=Random(5)
        val image=LinearImage(97,83,FloatArray(97*83*3){random.nextFloat()*8f-.2f})
        val fast=AnalyticFilmEngine(); val reference=AnalyticFilmEngine(false)
        for(c in listOf(.5f,1.12f,2f)) for(ev in listOf(-4f,0f,4f)) for(mono in listOf(false,true)) {
            val recipe=FilmRecipe(exposureEv=ev,contrast=c,monochrome=mono)
            assertArrayEquals(reference.render(image,recipe),fast.render(image,recipe))
        }
    }
    @Test fun floatNeighborhoodsAtEveryOutputTransitionMatchScalar() {
        for(contrast in listOf(.5f,1.12f,2f)) {
            val curve=QuantizedFilmCurve(contrast)
            fun scalar(v: Float)=(ColorMath.encodeSrgb(AnalyticFilmEngine.response(v,contrast)).coerceIn(0f,1f)*255f+.5f).toInt()
            for(code in 1..255) {
                var lo=0; var hi=Float.POSITIVE_INFINITY.toRawBits()
                while(lo<hi) {val mid=lo+(hi-lo)/2; if(scalar(Float.fromBits(mid))>=code) hi=mid else lo=mid+1}
                for(bits in lo-600..lo+600) {
                    val v=Float.fromBits(bits)
                    assertEquals("contrast=$contrast code=$code v=$v",scalar(v),curve.encode(v))
                }
            }
        }
    }
}
