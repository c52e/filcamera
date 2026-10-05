package io.github.c52e.filcamera

import io.github.c52e.filcamera.camera.*
import org.junit.Assert.*
import org.junit.Test

class FocalOptionsTest {
    private fun lenses(vararg mm: Double)=mm.mapIndexed {i,n->RawLens(LensRoute("$i","$i"),n,null)}
    @Test fun requestedExamples() {
        for(tele in listOf(70.0,120.0)) {
            val items=FocalOptions.create(lenses(12.0,24.0,tele))
            assertEquals(listOf(12.0,24.0,35.0,50.0,85.0,tele).sorted(),items.map {it.equivalentMm})
            assertEquals(24.0,items.first {it.targetMm==35.0}.lens!!.equivalentMm!!,0.0)
            assertEquals(24.0,items.first {it.targetMm==50.0}.lens!!.equivalentMm!!,0.0)
            assertEquals(if(tele==70.0) 70.0 else 24.0,items.first {it.targetMm==85.0}.lens!!.equivalentMm!!,0.0)
        }
    }
    @Test fun strictUnroundedBoundaryAndNativeAbove() {
        for(n in listOf(34.01,35.99)) assertFalse(FocalOptions.create(lenses(n)).any {it.targetMm==35.0})
        for(n in listOf(34.0,33.99,36.0,36.01)) assertTrue(FocalOptions.create(lenses(n)).any {it.targetMm==35.0})
        val items=FocalOptions.create(lenses(34.001,35.999))
        assertEquals(2,items.count {it.targetMm==null})
        assertEquals(listOf("34","36"),items.filter {it.targetMm==null}.map {it.label})
        assertFalse(items.any {it.targetMm==35.0})
    }
    @Test fun identityDedupAndUnavailableSources() {
        val a=RawLens(LensRoute("logical","a","a"),24.0,null)
        val direct=a.copy(route=LensRoute("a","a"))
        val other=a.copy(route=LensRoute("b","b"),equivalentMm=24.1)
        val items=FocalOptions.create(listOf(a,direct,other))
        assertEquals(2,items.count {it.targetMm==null})
        assertEquals(direct,items.first {it.id=="native:a"}.lens)
        assertEquals(other,items.first {it.targetMm==35.0}.lens)
        assertNull(FocalOptions.create(lenses(120.0)).first {it.targetMm==85.0}.lens)
        assertTrue(FocalOptions.create(listOf(a.copy(equivalentMm=null))).filter {it.targetMm!=null}.all {it.lens==null})
    }
}
