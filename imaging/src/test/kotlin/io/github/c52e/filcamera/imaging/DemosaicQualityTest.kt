package io.github.c52e.filcamera.imaging

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class DemosaicQualityTest {
    private val identity=floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f)
    private fun frame(w: Int,h: Int,pattern: BayerPattern=BayerPattern.RGGB,signal: (Int,Int,Int)->Float): RawFrame {
        val buffer=ByteBuffer.allocate(w*h*2).order(ByteOrder.LITTLE_ENDIAN)
        for(y in 0 until h) for(x in 0 until w) {
            val c=pattern.channels[y%2*2+x%2]
            buffer.putShort(((signal(x,y,if(c==0) 0 else if(c==3) 2 else 1))*60000).roundToInt().toShort())
        }
        buffer.rewind()
        return RawFrame(w,h,w*2,2,buffer,pattern,FloatArray(4),60000f,FloatArray(4){1f},identity)
    }
    @Test fun constantColorAllCfasOddCropsHeadroomAndTinyEdges() {
        for(pattern in BayerPattern.entries) for((w,h) in listOf(2 to 2,3 to 7,13 to 11)) {
            val input=frame(w,h,pattern){_,_,c -> floatArrayOf(.2f,.3f,.4f)[c]}.copy(gains=FloatArray(4){4f})
            for(f in listOf(input,if(w>3) input.copy(left=1,top=1,right=w-1,bottom=h-1) else input)) {
                val out=EdgeDirectedRawDeveloper().develop(f)
                for(i in out.rgb.indices step 3) for(c in 0..2) {
                    val m=ColorMath.srgbTo2020
                    assertEquals(m[c*3]*.8f+m[c*3+1]*1.2f+m[c*3+2]*1.6f,out.rgb[i+c],.00001f)
                }
            }
        }
    }
    @Test fun knownSceneErrorAtDeliveredResolution() {
        val w=256; val h=192
        val scenes=listOf<Pair<String,(Int,Int,Int)->Float>>(
            "vertical_sine" to {x,_,_ -> (.4+.3*sin(x*2*PI/11)).toFloat()},
            "slanted_edge" to {x,y,_ -> (.1+.7/(1+exp(-(x-y*.63-61)*1.5))).toFloat()},
            "diagonal_sine" to {x,y,_ -> (.4+.3*sin((x+y*.6)*2*PI/13)).toFloat()},
            "color_edge" to {x,y,c -> if(x>y*.63+61) floatArrayOf(.8f,.15f,.05f)[c] else floatArrayOf(.05f,.25f,.7f)[c]}
        )
        for((name,signal) in scenes) {
            val f=frame(w,h,signal=signal)
            val baseline=BilinearRawDeveloper(w*h/4).develop(f)
            val candidate=EdgeDirectedRawDeveloper(w*h/4).develop(f)
            fun mse(image: LinearImage): Double {
                var error=0.0; var count=0
                for(y in 3 until image.height-3) for(x in 3 until image.width-3) {
                    val truth=FloatArray(3) {c -> (signal(x*2,y*2,c)+signal(x*2+1,y*2,c)+signal(x*2,y*2+1,c)+signal(x*2+1,y*2+1,c))*.25f}
                    for(c in 0..2) {
                        val m=ColorMath.srgbTo2020
                        val v=m[c*3]*truth[0]+m[c*3+1]*truth[1]+m[c*3+2]*truth[2]
                        error+=(image.rgb[(y*image.width+x)*3+c]-v).toDouble().pow(2); count++
                    }
                }
                return error/count
            }
            val old=mse(baseline); val next=mse(candidate)
            println("QUALITY $name bilinearMse=$old directionalMse=$next ratio=${next/old}")
            if(name!="color_edge") assertTrue("$name failed to improve",next<old*.9)
            else assertTrue("Colored edge regression",next<old*1.1)
        }
    }
    @Test fun calibrationStrideAndFailureContractsAreShared() {
        val base=frame(9,7){_,_,_->.25f}
        val padded=ByteBuffer.allocate(5+7*48).order(ByteOrder.LITTLE_ENDIAN)
        for(y in 0 until 7) for(x in 0 until 9) padded.putShort(5+y*48+x*4,15000)
        padded.position(5)
        val expected=EdgeDirectedRawDeveloper(10).develop(base)
        val actual=EdgeDirectedRawDeveloper(10).develop(base.copy(bytes=padded,rowStride=48,pixelStride=4))
        assertArrayEquals(expected.rgb,actual.rgb,0f)
        assertThrows(IllegalArgumentException::class.java) {EdgeDirectedRawDeveloper().develop(base.copy(white=0f))}
        Thread.currentThread().interrupt()
        try {assertThrows(InterruptedException::class.java) {EdgeDirectedRawDeveloper().develop(base)}} finally {Thread.interrupted()}
    }
    @Test fun flatFieldNoiseTradeoffIsBounded() {
        val random=java.util.Random(42)
        val f=frame(256,192){_,_,_->(.4+random.nextGaussian()*.006).toFloat()}
        fun mse(image: LinearImage)=image.rgb.sumOf {(it-.4f).toDouble().pow(2)}/image.rgb.size
        val old=mse(BilinearRawDeveloper(256*192/4).develop(f))
        val next=mse(EdgeDirectedRawDeveloper(256*192/4).develop(f))
        println("QUALITY flat_noise bilinearMse=$old directionalMse=$next ratio=${next/old}")
        assertTrue("Noise increase exceeds quality budget",next<old*1.4)
    }
    @Test fun spatialCalibrationWithOddCropAndDifferentGreenGains() {
        for(pattern in BayerPattern.entries) {
            val black=floatArrayOf(10f,20f,30f,40f); val gains=floatArrayOf(1.5f,1.2f,1.8f,2f)
            val grid=ShadingGrid(2,2,FloatArray(16){1f+it*.05f})
            val b=ByteBuffer.allocate(11*9*2).order(ByteOrder.LITTLE_ENDIAN)
            for(y in 0 until 9) for(x in 0 until 11) {
                val site=y%2*2+x%2; val c=pattern.channels[site]
                val shade=grid.gain(c,(x-1)/8f,(y-1)/6f)
                b.putShort((black[site]+.25f/gains[c]/shade*(60000-black[site])).roundToInt().toShort())
            }
            b.rewind()
            val f=RawFrame(11,9,22,2,b,pattern,black,60000f,gains,identity,1,1,10,8,grid)
            assertTrue(EdgeDirectedRawDeveloper().develop(f).rgb.all {abs(it-.25f)<.0001f})
        }
    }
}
