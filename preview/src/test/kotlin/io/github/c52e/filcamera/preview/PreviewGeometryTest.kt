package io.github.c52e.filcamera.preview

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewGeometryTest {
    @Test fun tapsFollowRenderedQuadAtAllRotationsAndCrops() {
        val producer=FloatArray(16).apply {this[0]=1f;this[5]=-1f;this[13]=1f;this[10]=1f;this[15]=1f}
        // Upper-left display quadrant, four inverse display rotations, top-left buffer coordinates.
        val expected=listOf(.25f to .25f,.75f to .25f,.75f to .75f,.25f to .75f)
        for(degrees in listOf(0,90,180,270)) for(scale in listOf(1f,.5f,.277f)) {
            val matrix=FloatArray(16);PreviewGeometry.centerCrop(producer,scale,matrix)
            val w=if(degrees%180==0) 800 else 600;val h=if(degrees%180==0) 600 else 800
            val point=PreviewGeometry.tap(w*.25f,h*.25f,w,h,matrix,1280,960,degrees)!!
            assertEquals(.5f+(expected[degrees/90].first-.5f)*scale,point.x,1e-6f)
            assertEquals(.5f+(expected[degrees/90].second-.5f)*scale,point.y,1e-6f)
        }
        org.junit.Assert.assertNull(PreviewGeometry.tap(500f,10f,1000,1000,producer,1280,960,0))
        val center=PreviewGeometry.tap(500f,500f,1000,1000,producer,1280,960,0)!!
        assertEquals(.5f,center.x,0f);assertEquals(.5f,center.y,0f)
        producer[0]=-1f;producer[12]=1f
        assertEquals(.75f,PreviewGeometry.tap(200f,150f,800,600,producer,1280,960,0)!!.x,0f)
        val quarter=FloatArray(16).apply {this[4]=1f;this[1]=1f;this[10]=1f;this[15]=1f}
        val turned=PreviewGeometry.tap(150f,200f,600,800,quarter,1280,960,0)!!
        assertEquals(.75f,turned.x,0f);assertEquals(.25f,turned.y,0f)
    }
    @Test fun consumerCompensatesDisplayOnly() {
        val expected=listOf(1f to 0f,0f to -1f,-1f to 0f,0f to 1f)
        for(i in 0..3) {
            val r=PreviewGeometry.rotation(i*90)
            assertEquals(expected[i].first,r.cosine,0f)
            assertEquals(expected[i].second,r.sine,0f)
        }
    }
    @Test fun producerQuarterTurnsAndMirrorsRetainCorrectAspectForAllDisplays() {
        for(quarterTurn in listOf(false,true)) for(mirror in listOf(-1f,1f)) for(display in 0..3) {
            val matrix=FloatArray(16)
            if(quarterTurn) {matrix[4]=mirror; matrix[1]=1f} else {matrix[0]=mirror; matrix[5]=-1f}
            val swapped=quarterTurn.xor(display%2==1)
            assertEquals(if(swapped) .75f else 4f/3f,PreviewGeometry.aspect(matrix,1280,960,PreviewGeometry.rotation(display*90)),.00001f)
        }
    }
    @Test fun producerCropIsIncludedInAspect() {
        val matrix=FloatArray(16).apply {this[0]=.75f; this[5]=-1f}
        assertEquals(1f,PreviewGeometry.aspect(matrix,1280,960,PreviewGeometry.rotation(0)),.00001f)
    }
    @Test fun cropKeepsCenterAndAspectThroughProducerTransforms() {
        for(quarter in listOf(false,true)) for(mirror in listOf(-1f,1f)) for(display in 0..3) {
            val producer=FloatArray(16).apply {this[10]=1f;this[15]=1f;this[12]=.125f;this[13]=.25f}
            if(quarter) {producer[4]=.75f*mirror;producer[1]=.5f} else {producer[0]=.75f*mirror;producer[5]=-.5f}
            for(scale in listOf(1f,.67f,.47f,.28f)) {
                val cropped=FloatArray(16);PreviewGeometry.centerCrop(producer,scale,cropped)
                for(row in 0..1) {
                    fun coord(matrix: FloatArray,x: Float,y: Float)=matrix[row]*x+matrix[4+row]*y+matrix[12+row]
                    val center=coord(producer,.5f,.5f)
                    assertEquals(center,coord(cropped,.5f,.5f),1e-6f)
                    for(x in listOf(0f,1f)) for(y in listOf(0f,1f)) assertEquals(center+(coord(producer,x,y)-center)*scale,coord(cropped,x,y),1e-6f)
                }
                val r=PreviewGeometry.rotation(display*90)
                assertEquals(PreviewGeometry.aspect(producer,1280,960,r),PreviewGeometry.aspect(cropped,1280,960,r),1e-6f)
            }
        }
    }
}
