package io.github.c52e.filcamera.imaging

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class BilinearRawDeveloperTest {
    private val identity=floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f)
    private fun frame(w: Int=10,h: Int=8,pattern: BayerPattern=BayerPattern.RGGB,
                      sample: (Int,Int,Int)->Int): RawFrame {
        // Non-zero buffer position, padded rows and four-byte pixel stride.
        val stride=w*4+8; val buffer=ByteBuffer.allocate(7+stride*h).order(ByteOrder.LITTLE_ENDIAN)
        for(y in 0 until h) for(x in 0 until w) buffer.putShort(7+y*stride+x*4,sample(x,y,pattern.channels[(y%2)*2+x%2]).toShort())
        buffer.position(7)
        return RawFrame(w,h,stride,4,buffer,pattern,FloatArray(4),10000f,FloatArray(4){1f},identity)
    }
    @Test fun constantColorAllPatternsAndOddCrops() {
        for(pattern in BayerPattern.entries) {
            val values=intArrayOf(2000,3000,1500,4000)
            val f=frame(pattern=pattern) {_,_,c -> values[c] }.copy(gains=floatArrayOf(1f,1f,2f,1f),left=1,top=1,right=9,bottom=7)
            for(cap in listOf(100,17,1)) {
                val image=BilinearRawDeveloper(cap).develop(f)
                for(i in image.rgb.indices step 3) for(c in 0..2) {
                    val m=ColorMath.srgbTo2020
                    assertEquals(m[c*3]*.2f+m[c*3+1]*.3f+m[c*3+2]*.4f,image.rgb[i+c],.000001f)
                }
            }
        }
    }
    @Test fun reconstructsSpatialRampInsteadOfReplicatingCfaCells() {
        val f=frame {x,y,_ -> 1000+100*x+200*y}
        val out=BilinearRawDeveloper().develop(f)
        assertEquals(10,out.width); assertEquals(8,out.height)
        for(y in 1 until 7) for(x in 1 until 9) for(c in 0..2)
            assertEquals((1000+100*x+200*y)/10000f,out.rgb[(y*10+x)*3+c],.00001f)
    }
    @Test fun boundsAreContinuousAndIncludeOddEdges() {
        val f=frame(w=101,h=77) {_,_,_->2000}
        val out=BilinearRawDeveloper(3000).develop(f)
        assertTrue(out.width*out.height<=3000)
        assertTrue(out.width*out.height>2800)
        assertTrue(out.rgb.all {abs(it-.2f)<.00001f})
        val tiny=BilinearRawDeveloper(1).develop(frame(w=2,h=2){_,_,_->2000})
        assertEquals(1,tiny.width*tiny.height)
        assertTrue(tiny.rgb.all {abs(it-.2f)<.00001f})
        for((width,height) in listOf(101 to 2,2 to 101)) {
            val thin=BilinearRawDeveloper(1).develop(frame(w=width,h=height){_,_,_->2000})
            assertEquals(1,thin.width*thin.height)
        }
    }
    @Test fun blackLevelsAreSensorPositionsAndHeadroomSurvives() {
        for(pattern in BayerPattern.entries) {
            val blacks=floatArrayOf(0f,200f,400f,600f)
            val f=frame(pattern=pattern) {x,y,_ -> (blacks[y%2*2+x%2]+.8f*(10000-blacks[y%2*2+x%2])).toInt() }
                .copy(black=blacks,gains=FloatArray(4){2f})
            assertTrue(BilinearRawDeveloper().develop(f).rgb.all {abs(it-1.6f)<.00001f})
        }
    }
    @Test fun shadingInterpolationAndChannelOrder() {
        val grid=ShadingGrid(2,2,FloatArray(16) {1f+it/4+it%4*.1f})
        assertEquals(2.7f,grid.gain(2,.5f,.5f),.000001f)
        assertEquals(4.3f,grid.gain(3,1f,1f),.000001f)
        val gains=floatArrayOf(2f,3f,4f,5f)
        for(pattern in BayerPattern.entries) {
            val out=BilinearRawDeveloper().develop(frame(pattern=pattern) {_,_,c -> (10000/gains[c]).toInt() }
                .copy(shading=ShadingGrid(1,1,gains)))
            assertTrue(out.rgb.all {abs(it-1f)<.0002f})
        }
        assertThrows(IllegalArgumentException::class.java) {ShadingGrid(1,1,floatArrayOf(1f,1f,1f,Float.NaN))}
    }
    @Test fun rejectsTruncatedInvalidAndInterruptedInputs() {
        val f=frame {_,_,_->2000}
        assertThrows(IllegalArgumentException::class.java) {BilinearRawDeveloper().develop(f.copy(bytes=ByteBuffer.allocate(5)))}
        assertThrows(IllegalArgumentException::class.java) {BilinearRawDeveloper().develop(f.copy(white=0f))}
        assertThrows(IllegalArgumentException::class.java) {BilinearRawDeveloper().develop(f.copy(rowStride=Int.MAX_VALUE))}
        Thread.currentThread().interrupt()
        try {assertThrows(InterruptedException::class.java) {BilinearRawDeveloper().develop(f)}}
        finally {Thread.interrupted()}
    }
    @Test fun spatialShadingRecoversCalibratedFlatField() {
        val grid=ShadingGrid(2,2,FloatArray(16){1f+it*.15f})
        for(pattern in BayerPattern.entries) {
            val f=frame(pattern=pattern) {x,y,c -> (4000/grid.gain(c,x/9f,y/7f)).toInt()}.copy(shading=grid)
            assertTrue(BilinearRawDeveloper(31).develop(f).rgb.all {abs(it-.4f)<.0004f})
        }
    }
}
