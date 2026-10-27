package io.github.c52e.filcamera

import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Optional probe: supply cache/jpeg-performance-input.jpg and jpegPerformance=true. */
class JpegPerformanceTest {
    @Test fun realJpegStages() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("jpegPerformance")=="true")
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val source=File(app.cacheDir,"jpeg-performance-input.jpg")
        check(source.isFile)
        val task=FutureTask(Callable {
            val repository=PhotoRepository(app)
            val photo=repository.importPhoto(Uri.fromFile(source))
            val runs=JSONArray()
            val report=JSONObject().put("model",Build.MODEL).put("android",Build.VERSION.RELEASE)
                .put("sourceBytes",source.length()).put("recipe",FilmRecipe().json()).put("grainSeed",checkNotNull(FilmEngine.recordedSeed(JSONObject(photo.record.readText()))))
                .put("scope","verify/hash, prepare, assets, output allocation, film render, JPEG encode; excludes import, catalog save, MediaStore publication and worker queue")
                .put("runs",runs)
            val reportFile=File(app.cacheDir,"jpeg-performance.json")
            fun persist() {reportFile.writeText(report.toString(2))}
            try {
                for(full in listOf(false,true)) repeat(3) {index->
                    val input=EditInput()
                    val output=File(app.cacheDir,"jpeg-performance-output.jpg")
                    FilmEngine.close()
                    val timing=CaptureTiming()
                    try {
                        timing.stage("inputVerify") {repository.verifySource(photo)}
                        val hash=timing.stage("sourceHash") {PairCaptureStore.hash(photo.source)}
                        val prepared=input.prepare(photo.source,hash,timing,full,{false})
                        val assets=timing.stage("assets") {PreviewLutStore(app).prepareAssets {true}}
                        val rgba=timing.stage("outputAllocation") {FilmOutput.buffer(prepared.width,prepared.height)}
                        val result=timing.stage("nativeCall") {JSONObject(FilmEngine.render(assets.path,"",prepared.width,prepared.height,FilmRecipe(),SystemClock.elapsedRealtimeNanos(),"",checkNotNull(FilmEngine.recordedSeed(prepared.record)),rgba,prepared.pixels,prepared.gpuImage,cacheFilm=!full))}
                        check(!result.getBoolean("cancelled"))
                        timing.stage("photoEncode") {PhotoOutput.write(rgba,prepared.width,prepared.height,output,prepared.framing)}
                        runs.put(JSONObject().put("mode",if(full) "full" else "preview").put("iteration",index)
                            .put("width",prepared.width).put("height",prepared.height).put("timing",timing.json())
                            .put("native",result).put("outputBytes",output.length()).put("pssKb",android.os.Debug.getPss()))
                        persist()
                    } catch(e: Throwable) {report.put("failure",e.toString());persist();throw e}
                    finally {input.close();FilmEngine.close();output.delete()}
                }
            } finally {repository.deletePhoto(photo)}
        })
        FilmWorker.execute {task.run()};task.get(600,TimeUnit.SECONDS)
    }
}
