package io.github.c52e.filcamera

import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Supply the user-provided SDR P3 sample in cache/heic-input.heic. Preview renders only. */
class HeicImportTest {
    @Test fun sampleImportDecodeCacheAndExport() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("heicImport")=="true")
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val task=FutureTask(Callable {
            val source=File(app.cacheDir,"heic-input.heic");check(source.isFile)
            val repository=PhotoRepository(app);val input=EditInput()
            val photo=repository.importPhoto(Uri.fromFile(source))
            val exported=File(app.cacheDir,"heic-export-test.heic")
            val output=File(app.cacheDir,"heic-render-test.jpg")
            val report=JSONObject()
            try {
                val hash=PairCaptureStore.hash(source)
                assertEquals("heic",photo.kind);assertEquals("image/heic",photo.mime)
                assertEquals(hash,PairCaptureStore.hash(photo.source))
                assertEquals(FilmRecipe(),repository.captureRecipe(photo))
                assertEquals(photo.id,repository.scan().first {it.id==photo.id}.id)
                assertNotNull(photo.displayFile)
                val start=SystemClock.elapsedRealtimeNanos()
                val full=BitmapEditInput.decode(photo.source,wideColor=true)
                try {
                    assertEquals(4096,full.width);assertEquals(3072,full.height)
                    assertEquals(full.width to full.height,BitmapEditInput.dimensions(photo.source))
                    report.put("width",full.width).put("height",full.height).put("bitmapConfig",full.config.toString())
                } finally {full.recycle()}
                report.put("fullDecodeAndHeaderMs",(SystemClock.elapsedRealtimeNanos()-start)/1e6)
                val native=ImageDecoder.decodeBitmap(ImageDecoder.createSource(photo.source)) {decoder,info,_->
                    assertTrue(info.mimeType in listOf("image/heic","image/heif"))
                    assertTrue(checkNotNull(info.colorSpace).isWideGamut)
                    decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE;decoder.setTargetSize(512,384)
                }
                val converted=BitmapEditInput.decode(photo.source,maxEdge=512,wideColor=true)
                try {
                    val connector=ColorSpace.connect(checkNotNull(native.colorSpace),checkNotNull(converted.colorSpace))
                    for((x,y) in listOf(32 to 32,200 to 150,400 to 300)) {
                        val c=native.getColor(x,y);val expected=connector.transform(c.red(),c.green(),c.blue());val actual=converted.getColor(x,y)
                        assertEquals(expected[0],actual.red(),0.01f);assertEquals(expected[1],actual.green(),0.01f);assertEquals(expected[2],actual.blue(),0.01f)
                    }
                    report.put("sourceColorSpace",native.colorSpace.toString())
                } finally {native.recycle();converted.recycle()}
                val thumbnail=repository.thumbnail(photo.source)
                assertTrue(thumbnail.isFile)
                val timing=CaptureTiming()
                val prepared=input.prepare(photo.source,hash,timing,false,{false})
                report.put("prepare",timing.json()).put("previewWidth",prepared.width).put("previewHeight",prepared.height)
                assertTrue(prepared.width.toLong()*prepared.height<=3_000_000)
                assertTrue(prepared.gpuImage!=0L);assertNull(prepared.pixels)
                val seed=checkNotNull(FilmEngine.recordedSeed(prepared.record));assertEquals(hash.take(8).toLong(16),seed)
                val assets=prepareFilmAssets(app)
                fun render(recipe: FilmRecipe,cached: Boolean): Pair<JSONObject,java.nio.ByteBuffer> {
                    val rgba=FilmOutput.buffer(prepared.width,prepared.height)
                    val result=JSONObject(FilmEngine.render(assets.path,prepared.width,prepared.height,recipe,SystemClock.elapsedRealtimeNanos(),seed,rgba,prepared.pixels,prepared.gpuImage,cacheFilm=cached))
                    assertFalse(result.getBoolean("cancelled"))
                    assertEquals(0L,result.getJSONObject("gpuTransfers").getLong("imageUploadBytes"))
                    val digest=MessageDigest.getInstance("SHA-256");digest.update(rgba.duplicate().apply {clear()})
                    result.put("hash",digest.digest().joinToString("") {"%02x".format(it.toInt() and 255)})
                    return result to rgba
                }
                render(FilmRecipe(),true)
                val (cached,rgba)=render(FilmRecipe(print=1),true)
                assertEquals("hit",cached.getJSONObject("filmCache").getString("state"))
                assertEquals(cached.getString("hash"),render(FilmRecipe(print=1),false).first.getString("hash"))
                PhotoOutput.write(rgba,prepared.width,prepared.height,output,prepared.framing)
                repository.saveEdit(photo,output,cached.put("input",JSONObject().put("kind","heic")))
                assertNotNull(repository.scan().first {it.id==photo.id}.latest)
                repository.exportOriginal(photo,Uri.fromFile(exported));assertEquals(hash,PairCaptureStore.hash(exported))
                report.put("cacheHitMs",cached.getDouble("renderMs")).put("passed",true)
            } finally {
                input.close();FilmEngine.close();assertEquals(0L,FilmEngine.liveGpuImages())
                repository.deletePhoto(photo);exported.delete();output.delete()
                File(app.cacheDir,"heic-import-result.json").writeText(report.toString(2))
            }
        })
        FilmWorker.execute {task.run()};task.get(180,TimeUnit.SECONDS)
    }
}
