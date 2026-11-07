package io.github.c52e.filcamera

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.github.c52e.filcamera.rawvulkan.NativeBuffer
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class LargePhotoGpuTest {
    /** Opt-in: the full-resolution spatial pipeline needs several GB of GPU memory. */
    @Test fun halationPreservesIdenticalRows() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("largePhotoRegression")=="true")
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val task=FutureTask(Callable {
            val assets=prepareFilmAssets(app)
            for((width,height) in listOf(259 to 33,7032 to 4688)) {
                val row=FloatArray(width*3) {i->
                    val x=(i/3).toFloat()/(width-1)
                    when(i%3) {0->.015f+.2f*x;1->.025f+.12f*x;else->.04f+.08f*x}
                }
                NativeBuffer.allocate(width*height*12) {buffer->
                    val floats=buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
                    repeat(height) {floats.put(row)}
                }.use {input->
                    NativeBuffer.allocate(width*height*4).use {output->
                        try {
                            val result=JSONObject(FilmEngine.render(assets.path,width,height,
                                FilmRecipe(filmDiffusionStrength=0,printDiffusionStrength=0,grainStrength=0),
                                SystemClock.elapsedRealtimeNanos(),123L,output.pixels,input.pixels,cacheFilm=false))
                            assertTrue(result.toString(),!result.getBoolean("cancelled"))
                            val bytes=output.pixels
                            var maxError=0
                            for(y in 1 until height) for(x in 0 until width) for(c in 0..3) {
                                val expected=bytes.get(x*4+c).toInt() and 255
                                val actual=bytes.get((y*width+x)*4+c).toInt() and 255
                                maxError=maxOf(maxError,kotlin.math.abs(actual-expected))
                            }
                            assertTrue("${width}x$height row mismatch: $maxError",maxError<=2)
                        } finally {FilmEngine.close()}
                    }
                }
            }
        })
        FilmWorker.execute {task.run()};task.get(180,TimeUnit.SECONDS)
    }
}
