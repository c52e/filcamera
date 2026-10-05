package io.github.c52e.filcamera

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

import android.os.SystemClock
import org.json.JSONObject

/** Monotonic elapsed times. Native engine timings are nested within nativeCall, not additive. */
class CaptureTiming(private val progress: (String)->Unit = {}) {
    private val start=SystemClock.elapsedRealtimeNanos()
    private val marks=linkedMapOf("shutter" to 0.0)
    private val stages=linkedMapOf<String,Double>()
    private val stageStarts=linkedMapOf<String,Double>()
    @Synchronized fun mark(name: String) { marks[name]=(SystemClock.elapsedRealtimeNanos()-start)/1e6 }
    fun <T> stage(name: String,body: ()->T): T {
        progress(name)
        val begin=SystemClock.elapsedRealtimeNanos()
        try { return body() } finally { synchronized(this) { stageStarts[name]=(begin-start)/1e6;stages[name]=(SystemClock.elapsedRealtimeNanos()-begin)/1e6 } }
    }
    @Synchronized fun json(): JSONObject {
        val queues=JSONObject()
        marks["previewWorkerStarted"]?.let { queues.put("preview",it-(marks["previewSnapshot"] ?: it)) }
        marks["finishWorkerStarted"]?.let { queues.put("rawFinish",it-(marks["rawArrived"] ?: it)) }
        return JSONObject().put("clock","elapsedRealtimeNanos").put("startNs",start).put("marksMs",JSONObject(marks as Map<*,*>))
            .put("stageStartsMs",JSONObject(stageStarts as Map<*,*>))
            .put("stagesMs",JSONObject(stages as Map<*,*>)).put("queueMs",queues)
            .put("totalMs",(SystemClock.elapsedRealtimeNanos()-start)/1e6)
    }
    companion object {
        fun label(stage: String)=when(stage) {
            "previewSave"->tr(Texts.string.save_preview_comparison);"dngSave"->tr(Texts.string.save_dng);"rawMetadata"->tr(Texts.string.read_raw_calibration)
            "rawDevelop"->tr(Texts.string.develop_raw);"dngRead"->tr(Texts.string.read_and_decompress_dng)
            "assetVerify"->tr(Texts.string.verify_engine_assets);"nativeCall"->tr(Texts.string.render_film_on_gpu);"bitmapEncode"->tr(Texts.string.create_output_bitmap)
            "photoEncode"->tr(Texts.string.compress_photo);"fileVerify"->tr(Texts.string.verify_photo_file);"nativeRelease"->tr(Texts.string.release_gpu_resources)
            else->stage
        }
    }
}
