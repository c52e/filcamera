package io.github.c52e.filcamera

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Regression for large preview constant buffers; uses only synthetic LUT samples. */
class GpuPreviewDiagnosticTest {
    @Test fun probeLutMathModes() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val task=FutureTask {
            val recipe=FilmRecipe.load(app)
            val assets=PreviewLutStore(app,recipe=recipe).prepareAssets()
            val failures=ArrayList<String>()
            try { for(relaxed in listOf(true,false)) {
                val output=File(app.cacheDir,"gpu-preview-probe-$relaxed.rgba16f")
                try {
                    output.delete();FilmEngine.close();FilmEngine.testRelaxed(relaxed)
                    FilmEngine.bakePreviewLut(assets.path,android.os.SystemClock.elapsedRealtimeNanos(),output.path,recipe)
                    val bytes=output.readBytes()
                    PreviewLut.read(bytes,PreviewLut.digest(bytes),"gpu-regression","device-test",recipe)
                    assertFalse("LUT endpoints must differ",bytes.copyOfRange(0,6).contentEquals(bytes.copyOfRange(bytes.size-8,bytes.size-2)))
                    android.util.Log.i("GpuPreviewProbe","relaxed=$relaxed PASS bytes=${output.length()}")
                } catch(e: Exception) {
                    android.util.Log.e("GpuPreviewProbe","relaxed=$relaxed FAIL",e)
                    failures.add("relaxed=$relaxed: ${e.message}")
                } finally {output.delete();FilmEngine.close()}
            } } finally {FilmEngine.testRelaxed(true)}
            assertTrue(failures.joinToString("\n"),failures.isEmpty())
        }
        FilmWorker.execute {task.run()};task.get(120,TimeUnit.SECONDS)
    }
}
