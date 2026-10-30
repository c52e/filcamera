package io.github.c52e.filcamera

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.github.c52e.filcamera.imaging.EdgeDirectedRawDeveloper
import io.github.c52e.filcamera.rawvulkan.DngRawFile
import io.github.c52e.filcamera.rawvulkan.VulkanRawDeveloper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class StandardDngImportTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun <T> worker(body: ()->T): T {
        val task=FutureTask(Callable(body));FilmWorker.execute {task.run()};return task.get(180,TimeUnit.SECONDS)
    }
    @Test fun unpackTwelveBitAndBigEndianSixteenBitWithoutPrivateMetadata() {
        for((bits,order) in listOf(12 to ByteOrder.LITTLE_ENDIAN,16 to ByteOrder.BIG_ENDIAN)) {
            val file=File(app.cacheDir,"synthetic-standard.dng")
            try {
                file.writeBytes(synthetic(bits,order))
                val decoded=DngRawFile.read(file)
                assertEquals("standard-bayer-dng-v1",decoded.record.getString("rawImport"))
                assertEquals(90,decoded.rotation)
                assertEquals("[1,1,6,4]",decoded.record.getJSONArray("dngCrop").toString())
                val pixels=decoded.frame.bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                for(i in 0 until 48) assertEquals(100+i,pixels.getShort(i*2).toInt() and 65535)
            } finally {file.delete()}
        }
    }
    @Test fun requiredUnknownOpcodeAndTruncatedPixelsAreRejected() {
        val file=File(app.cacheDir,"invalid-standard.dng")
        try {
            for(bytes in listOf(synthetic(16,ByteOrder.LITTLE_ENDIAN,requiredOpcode=true),synthetic(16,ByteOrder.LITTLE_ENDIAN).dropLast(20).toByteArray())) {
                file.writeBytes(bytes)
                try {DngRawFile.read(file);fail("Invalid/unsupported DNG must not be imported")}
                catch(expected: IllegalArgumentException) {assertNotNull(expected.message)}
            }
        } finally {file.delete()}
    }
    @Test fun optionalUnsupportedGainMapsAreSkippedButRequiredAndMalformedMapsFail() {
        val file=File(app.cacheDir,"gainmap-standard.dng")
        fun read(opcodes: ByteArray): DngRawFile.Decoded {
            file.writeBytes(synthetic(16,ByteOrder.LITTLE_ENDIAN,opcodeList=opcodes))
            return DngRawFile.read(file)
        }
        fun rejected(opcodes: ByteArray) {
            try {read(opcodes);fail("Required or malformed GainMaps must fail")}
            catch(expected: IllegalArgumentException) {assertNotNull(expected.message)}
        }
        try {
            // Legal layouts that this developer cannot apply: stride one, incomplete CFA, differing grids.
            val unsupported=listOf(gainMaps(listOf(0),pitch=1),gainMaps(listOf(0,1,2)),gainMaps(listOf(0,1,2,3),differentGrid=true))
            for(opcodes in unsupported) {
                val decoded=read(opcodes)
                assertNull(decoded.frame.shading)
                assertTrue(decoded.record.getJSONArray("skippedOptionalOpcodes").length()>0)
            }
            rejected(gainMaps(listOf(0),pitch=1,optional=false))
            rejected(gainMaps(listOf(0,1,2),optional=false))
            rejected(gainMaps(listOf(0,1,2,3),optional=false,differentGrid=true))
            rejected(gainMaps(listOf(0),gain=Float.NaN))
            rejected(gainMaps(listOf(0)).dropLast(1).toByteArray())
            val supported=read(gainMaps(listOf(0,1,2,3)))
            assertNotNull(supported.frame.shading)
            assertEquals(2f,supported.frame.shading!!.gain(0,.5f,.5f),0f)
        } finally {file.delete()}
    }

    private fun gainMaps(sites: List<Int>,pitch: Int=2,optional: Boolean=true,differentGrid: Boolean=false,gain: Float=2f): ByteArray {
        val b=ByteBuffer.allocate(4+sites.size*96).order(ByteOrder.BIG_ENDIAN).putInt(sites.size)
        sites.forEach {site->
            b.putInt(9).putInt(0x01030000).putInt(if(optional) 1 else 0).putInt(80)
            for(value in listOf(site/2,site%2,6,8,0,1,pitch,pitch,1,1)) b.putInt(value)
            b.putDouble(if(differentGrid && site==3) .5 else 1.0).putDouble(1.0)
                .putDouble(0.0).putDouble(0.0).putInt(1).putFloat(gain)
        }
        return b.array()
    }
    @Test fun mappedGainGridMatchesCpuReferenceOnGpu()=worker {
        val file=File(app.cacheDir,"shading-standard.dng")
        try {
            file.writeBytes(synthetic(16,ByteOrder.LITTLE_ENDIAN))
            val decoded=DngRawFile.read(file)
            val gains=FloatArray(3*2*4) {i->.5f+(i%7)*.2f}
            val grid=io.github.c52e.filcamera.imaging.ShadingGrid(3,2,gains,.8f,.7f,.15f,-.1f)
            val frame=decoded.frame.copy(shading=grid)
            val cpu=EdgeDirectedRawDeveloper(48).develop(frame)
            val gpu=VulkanRawDeveloper(48).develop(frame)
            assertEquals(cpu.width,gpu.width);assertEquals(cpu.height,gpu.height)
            val maximum=cpu.rgb.zip(gpu.rgb).maxOf {(a,b)->abs(a-b)}
            assertTrue("Mapped gain interpolation GPU error $maximum",maximum<1e-5f)
        } finally {file.delete();FilmEngine.close()}
    }
    /** Optional standard Bayer fixture: standard-input.dng in target cache. */
    @Test fun realStandardDngImportEditReopenAndExport() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("standardDngImport")=="true")
        worker {
            val fixture=File(app.cacheDir,"standard-input.dng");assertTrue(fixture.isFile)
            val originalHash=PairCaptureStore.hash(fixture)
            val repository=PhotoRepository(app);val input=EditInput()
            val photo=repository.importPhoto(Uri.fromFile(fixture))
            val output=File(app.cacheDir,"standard-render-test.jpg")
            val exported=File(app.cacheDir,"standard-original-test.dng")
            try {
                val recipe=repository.captureRecipe(photo)
                assertEquals(FilmRecipe(),recipe);assertNotNull(photo.displayFile)
                val assets=PreviewLutStore(app).prepareAssets()
                fun render(full: Boolean): Triple<JSONObject,ByteBuffer,Pair<Int,Int>> {
                    val prepared=input.prepare(photo.source,originalHash,CaptureTiming(),full,{false})
                    assertTrue(prepared.record.has("rawImport"))
                    val pixels=FilmOutput.buffer(prepared.width,prepared.height)
                    val seed=checkNotNull(FilmEngine.recordedSeed(prepared.record))
                    assertEquals(originalHash.take(8).toLong(16),seed)
                    val result=JSONObject(FilmEngine.render(assets.path,"",prepared.width,prepared.height,recipe,
                        SystemClock.elapsedRealtimeNanos(),"",seed,pixels,prepared.pixels,prepared.gpuImage))
                    assertFalse(result.getBoolean("cancelled"))
                    assertEquals(0L,result.getJSONObject("gpuTransfers").getLong("intermediateReadbackBytes"))
                    PhotoOutput.write(pixels,prepared.width,prepared.height,output,prepared.framing)
                    return Triple(result,pixels,prepared.width to prepared.height)
                }
                val (_,preview,_)=render(false)
                fun digest(buffer: ByteBuffer)=java.security.MessageDigest.getInstance("SHA-256").apply {update(buffer.duplicate().apply {clear()})}.digest()
                val hash=digest(preview)
                input.close()
                val (_,reopened,_)=render(false)
                assertArrayEquals(hash,digest(reopened))
                input.close()
                val (finalResult,_,expectedSize)=render(true)
                val dimensions=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeFile(output.path,dimensions)
                assertEquals(expectedSize.first,dimensions.outWidth);assertEquals(expectedSize.second,dimensions.outHeight)
                repository.saveEdit(photo,output,finalResult)
                assertNotNull(repository.scan().first {it.id==photo.id}.latest)
                repository.exportOriginal(photo,Uri.fromFile(exported));assertEquals(originalHash,PairCaptureStore.hash(exported))
            } finally {
                input.close();FilmEngine.close();assertEquals(0L,FilmEngine.liveGpuImages())
                repository.deletePhoto(photo);assertFalse(File(photo.source.parentFile,photo.source.nameWithoutExtension+".preview.jpg").exists())
                output.delete();exported.delete();assertEquals(originalHash,PairCaptureStore.hash(fixture))
            }
        }
    }

    private fun synthetic(bits: Int,order: ByteOrder,requiredOpcode: Boolean=false,opcodeList: ByteArray?=null): ByteArray {
        data class Entry(val id: Int,val type: Int,val count: Int,val data: ByteArray)
        val tags=ArrayList<Entry>()
        fun shorts(id: Int,vararg values: Int) {tags+=Entry(id,3,values.size,ByteBuffer.allocate(values.size*2).order(order).apply {values.forEach {putShort(it.toShort())}}.array())}
        fun longs(id: Int,vararg values: Int) {tags+=Entry(id,4,values.size,ByteBuffer.allocate(values.size*4).order(order).apply {values.forEach(::putInt)}.array())}
        fun rationals(id: Int,vararg values: Int) {tags+=Entry(id,5,values.size,ByteBuffer.allocate(values.size*8).order(order).apply {values.forEach {putInt(it);putInt(1)}}.array())}
        longs(256,8);longs(257,6);shorts(258,bits);shorts(259,1);shorts(262,32803);shorts(274,6);shorts(277,1)
        longs(273,0);longs(278,6);longs(279,8*6*bits/8);shorts(33421,2,2)
        tags+=Entry(33422,1,4,byteArrayOf(0,1,1,2));tags+=Entry(50706,1,4,byteArrayOf(1,4,0,0))
        rationals(50721,1,0,0,0,1,0,0,0,1);rationals(50728,1,1,1);longs(50717,(1 shl bits)-1)
        longs(50719,1,1);longs(50720,6,4)
        if(requiredOpcode) tags+=Entry(51009,7,20,ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN).putInt(1).putInt(999).putInt(0x01030000).putInt(0).putInt(0).array())
        opcodeList?.let {tags+=Entry(51009,7,it.size,it)}
        tags.sortBy {it.id};var tail=8+2+tags.size*12+4
        val buffer=ByteBuffer.allocate(4096).order(order)
        buffer.putShort(if(order==ByteOrder.LITTLE_ENDIAN) 0x4949.toShort() else 0x4d4d.toShort()).putShort(42).putInt(8).putShort(tags.size.toShort())
        var stripEntry=0
        tags.forEach {entry->
            buffer.putShort(entry.id.toShort()).putShort(entry.type.toShort()).putInt(entry.count)
            if(entry.id==273) stripEntry=buffer.position()
            if(entry.data.size<=4) {buffer.put(entry.data);repeat(4-entry.data.size) {buffer.put(0)}}
            else {buffer.putInt(tail);val saved=buffer.position();buffer.position(tail);buffer.put(entry.data);tail=buffer.position();buffer.position(saved)}
        }
        buffer.putInt(0);buffer.putInt(stripEntry,tail);buffer.position(tail)
        if(bits==16) repeat(48) {buffer.putShort((100+it).toShort())}
        else {
            for(i in 0 until 48 step 2) {
                val a=100+i;val b=101+i
                buffer.put((a ushr 4).toByte()).put(((a shl 4) or (b ushr 8)).toByte()).put(b.toByte())
            }
        }
        return buffer.array().copyOf(buffer.position())
    }
}
