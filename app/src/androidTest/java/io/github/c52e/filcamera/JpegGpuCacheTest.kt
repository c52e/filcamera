package io.github.c52e.filcamera

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.github.c52e.filcamera.rawvulkan.VulkanRawDeveloper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class JpegGpuCacheTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun worker(action: ()->Unit) {
        val task=FutureTask(Callable {action()});FilmWorker.execute {task.run()};task.get(300,TimeUnit.SECONDS)
    }
    private fun fixture(shift: Int): File {
        val file=File(app.cacheDir,"jpeg-gpu-${UUID.randomUUID()}.jpg")
        val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
        try {
            for(y in 0 until bitmap.height) for(x in 0 until bitmap.width)
                bitmap.setPixel(x,y,android.graphics.Color.rgb((x+shift)%256,y%256,(x+y+shift)%256))
            file.outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it))}
        } finally {bitmap.recycle()}
        return file
    }
    private fun render(assets: File,prepared: EditInput.Prepared,recipe: FilmRecipe,enabled: Boolean,seed: Long=0,cpu: java.nio.ByteBuffer?=null,capture: ((java.nio.ByteBuffer)->Unit)?=null): JSONObject {
        val output=FilmOutput.buffer(prepared.width,prepared.height)
        val start=SystemClock.elapsedRealtimeNanos()
        val result=JSONObject(FilmEngine.render(assets.path,"",prepared.width,prepared.height,recipe,start,"",seed,output,cpu ?: prepared.pixels,if(cpu==null) prepared.gpuImage else 0,cacheFilm=enabled))
        result.put("callMs",(SystemClock.elapsedRealtimeNanos()-start)/1e6)
        assertFalse(result.getBoolean("cancelled"))
        val digest=MessageDigest.getInstance("SHA-256");digest.update(output.duplicate().apply {clear()})
        capture?.invoke(output.duplicate().apply {clear()})
        result.put("rgbaSha256",digest.digest().joinToString("") {"%02x".format(it.toInt() and 255)})
        if(cpu==null) {
            assertEquals(0L,result.getJSONObject("gpuTransfers").getLong("imageUploadBytes"))
            assertEquals(0L,result.getJSONObject("gpuTransfers").getLong("intermediateReadbackBytes"))
        }
        if(result.getJSONObject("filmCache").getString("state")=="hit") {
            val stages=result.getJSONObject("stageTimings").getJSONObject("stages_ms")
            for(stage in listOf("filming_expose","develop","halation","grain","dir_couplers")) assertEquals(stage,0.0,stages.optDouble(stage,0.0),0.0)
        }
        return result
    }
    private fun state(result: JSONObject,expected: String) {assertEquals(expected,result.getJSONObject("filmCache").getString("state"))}
    private fun equal(a: JSONObject,b: JSONObject) {assertEquals(a.getString("rgbaSha256"),b.getString("rgbaSha256"))}

    @Test fun cacheParityInvalidationAndCleanup()=worker {
        val first=fixture(0);val second=fixture(70)
        val repository=PhotoRepository(app);val photos=mutableListOf<PhotoRepository.Photo>();val input=EditInput()
        val baseline=FilmEngine.liveGpuImages()
        try {
            val photo=repository.importPhoto(Uri.fromFile(first));photos+=photo
            val hash=PairCaptureStore.hash(photo.source)
            var prepared=input.prepare(photo.source,hash,CaptureTiming(),false,{false})
            assertNull(prepared.pixels);assertTrue(prepared.gpuImage!=0L)
            assertEquals(baseline+1,FilmEngine.liveGpuImages())
            assertSame(prepared,input.prepare(photo.source,hash,CaptureTiming(),false,{false}))
            val assets=PreviewLutStore(app).prepareAssets();val base=FilmRecipe()
            val (cpu,_)=BitmapCpuReference.prepare(photo.source,false,{false})
            try {
                val oldBytes=ByteArray(prepared.width*prepared.height*4);val gpuBytes=ByteArray(oldBytes.size)
                render(assets,prepared,base,false,cpu=cpu.pixels,capture={it.get(oldBytes)})
                val gpu=render(assets,prepared,base,false,capture={it.get(gpuBytes)})
                // The old host spatial route and resident GPU route use different
                // arithmetic. Apply the repository's existing numerical gates.
                val histogram=IntArray(256);var square=0L;var samples=0
                for(i in oldBytes.indices) if(i%4!=3) {
                    val error=kotlin.math.abs((oldBytes[i].toInt() and 255)-(gpuBytes[i].toInt() and 255))
                    histogram[error]++;square+=error.toLong()*error;samples++
                }
                val rms=kotlin.math.sqrt(square.toDouble()/samples)
                var cumulative=0;var p99=0
                for(i in histogram.indices) {cumulative+=histogram[i];if(cumulative>=kotlin.math.ceil(samples*.99).toInt()) {p99=i;break}}
                val maximum=histogram.indexOfLast {it!=0}
                val metrics=JSONObject().put("rms",rms).put("p99",p99).put("max",maximum)
                File(app.cacheDir,"jpeg-gpu-quality.json").writeText(metrics.toString(2))
                assertTrue(metrics.toString(),rms<=1.0 && p99<=3 && maximum<=16)
                // On the same GPU spatial route, identical converted pixels
                // must be byte-identical regardless of their transport.
                val bitmap=BitmapEditInput.decode(photo.source,maxPixels=3_000_000,wideColor=true)
                FilmEngine.testResidentEngine(true)
                try {
                    VulkanRawDeveloper.convertSrgbBitmap(bitmap,BitmapEditInput.srgbToProPhoto,readbackForTest=true).use {
                        equal(render(assets,prepared,base,false,cpu=it.pixels),gpu)
                    }
                } finally {FilmEngine.testResidentEngine(false);bitmap.recycle()}
            } finally {cpu.close()}
            for(recipe in listOf(base.copy(print=2),base.copy(contrast=25),base.copy(magenta=3),base.copy(yellow=3),base.copy(printDiffusion=false))) {
                state(render(assets,prepared,base,true),"miss")
                val cached=render(assets,prepared,recipe,true);state(cached,"hit")
                val control=render(assets,prepared,recipe,false);state(control,"disabled");equal(cached,control)
            }
            state(render(assets,prepared,base,true),"miss")
            val changed=render(assets,prepared,base.copy(film=2),true);state(changed,"miss")
            equal(changed,render(assets,prepared,base.copy(film=2),false))
            state(render(assets,prepared,base,true),"miss")
            state(render(assets,prepared,base,true,seed=1),"miss")
            val originalImage=prepared.gpuImage
            val other=repository.importPhoto(Uri.fromFile(second));photos+=other
            prepared=input.prepare(other.source,PairCaptureStore.hash(other.source),CaptureTiming(),false,{false})
            assertNotEquals(originalImage,prepared.gpuImage)
            assertEquals(baseline+1,FilmEngine.liveGpuImages())
            state(render(assets,prepared,base,true),"miss")
            val hit=render(assets,prepared,base,true);state(hit,"hit");equal(hit,render(assets,prepared,base,false))
            input.close();assertEquals(baseline,FilmEngine.liveGpuImages())
            // Cancellation after upload must release the newly adopted image.
            assertTrue(runCatching {
                input.prepare(photo.source,hash,CaptureTiming(),false,{FilmEngine.liveGpuImages()>baseline})
            }.exceptionOrNull() is InterruptedException)
            assertEquals(baseline,FilmEngine.liveGpuImages())
            prepared=input.prepare(photo.source,hash,CaptureTiming(),false,{false})
            state(render(assets,prepared,base,true),"miss")
            FilmEngine.testGpuFailure(true)
            try {assertTrue(runCatching {render(assets,prepared,base.copy(print=1),true)}.isFailure)}
            finally {FilmEngine.testGpuFailure(false)}
            state(render(assets,prepared,base,true),"miss")
            FilmEngine.close()
            state(render(assets,prepared,base,true),"miss")
        } finally {
            FilmEngine.testGpuFailure(false);input.close();FilmEngine.close()
            assertEquals(baseline,FilmEngine.liveGpuImages())
            photos.forEach(repository::deletePhoto);first.delete();second.delete()
        }
    }

    /** Real-photo preview only: never loads or renders the full-resolution input. */
    @Test fun realJpegPreviewCachePerformance() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("jpegCachePerformance")=="true")
        worker {
            val source=File(app.cacheDir,"jpeg-performance-input.jpg");check(source.isFile)
            val repository=PhotoRepository(app);val photo=repository.importPhoto(Uri.fromFile(source));val input=EditInput()
            val rows=JSONArray();val timing=CaptureTiming()
            val report=JSONObject().put("model",Build.MODEL).put("recipe",FilmRecipe().json()).put("runs",rows)
            val file=File(app.cacheDir,"jpeg-gpu-cache-performance.json")
            try {
                val prepared=input.prepare(photo.source,PairCaptureStore.hash(photo.source),timing,false,{false})
                report.put("prepare",timing.json()).put("width",prepared.width).put("height",prepared.height)
                val assets=PreviewLutStore(app).prepareAssets();val base=FilmRecipe()
                for(index in 1..3) {
                    val edited=base.copy(print=index,magenta=index)
                    fun record(label: String,result: JSONObject) {result.put("case",label).put("iteration",index);rows.put(result);file.writeText(report.toString(2))}
                    val control=render(assets,prepared,edited,false);state(control,"disabled");record("control",control)
                    val prime=render(assets,prepared,base,true);state(prime,"miss");record("prime",prime)
                    val hit=render(assets,prepared,edited,true);state(hit,"hit");equal(control,hit);record("hit",hit)
                }
                report.put("passed",true)
            } finally {
                input.close();FilmEngine.close();report.put("liveGpuImagesAfterClose",FilmEngine.liveGpuImages());file.writeText(report.toString(2));repository.deletePhoto(photo)
            }
        }
    }
}
