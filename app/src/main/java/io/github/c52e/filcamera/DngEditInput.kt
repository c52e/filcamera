package io.github.c52e.filcamera

import io.github.c52e.filcamera.imaging.RawLimits

import android.content.Context
import io.github.c52e.filcamera.rawvulkan.DngRawFile
import io.github.c52e.filcamera.rawvulkan.VulkanRawDeveloper
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

/** Immutable direct-memory input, owned by the serial FilmWorker editing session. */
class DngEditInput(@Suppress("UNUSED_PARAMETER") context: Context) {
    data class Prepared(val pixels: ByteBuffer?,val width: Int,val height: Int,val record: JSONObject,val framing: CaptureFraming,val owner: VulkanRawDeveloper.FilmInput)
    private var key=""
    private var ready: Prepared?=null
    fun prepare(source: File,sha256: String,timing: CaptureTiming,fullResolution: Boolean=false,cancelled: ()->Boolean): Prepared {
        fun checkActive() {if(cancelled()) throw InterruptedException()}
        checkActive()
        val cacheKey=sha256+"|full="+fullResolution
        ready?.let {if(key==cacheKey) {timing.mark("dngCacheHit");return it}}
        close()
        val raw=timing.stage("dngRead") {DngRawFile.read(source,cancelled)};checkActive()
        val framing=CaptureFraming.read(raw.record.optJSONObject("framing"),raw.frame.right-raw.frame.left,raw.frame.bottom-raw.frame.top)
        val converted=timing.stage("rawDevelop") {VulkanRawDeveloper(if(fullResolution) RawLimits.MAX_PIXELS else 3_000_000).developGpuProPhoto(raw.frame,raw.rotation,framing.crop)}
        try {checkActive()} catch(e: Exception) {converted.close();throw e}
        val result=Prepared(converted.cpuPixels,converted.width,converted.height,raw.record,framing,converted)
        ready=result;key=cacheKey;return result
    }
    fun close() {ready?.owner?.close();ready=null;key=""}
}
