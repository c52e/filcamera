package io.github.c52e.filcamera

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assume.assumeNotNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class RenderPerformanceTest {
    @Test fun retainedPerformance() {
        val relative=InstrumentationRegistry.getArguments().getString("retainedDng")
        assumeNotNull(relative)
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val task=FutureTask {
            val source=File(app.filesDir,checkNotNull(relative))
            val hash=PairCaptureStore.hash(source)
            val input=DngEditInput(app)
            val runs=JSONArray()
            android.system.Os.setenv("SPK_GPU_DEBUG","1",true)
            try {
                val timing=CaptureTiming()
                val prepared=input.prepare(source,hash,timing,true,{false})
                val assets=PreviewLutStore(app).prepareAssets()
                val recipe=FilmRecipe.parse(prepared.record.getJSONObject("recipe"))
                val rgba=FilmOutput.buffer(prepared.width,prepared.height)
                val prepareTiming=timing.json()
                var referenceHash: String?=null
                repeat(5) { index ->
                    if(index==0 || index==4) android.system.Os.setenv("FILMCAMERA_DIFFUSION_CACHE_OFF","1",true)
                    else android.system.Os.unsetenv("FILMCAMERA_DIFFUSION_CACHE_OFF")
                    android.util.Log.i("RenderPerf","BEGIN $index")
                    val result=JSONObject(FilmEngine.render(assets.path,"",prepared.width,prepared.height,recipe,
                        android.os.SystemClock.elapsedRealtimeNanos(),"",FilmEngine.recordedSeed(prepared.record) ?: 123L,
                        rgba,prepared.pixels,prepared.owner.gpuImage))
                    check(!result.getBoolean("cancelled"))
                    val digest=java.security.MessageDigest.getInstance("SHA-256")
                    val view=rgba.duplicate().apply {clear()}
                    digest.update(view)
                    val hash=digest.digest().joinToString("") {"%02x".format(it.toInt() and 255)}
                    if(referenceHash==null) referenceHash=hash else check(referenceHash==hash) {"Cache changed RGBA output at $index"}
                    result.put("rgbaSha256",hash).put("cacheEnabled",index!=0 && index!=4)
                    result.put("iteration",index)
                    runs.put(result)
                    File(app.cacheDir,"render-performance.json").writeText(JSONObject().put("width",prepared.width).put("height",prepared.height).put("recipe",recipe.json()).put("prepare",prepareTiming).put("runs",runs).toString(2))
                    android.util.Log.i("RenderPerf","END $index ms=${result.getDouble("renderMs")}")
                }
                // Exercise cache replacement when the editing resolution changes.
                for(full in listOf(false,true)) {
                    val resized=input.prepare(source,hash,CaptureTiming(),full,{false})
                    val output=FilmOutput.buffer(resized.width,resized.height)
                    var expected: ByteArray?=null
                    for(enabled in listOf(false,true)) {
                        if(enabled) android.system.Os.unsetenv("FILMCAMERA_DIFFUSION_CACHE_OFF")
                        else android.system.Os.setenv("FILMCAMERA_DIFFUSION_CACHE_OFF","1",true)
                        val result=JSONObject(FilmEngine.render(assets.path,"",resized.width,resized.height,recipe,
                            android.os.SystemClock.elapsedRealtimeNanos(),"",FilmEngine.recordedSeed(resized.record) ?: 123L,
                            output,resized.pixels,resized.owner.gpuImage))
                        check(!result.getBoolean("cancelled"))
                        val digest=java.security.MessageDigest.getInstance("SHA-256")
                        digest.update(output.duplicate().apply {clear()})
                        val actual=digest.digest()
                        if(expected==null)expected=actual else check(expected.contentEquals(actual)) {"Cache invalidation changed output at full=$full"}
                    }
                    android.util.Log.i("RenderPerf","RESIZE PASS ${resized.width}x${resized.height}")
                }
                check(hash==PairCaptureStore.hash(source))
            } finally {input.close();FilmEngine.close();android.system.Os.unsetenv("SPK_GPU_DEBUG");android.system.Os.unsetenv("FILMCAMERA_DIFFUSION_CACHE_OFF")}
        }
        FilmWorker.execute {task.run()};task.get(300,TimeUnit.SECONDS)
    }
}
