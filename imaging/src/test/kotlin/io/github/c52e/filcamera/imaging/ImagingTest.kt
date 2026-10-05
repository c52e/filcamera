package io.github.c52e.filcamera.imaging

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ImagingTest {
    private val identity=floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f)
    @Test fun allCfaLayoutsRespectBlackStrideAndSeparateGreenGains() {
        for(pattern in BayerPattern.entries) {
            val data=ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
            val black=floatArrayOf(10f,20f,30f,40f)
            val gains=floatArrayOf(2f,1.2f,1.5f,3f)
            val values=floatArrayOf(.1f,.2f,.16f,.1f)
            for(y in 0..3) for(x in 0..3) {
                val site=(y%2)*2+x%2; val channel=pattern.channels[site]
                data.putShort(y*16+x*2,(black[site]+values[channel]*(1040-black[site])).toInt().toShort())
            }
            val out=BinnedRawDeveloper().develop(RawFrame(4,4,16,2,data,pattern,black,1040f,gains,identity))
            val expected=ColorMath.srgbTo2020
            for(i in 0 until 4) for(c in 0..2) assertEquals((expected[c*3]*.2f+expected[c*3+1]*.24f+expected[c*3+2]*.3f).toDouble(),out.rgb[i*3+c].toDouble(),.004)
        }
    }
    @Test fun oddCropUsesSensorCfaCoordinates() {
        val data=ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        for(y in 0..3) for(x in 0..3) data.putShort(y*8+x*2,if(y%2==0&&x%2==0) 800 else 0)
        val out=BinnedRawDeveloper().develop(RawFrame(4,4,8,2,data,BayerPattern.RGGB,FloatArray(4),1000f,FloatArray(4){1f},identity,1,1,3,3))
        assertEquals(.8f*.627404f,out.rgb[0],.0001f)
    }
    @Test fun truncatedPlaneRejected() {
        val f=RawFrame(4,4,8,2,ByteBuffer.allocate(30),BayerPattern.RGGB,FloatArray(4),1000f,FloatArray(4){1f},identity)
        assertThrows(IllegalArgumentException::class.java) { BinnedRawDeveloper().develop(f) }
    }
    @Test fun binningBoundsOutputAndPreservesHighlightHeadroom() {
        val b=ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        for(i in 0 until 64) b.putShort(i*2,900)
        val out=BinnedRawDeveloper(4).develop(RawFrame(8,8,16,2,b,BayerPattern.RGGB,FloatArray(4),1000f,FloatArray(4){2f},identity))
        assertEquals(4,out.width*out.height)
        assertTrue(out.rgb.all { it>1.79f && it<1.81f })
    }
    @Test fun curveIsMonotoneAnchoredAndBounded() {
        assertEquals(.18f,AnalyticFilmEngine.response(.18f,1.12f),.00001f)
        var previous=0f
        for(i in 0..10000) { val v=AnalyticFilmEngine.response(i/100f,1.12f); assertTrue(v in previous..1f); previous=v }
        assertEquals(0f,AnalyticFilmEngine.response(-1f,1.12f),0f)
    }
    @Test fun transferFunctionRoundTrip() {
        for(i in 0..1000) { val x=i/1000f; assertEquals(x,ColorMath.encodeSrgb(ColorMath.decodeSrgb(x)),.000001f) }
    }
    @Test fun timestampPairingHandlesBothOrdersAndNeverPairsDifferentFrames() {
        val closed=mutableListOf<String>(); val pairs=mutableListOf<String>()
        val p=TimestampPairer<String,String>(2,{closed.add(it)}) {i,r -> pairs.add("$i/$r")}
        p.image(11,"a"); p.result(12,"y"); assertTrue(pairs.isEmpty())
        p.result(11,"x"); p.image(12,"b")
        assertEquals(listOf("a/x","b/y"),pairs)
        p.image(13,"c"); p.image(14,"d"); p.image(15,"e"); assertEquals(listOf("c"),closed)
        p.clear(); assertEquals(listOf("c","d","e"),closed)
        p.clear(); assertEquals(3,closed.size)
    }
    @Test fun duplicateImageAndEvictedResultsDoNotLeakOrMismatch() {
        val closed=mutableListOf<String>(); val paired=mutableListOf<String>()
        val p=TimestampPairer<String,String>(1,{closed.add(it)}) { i,r -> paired.add("$i/$r") }
        p.image(1,"old"); p.image(1,"new"); assertEquals(listOf("old"),closed)
        p.result(2,"r2"); p.result(3,"r3"); p.image(2,"two")
        assertTrue(paired.isEmpty()); p.clear(); assertEquals(listOf("old","new","two"),closed)
    }
}
