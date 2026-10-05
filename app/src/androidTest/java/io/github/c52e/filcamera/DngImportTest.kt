package io.github.c52e.filcamera

import android.net.Uri
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Optional DNG import check. Supply a DNG in target cache as import-input.dng. */
class DngImportTest {
    @Test fun importAndDevelopDng() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("dngImport")=="true")
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val source=File(app.cacheDir,"import-input.dng")
        assertTrue("Supply import-input.dng before running this probe",source.isFile)
        val originalHash=PairCaptureStore.hash(source)
        val task=FutureTask(Callable {
            val repository=PhotoRepository(app)
            val photo=repository.importDng(Uri.fromFile(source))
            val input=DngEditInput(app)
            val output=File(app.cacheDir,"import-developed.jpg")
            try {
                val timing=CaptureTiming()
                val prepared=input.prepare(photo.source,PairCaptureStore.hash(photo.source),timing,cancelled={false})
                val recipe=repository.captureRecipe(photo)
                val assets=PreviewLutStore(app).prepareAssets()
                val pixels=FilmOutput.buffer(prepared.width,prepared.height)
                val result=JSONObject(FilmEngine.render(assets.path,"",prepared.width,prepared.height,recipe,
                    SystemClock.elapsedRealtimeNanos(),"",checkNotNull(FilmEngine.recordedSeed(prepared.record)),
                    pixels,prepared.pixels,prepared.owner.gpuImage))
                assertFalse(result.getBoolean("cancelled"))
                assertEquals(0L,result.getJSONObject("gpuTransfers").getLong("intermediateReadbackBytes"))
                PhotoOutput.write(pixels,prepared.width,prepared.height,output,prepared.framing)
                val size=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
                android.graphics.BitmapFactory.decodeFile(output.path,size)
                assertEquals(prepared.width,size.outWidth);assertEquals(prepared.height,size.outHeight)
                assertEquals(originalHash,PairCaptureStore.hash(source))
            } finally {
                input.close();FilmEngine.close()
                repository.deletePhoto(photo)
                output.delete()
            }
        })
        FilmWorker.execute {task.run()};task.get(120,TimeUnit.SECONDS)
    }
}
