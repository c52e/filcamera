package io.github.c52e.filcamera

import androidx.test.platform.app.InstrumentationRegistry
import io.github.c52e.filcamera.imaging.*
import io.github.c52e.filcamera.rawvulkan.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.*

/** Public synthetic regressions; the optional private fixture is never packaged. */
class RawHighlightsTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun worker(block: ()->Unit) {
        val task=FutureTask {try {block()} finally {
            FilmEngine.testRelaxed(true);FilmEngine.testWorkgroup(256,16,8);FilmEngine.close()
        }}
        FilmWorker.execute {task.run()};task.get(240,TimeUnit.SECONDS)
    }
    private fun frame(pattern: BayerPattern,clipped: Boolean=true): RawFrame {
        val w=53;val h=39;val stride=w*3+5
        val bytes=ByteBuffer.allocateDirect(stride*h+8).order(ByteOrder.LITTLE_ENDIAN)
        bytes.position(4)
        val gains=floatArrayOf(2.55f,1f,1.03f,1.52f)
        for(y in 0 until h) for(x in 0 until w) {
            val c=pattern.channels[(y%2)*2+x%2]
            val light=if(!clipped) .8f+.001f*x else if(x>w/2) 3f else .28f+.006f*x+.003f*y
            val raw=(64+959*min(1f,light/gains[c])).roundToInt()
            bytes.putShort(4+y*stride+x*3,raw.toShort())
        }
        return RawFrame(w,h,stride,3,bytes,pattern,FloatArray(4){64f},1023f,gains,
            floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f),1,1,w-1,h-1,
            ShadingGrid(2,2,FloatArray(16){1f+(it/4)*.08f}))
    }
    // Independent double-precision reference, followed by the retained CPU demosaic.
    private fun oracle(frame: RawFrame): LinearImage = with(frame) {
        val w=right-left;val h=bottom-top;val src=bytes.slice().order(ByteOrder.LITTLE_ENDIAN)
        fun mirror(i: Int,n: Int)=if(i<0) -i else if(i>=n) 2*n-2-i else i
        fun channel(x: Int,y: Int)=pattern.channels[((y+top)%2)*2+(x+left)%2]
        fun color(c: Int)=if(c==3) 2 else if(c==0) 0 else 1
        val a=DoubleArray(w*h) {i->
            val x=i%w;val y=i/w;val site=((y+top)%2)*2+(x+left)%2
            val raw=src.getShort((y+top)*rowStride+(x+left)*pixelStride).toInt() and 65535
            max(0.0,(raw-black[site]).toDouble()/(white-black[site]))*gains[channel(x,y)]
        }
        fun ref(x: Int,y: Int): Double {
            val sums=DoubleArray(3);val counts=IntArray(3)
            for(dy in -1..1) for(dx in -1..1) {
                val xx=mirror(x+dx,w);val yy=mirror(y+dy,h);val c=color(channel(xx,yy))
                sums[c]+=a[yy*w+xx];counts[c]++
            }
            val own=color(channel(x,y));val others=(0..2).filter {it!=own}
            return ((Math.cbrt(sums[others[0]]/counts[others[0]])+Math.cbrt(sums[others[1]]/counts[others[1]]))/2).pow(3)
        }
        val mw=(w+2)/3;val mh=(h+2)/3;val masks=IntArray(mw*mh)
        for(y in 0 until h) for(x in 0 until w) if(a[y*w+x]>=.987*gains[channel(x,y)])
            masks[(y/3)*mw+x/3]=masks[(y/3)*mw+x/3] or (1 shl color(channel(x,y)))
        val sums=DoubleArray(3);val counts=IntArray(3)
        for(y in 0 until h) for(x in 0 until w) {
            val c=color(channel(x,y));val clip=.987*gains[channel(x,y)];val v=a[y*w+x]
            var near=false
            for(yy in max(0,y/3-3)..min(mh-1,y/3+3)) for(xx in max(0,x/3-3)..min(mw-1,x/3+3))
                if(masks[yy*mw+xx] and (1 shl c)!=0) near=true
            if(near && v<clip && v>.2*clip) {sums[c]+=v-ref(x,y);counts[c]++}
        }
        val out=ByteBuffer.allocateDirect(width*height*2).order(ByteOrder.LITTLE_ENDIAN)
        for(y in 0 until h) for(x in 0 until w) {
            val c=channel(x,y);var v=a[y*w+x]
            if(v>=.987*gains[c]) v=max(v,ref(x,y)+if(counts[color(c)]>100) sums[color(c)]/counts[color(c)] else 0.0)
            v*=shading!!.gain(c,x.toFloat()/(w-1),y.toFloat()/(h-1))
            out.putShort(((y+top)*width+x+left)*2,(v*4096).roundToInt().toShort())
        }
        out.rewind()
        EdgeDirectedRawDeveloper(w*h).develop(RawFrame(width,height,width*2,2,out,pattern,
            FloatArray(4),4096f,FloatArray(4){1f},cameraToSrgb,left,top,right,bottom))
    }
    @Test fun reconstructionMatchesOracleAndLeavesUnclippedInputsAlone()=worker {
        for(relaxed in listOf(false,true)) for(pattern in BayerPattern.entries) {
            FilmEngine.testRelaxed(relaxed)
            val frame=frame(pattern);val expected=oracle(frame);val developer=VulkanRawDeveloper(frame.width*frame.height)
            val actual=developer.develop(frame)
            val error=expected.rgb.zip(actual.rgb).maxOf {(a,b)->abs(a-b)}
            assertTrue("$pattern relaxed=$relaxed max error $error",error<.002f)
            assertEquals(true,developer.diagnostics["highlightClipped"])
            val clean=frame(pattern,false)
            val on=developer.develop(clean);val off=VulkanRawDeveloper(clean.width*clean.height,false).develop(clean)
            assertEquals(false,developer.diagnostics["highlightClipped"])
            assertTrue(on.rgb.zip(off.rgb).all {(a,b)->abs(a-b)<1e-6f})
        }
        FilmEngine.testRelaxed(true)
    }
    @Test fun flatSaturatedWhiteIsLessMagentaAndEdgesStayFinite()=worker {
        for(pattern in BayerPattern.entries) for(size in listOf(2,3,17,64)) {
            val bytes=ByteBuffer.allocateDirect(size*size*2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(size*size){bytes.putShort(1023)};bytes.rewind()
            val f=RawFrame(size,size,size*2,2,bytes,pattern,FloatArray(4){64f},1023f,
                floatArrayOf(2.55f,1f,1f,1.52f),floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f))
            val on=VulkanRawDeveloper(size*size).develop(f);val off=VulkanRawDeveloper(size*size,false).develop(f)
            assertTrue(on.rgb.all {it.isFinite()})
            fun chroma(image: LinearImage): Float {
                // Linear luminance-independent channel spread in the output's Rec.2020 space.
                return image.rgb.toList().chunked(3).map {it.max()-it.min()}.average().toFloat()
            }
            assertTrue("$pattern $size should reduce the cast",chroma(on)<chroma(off))
        }
    }
    @Test fun workgroupReductionResizeRotationAndRepeatability()=worker {
        val f=frame(BayerPattern.GBRG)
        val crop=VulkanRawDeveloper.Crop(3,2,43,31)
        var expected: FloatArray?=null
        for(x in listOf(8,16)) for(y in listOf(8,16)) {
            FilmEngine.testWorkgroup(256,x,y)
            val developer=VulkanRawDeveloper(600)
            fun result()=developer.developProPhoto(f,90,crop).use {
                assertTrue(it.width*it.height<=600)
                FloatArray(it.width*it.height*3).apply {it.pixels.asFloatBuffer().get(this)}
            }
            val a=result();assertArrayEquals(a,result(),0f)
            expected?.let {assertArrayEquals(it,a,2e-5f)} ?: run {expected=a}
        }
        assertEquals(0L,FilmEngine.liveGpuImages())
    }
    @Test fun privateFixturePerformanceAndRender() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("rawHighlightsFixture")=="true")
        worker {
            val file=File(app.cacheDir,"highlight-input.dng");val hash=PairCaptureStore.hash(file)
            val raw=DngRawFile.read(file);val rows=JSONArray()
            for(limit in listOf(3_000_000,RawLimits.MAX_PIXELS)) {
                // Warm each pipeline variant before collecting paired timings.
                for(enabled in listOf(false,true)) VulkanRawDeveloper(limit,enabled).developGpuProPhoto(raw.frame,raw.rotation).close()
                repeat(5) {iteration->
                    for(enabled in if(iteration%2==0) listOf(false,true) else listOf(true,false)) {
                        val developer=VulkanRawDeveloper(limit,enabled)
                        developer.developGpuProPhoto(raw.frame,raw.rotation).close()
                        rows.put(JSONObject(developer.diagnostics).put("limit",limit).put("iteration",iteration))
                    }
                }
            }
            val assets=prepareFilmAssets(app);val recipe=FilmRecipe.restore(raw.record)
            for(enabled in listOf(false,true)) {
                val input=VulkanRawDeveloper(3_000_000,enabled).developGpuProPhoto(raw.frame,raw.rotation)
                input.use {
                    val rgba=FilmOutput.buffer(it.width,it.height)
                    FilmEngine.render(assets.path,it.width,it.height,recipe,android.os.SystemClock.elapsedRealtimeNanos(),
                        FilmEngine.recordedSeed(raw.record) ?: 0L,rgba=rgba,gpuImage=it.gpuImage)
                    val bitmap=FilmOutput.bitmap(rgba,it.width,it.height)
                    try {PhotoOutput.write(bitmap,File(app.cacheDir,"highlight-${if(enabled) "on" else "off"}.jpg"))}
                    finally {bitmap.recycle()}
                }
            }
            assertEquals(hash,PairCaptureStore.hash(file))
            File(app.cacheDir,"raw-highlights-performance.json").writeText(JSONObject().put("rows",rows).toString(2))
        }
    }
}
