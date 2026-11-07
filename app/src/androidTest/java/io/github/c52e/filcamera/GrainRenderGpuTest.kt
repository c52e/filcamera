package io.github.c52e.filcamera

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class GrainRenderGpuTest {
    @Test fun renderSyntheticGrain() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val task=FutureTask {
            val width=320;val height=240
            val input=ByteBuffer.allocateDirect(width*height*12).order(ByteOrder.nativeOrder())
            val floats=input.asFloatBuffer()
            repeat(width*height) {p->repeat(3) {floats.put(.01f+.4f*(p%width)/width)}}
            val assets=PreviewLutStore(app).prepareAssets()
            try {
                FilmEngine.testResidentEngine(true)
                val output=FilmOutput.buffer(width,height)
                val base=FilmRecipe(filmDiffusionStrength=0,printDiffusionStrength=0,halationStrength=0)
                fun render(recipe: FilmRecipe): ByteArray {
                    val result=JSONObject(FilmEngine.render(assets.path,"",width,height,recipe,
                        android.os.SystemClock.elapsedRealtimeNanos(),"",123L,output,input))
                    assertFalse(result.getBoolean("cancelled"))
                    val pixels=ByteArray(output.capacity())
                    output.duplicate().apply {clear();get(pixels)}
                    return pixels
                }
                val off=render(base.copy(grainStrength=0))
                val normal=render(base)
                val light=render(base.copy(grainStrength=500))
                val strong=render(base.copy(grainStrength=2000))
                assertFalse(off.contentEquals(normal))
                assertFalse(light.contentEquals(off))
                assertFalse(light.contentEquals(normal))
                assertFalse(strong.contentEquals(normal))
                assertArrayEquals(light,render(base.copy(grainStrength=500)))
            } finally {FilmEngine.close();FilmEngine.testResidentEngine(false)}
        }
        FilmWorker.execute {task.run()};task.get(120,TimeUnit.SECONDS)
    }

    /** Reprocess a retained DNG without changing it or publishing another photograph. */
    @Test fun renderRetainedDng() {
        val relative=InstrumentationRegistry.getArguments().getString("retainedDng")
        assumeNotNull(relative)
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val source=File(app.filesDir,checkNotNull(relative))
        val hash=PairCaptureStore.hash(source)
        val task=FutureTask {
            val input=EditInput()
            try {
                val prepared=input.prepare(source,hash,CaptureTiming(),fullResolution=true,cancelled={false})
                android.util.Log.i("GrainRenderProbe","retained DNG ${prepared.width}x${prepared.height}")
                val recipe=FilmRecipe.parse(prepared.record.getJSONObject("recipe"))
                val assets=PreviewLutStore(app).prepareAssets()
                val output=FilmOutput.buffer(prepared.width,prepared.height)
                val result=JSONObject(FilmEngine.render(assets.path,"",prepared.width,prepared.height,recipe,
                    android.os.SystemClock.elapsedRealtimeNanos(),"",FilmEngine.recordedSeed(prepared.record) ?: 123L,
                    output,prepared.pixels,prepared.gpuImage))
                assertFalse(result.getBoolean("cancelled"))
                assertEquals(hash,PairCaptureStore.hash(source))
                val jpg=File(app.cacheDir,"grain-retry.jpg")
                PhotoOutput.write(output,prepared.width,prepared.height,jpg,prepared.framing)
                assertTrue(jpg.length()>0)
            } finally {input.close();FilmEngine.close()}
        }
        FilmWorker.execute {task.run()};task.get(180,TimeUnit.SECONDS)
    }
}
