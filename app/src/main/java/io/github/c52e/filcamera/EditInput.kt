package io.github.c52e.filcamera

import io.github.c52e.filcamera.imaging.RawLimits

import io.github.c52e.filcamera.rawvulkan.DngRawFile
import io.github.c52e.filcamera.rawvulkan.VulkanRawDeveloper
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

/** Immutable direct-memory input, owned by the serial FilmWorker editing session. */
class EditInput {
    data class Prepared(val pixels: ByteBuffer?,val width: Int,val height: Int,val record: JSONObject,val framing: CaptureFraming,val owner: VulkanRawDeveloper.FilmInput) {
        val gpuImage get()=owner.gpuImage
    }
    private var key=""
    private var ready: Prepared?=null
    fun prepare(source: File,sha256: String,timing: CaptureTiming,fullResolution: Boolean=false,cancelled: ()->Boolean): Prepared {
        fun checkActive() {if(cancelled()) throw InterruptedException()}
        checkActive()
        val cacheKey=sha256+"|full="+fullResolution
        ready?.let {if(key==cacheKey) {timing.mark("inputCacheHit");return it}}
        close()
        if(source.extension.lowercase() in listOf("jpg","jpeg","heic","heif")) {
            val converted=timing.stage("bitmapDecode") {BitmapEditInput.prepareGpu(source,fullResolution,cancelled,timing)}
            try {
                checkActive()
                val record=JSONObject(File(source.parentFile,source.nameWithoutExtension+".json").readText())
                val result=Prepared(null,converted.width,converted.height,record,CaptureFraming.create(converted.width,converted.height,null,null),converted)
                ready=result;key=cacheKey;return result
            } catch(e: Throwable) {converted.close();throw e}
        }
        val raw=timing.stage("dngRead") {DngRawFile.read(source,cancelled)};checkActive()
        val record=if(raw.record.has("rawImport")) JSONObject(File(source.parentFile,source.nameWithoutExtension+".json").readText()) else raw.record
        if(raw.record.has("rawImport")) require(record.getJSONObject("rawImport").getString("rawImport")==raw.record.getString("rawImport")) {
            "Unsupported saved DNG development version"
        }
        val framing=if(raw.record.has("rawImport")) CaptureFraming.dng(raw.record.getJSONArray("dngCrop"),raw.frame.right-raw.frame.left,raw.frame.bottom-raw.frame.top)
            else CaptureFraming.read(raw.record.optJSONObject("framing"),raw.frame.right-raw.frame.left,raw.frame.bottom-raw.frame.top)
        val converted=timing.stage("rawDevelop") {VulkanRawDeveloper(if(fullResolution) RawLimits.MAX_PIXELS else 3_000_000).developGpuProPhoto(raw.frame,raw.rotation,framing.crop)}
        try {checkActive()} catch(e: Exception) {converted.close();throw e}
        val result=Prepared(converted.cpuPixels,converted.width,converted.height,record,framing,converted)
        ready=result;key=cacheKey;return result
    }
    fun close() {ready?.owner?.close();ready=null;key=""}
}
