package io.github.c52e.filcamera

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.media.ExifInterface
import android.net.Uri
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class JpegImportTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(color: Int=Color.rgb(128,128,128)): File {
        val file=File(context.cacheDir,"jpeg-test-${UUID.randomUUID()}.jpeg")
        val bitmap=Bitmap.createBitmap(128,96,Bitmap.Config.ARGB_8888)
        try {bitmap.eraseColor(color);file.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,100,it))}} finally {bitmap.recycle()}
        return file
    }
    private fun worker(action: ()->Unit) {
        val task=FutureTask(Callable {action()});FilmWorker.execute {task.run()};task.get(120,TimeUnit.SECONDS)
    }
    @Test fun editingPreviewCanBeSavedAndOriginalIsVerified() {
        val file=fixture();val repository=PhotoRepository(context).apply {failPublish=true}
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        lateinit var photo: PhotoRepository.Photo
        var session: EditSession?=null
        worker {photo=repository.importPhoto(Uri.fromFile(file))}
        try {
            instrumentation.runOnMainSync {session=EditSession(context,repository).also {it.open(photo,FilmRecipe())}}
            val editing=checkNotNull(session)
            val deadline=SystemClock.elapsedRealtime()+30_000
            while(editing.state.value.busy && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(20)
            val preview=editing.state.value
            assertFalse(preview.message,preview.busy)
            assertNotNull(preview.file)
            assertEquals(preview.recipe.hash,preview.resultHash)
            val finished=CountDownLatch(1)
            var version: PhotoRepository.Version?=null
            instrumentation.runOnMainSync {editing.save {saved,_->version=saved;finished.countDown()}}
            assertTrue("Save did not finish",finished.await(30,TimeUnit.SECONDS))
            val record=JSONObject(checkNotNull(version).record.readText())
            assertEquals("final",record.getString("renderQuality"))
            assertEquals("jpeg",record.getJSONObject("input").getString("kind"))
            assertTrue(record.has("engineIdentity"));assertTrue(record.has("timing"))
            worker {
                assertEquals(PairCaptureStore.hash(file),repository.verifySource(photo))
                photo.source.appendBytes(byteArrayOf(0))
                assertTrue(runCatching {repository.verifySource(photo)}.exceptionOrNull() is IllegalArgumentException)
            }
        } finally {
            session?.let {editing->instrumentation.runOnMainSync {editing.close()}}
            worker {repository.scan().firstOrNull {it.id==photo.id}?.let(repository::deletePhoto)}
            file.delete()
        }
    }
    @Test fun importRenderSaveExportAndReload()=worker {
        val file=fixture();val repository=PhotoRepository(context);val input=EditInput()
        val output=File(context.cacheDir,"jpeg-test-render.jpg");val exported=File(context.cacheDir,"jpeg-test-export.jpg")
        var photo: PhotoRepository.Photo?=null
        try {
            val hash=PairCaptureStore.hash(file)
            val imported=repository.importPhoto(Uri.fromFile(file));photo=imported
            assertEquals("jpeg",imported.kind);assertEquals("image/jpeg",imported.mime)
            assertEquals(hash,PairCaptureStore.hash(imported.source))
            assertEquals(FilmRecipe(),repository.captureRecipe(imported))
            assertNotNull(imported.displayFile);assertNull(imported.latest)
            assertTrue(repository.thumbnail(imported.source).isFile)
            val timing=CaptureTiming()
            val prepared=input.prepare(imported.source,hash,timing,cancelled={false})
            val seed=hash.take(8).toLong(16)
            assertEquals(seed,FilmEngine.recordedSeed(prepared.record))
            assertNull(prepared.pixels);assertTrue(prepared.gpuImage!=0L)
            val (cpu,_)=BitmapCpuReference.prepare(imported.source,false,{false})
            try {
                val samples=cpu.pixels.asFloatBuffer()
                // sRGB code 128 decodes to approximately 0.216 linear; a neutral stays neutral in ProPhoto.
                repeat(3) {assertEquals(0.216f,samples.get(),0.01f)}
            } finally {cpu.close()}
            assertSame(prepared,input.prepare(imported.source,hash,timing,cancelled={false}))
            val assets=PreviewLutStore(context).prepareAssets()
            fun render(): java.nio.ByteBuffer {
                val rgba=FilmOutput.buffer(prepared.width,prepared.height)
                val result=JSONObject(FilmEngine.render(assets.path,"",prepared.width,prepared.height,FilmRecipe(),SystemClock.elapsedRealtimeNanos(),"",seed,rgba,prepared.pixels,prepared.gpuImage))
                assertFalse(result.getBoolean("cancelled"))
                assertEquals(0L,result.getJSONObject("gpuTransfers").getLong("imageUploadBytes"))
                return rgba
            }
            val rgba=render();val again=render()
            assertEquals(rgba,again)
            PhotoOutput.write(rgba,prepared.width,prepared.height,output,prepared.framing)
            val saved=repository.saveEdit(imported,output,JSONObject().put("recipe",FilmRecipe().json()).put("input",JSONObject().put("kind","jpeg")))
            assertEquals(saved.id,repository.scan().first {it.id==imported.id}.latest?.id)
            repository.exportOriginal(imported,Uri.fromFile(exported))
            assertEquals(hash,PairCaptureStore.hash(exported));assertEquals(hash,PairCaptureStore.hash(file))
            input.close()
            val full=input.prepare(imported.source,hash,timing,true,{false})
            assertEquals(128,full.width);assertEquals(96,full.height)
        } finally {input.close();FilmEngine.close();photo?.let(repository::deletePhoto);file.delete();output.delete();exported.delete()}
    }
    @Test fun contentSeedsAreStableAndExistingSeedsArePreserved()=worker {
        val first=fixture(Color.RED);val second=fixture(Color.BLUE)
        val repository=PhotoRepository(context);val photos=mutableListOf<PhotoRepository.Photo>();val input=EditInput()
        try {
            fun imported(file: File)=repository.importPhoto(Uri.fromFile(file)).also {photos+=it}
            fun seed(photo: PhotoRepository.Photo)=FilmEngine.recordedSeed(JSONObject(photo.record.readText()))
            val original=imported(first)
            val expected=PairCaptureStore.hash(first).take(8).toLong(16)
            assertEquals(expected,seed(original))
            assertEquals(expected,seed(imported(first)))
            assertNotEquals(expected,seed(imported(second)))
            // Simulate a previously imported photo with the old fixed seed.
            PhotoRepository.atomic(original.record,JSONObject(original.record.readText()).put("grainSeed",0))
            assertEquals(expected,seed(imported(first)))
            val existing=repository.scan().first {it.id==original.id}
            assertEquals(0L,seed(existing))
            val prepared=input.prepare(existing.source,PairCaptureStore.hash(existing.source),CaptureTiming(),false,{false})
            assertEquals(0L,FilmEngine.recordedSeed(prepared.record))
        } finally {input.close();photos.forEach(repository::deletePhoto);first.delete();second.delete()}
    }
    @Test fun allExifOrientationsAndMirrors() {
        val file=fixture()
        val bitmap=Bitmap.createBitmap(128,96,Bitmap.Config.ARGB_8888)
        for(y in 0 until 96) for(x in 0 until 128) bitmap.setPixel(x,y,when {y<48 && x<64->Color.RED;y<48->Color.GREEN;x<64->Color.BLUE;else->Color.WHITE})
        file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,100,it)};bitmap.recycle()
        val corners=listOf(Color.RED,Color.GREEN,Color.BLUE,Color.WHITE)
        val expected=listOf(listOf(0,1,2,3),listOf(1,0,3,2),listOf(3,2,1,0),listOf(2,3,0,1),listOf(0,2,1,3),listOf(2,0,3,1),listOf(3,1,2,0),listOf(1,3,0,2))
        try {for(orientation in 1..8) {
            ExifInterface(file.path).apply {setAttribute(ExifInterface.TAG_ORIENTATION,orientation.toString());saveAttributes()}
            val decoded=BitmapEditInput.decode(file)
            try {
                assertEquals(if(orientation>=5) 96 else 128,decoded.width)
                assertEquals(if(orientation>=5) 128 else 96,decoded.height)
                assertEquals(decoded.width to decoded.height,BitmapEditInput.dimensions(file))
                val positions=listOf(8 to 8,(decoded.width-9) to 8,8 to (decoded.height-9),(decoded.width-9) to (decoded.height-9))
                positions.forEachIndexed {i,(x,y)->
                    val actual=decoded.getPixel(x,y);val wanted=corners[expected[orientation-1][i]]
                    assertEquals(Color.red(wanted).toDouble(),Color.red(actual).toDouble(),8.0)
                    assertEquals(Color.green(wanted).toDouble(),Color.green(actual).toDouble(),8.0)
                    assertEquals(Color.blue(wanted).toDouble(),Color.blue(actual).toDouble(),8.0)
                }
            } finally {decoded.recycle()}
        }} finally {file.delete()}
    }
    @Test fun batchImportContinuesAfterCorruptPhoto()=worker {
        val repository=PhotoRepository(context)
        val first=fixture(Color.RED);val last=fixture(Color.BLUE)
        val corrupt=File(context.cacheDir,"jpeg-test-${UUID.randomUUID()}.jpg")
        corrupt.writeBytes(byteArrayOf(0xff.toByte(),0xd8.toByte(),0))
        val before=repository.scan().map {it.id}.toSet()
        val progress=mutableListOf<Pair<Int,Int>>()
        var result: PhotoRepository.ImportResult?=null
        try {
            val imported=repository.importPhotos(listOf(first,corrupt,last).map {Uri.fromFile(it)}) {current,total->progress+=current to total}
            result=imported
            assertEquals(2,imported.photos.size)
            assertEquals(listOf(2),imported.failures.map {it.index})
            assertEquals(0,imported.skipped)
            assertEquals(listOf(1 to 3,2 to 3,3 to 3),progress)
            assertEquals(listOf(first,last).map {PairCaptureStore.hash(it)},imported.photos.map {PairCaptureStore.hash(it.source)})
            assertEquals(before+imported.photos.map {it.id},repository.scan().map {it.id}.toSet())
            assertFalse(imported.photos.first().source.parentFile!!.listFiles().orEmpty().any {it.extension=="pending"})
        } finally {
            result?.photos?.forEach {repository.deletePhoto(it)}
            listOf(first,corrupt,last).forEach {it.delete()}
        }
    }
    @Test fun corruptJpegIsRejectedWithoutCatalogEntry() {
        val repository=PhotoRepository(context);val before=repository.scan().map {it.id}.toSet()
        val file=File(context.cacheDir,"jpeg-test-corrupt.jpg");file.writeBytes(byteArrayOf(0xff.toByte(),0xd8.toByte(),0))
        try {
            assertTrue(runCatching {repository.importPhoto(Uri.fromFile(file))}.isFailure)
            assertEquals(before,repository.scan().map {it.id}.toSet())
        } finally {file.delete()}
    }
    @Test fun primaryColorsAndWideGamut()=worker {
        val srgb=fixture(Color.RED)
        val p3=File(context.cacheDir,"jpeg-test-p3.jpg")
        val space=ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        val bitmap=Bitmap.createBitmap(128,96,Bitmap.Config.RGBA_F16,false,space)
        try {
            bitmap.eraseColor(Color.pack(1f,0f,0f,1f,space))
            p3.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,100,it))}
        } finally {bitmap.recycle()}
        try {
            val (red,_)=BitmapCpuReference.prepare(srgb,true,{false})
            try {
                val rgb=red.pixels.asFloatBuffer()
                // Independent sRGB -> Bradford-adapted D50 ProPhoto primary reference.
                assertEquals(0.5293f,rgb.get(),0.015f)
                assertEquals(0.0984f,rgb.get(),0.015f)
                assertEquals(0.0169f,rgb.get(),0.015f)
            } finally {red.close()}
            val (wide,_)=BitmapCpuReference.prepare(p3,true,{false})
            try {assertTrue("P3 red must retain its wider gamut",wide.pixels.asFloatBuffer().get()>0.60f)} finally {wide.close()}
            assertTrue(runCatching {BitmapCpuReference.prepare(srgb,true,{true})}.exceptionOrNull() is InterruptedException)
        } finally {srgb.delete();p3.delete()}
    }
    @Test fun previewIsReducedButSaveUsesOriginalDimensions()=worker {
        val file=File(context.cacheDir,"jpeg-test-large.jpg")
        val bitmap=Bitmap.createBitmap(2048,1536,Bitmap.Config.ARGB_8888)
        try {bitmap.eraseColor(Color.GRAY);file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)}} finally {bitmap.recycle()}
        try {
            val (preview,previewSize)=BitmapCpuReference.prepare(file,false,{false})
            try {assertTrue(previewSize.first.toLong()*previewSize.second<=3_000_000);assertTrue(previewSize.first<2048)} finally {preview.close()}
            val (full,fullSize)=BitmapCpuReference.prepare(file,true,{false})
            try {assertEquals(2048 to 1536,fullSize)} finally {full.close()}
        } finally {file.delete()}
    }
}
