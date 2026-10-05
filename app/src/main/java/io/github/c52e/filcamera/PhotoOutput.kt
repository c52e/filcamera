package io.github.c52e.filcamera

import android.graphics.Bitmap
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

/** All new photos use JPEG. Historical files are located through their saved manifest. */
object PhotoOutput {
    const val extension="jpg"
    const val mime="image/jpeg"
    const val productionEncoder="android-bitmap"
    data class Encoding(val encoder: String,val encodeMs: Double,val metadataPublishMs: Double)
    fun metadata(file: File,encoding: Encoding?=null)=JSONObject().put("file",file.name).put("format","JPG").put("mime",mime).put("quality",95).put("colorSpace","sRGB")
        .put("encoder",encoding?.encoder ?: "unspecified").put("sampling","4:2:0").apply {
            if(encoding!=null) {put("encodeMs",encoding.encodeMs);put("metadataPublishMs",encoding.metadataPublishMs)}
        }
    // The direct libjpeg-turbo candidate passed quality but regressed native-size
    // encoding on the qualification device. Select the qualified encoder here;
    // this is not an error-triggered fallback or a quality change.
    fun write(rgba: ByteBuffer,width: Int,height: Int,file: File,framing: CaptureFraming?=null): Encoding {
        val bitmap=FilmOutput.bitmap(rgba,width,height)
        return try {write(bitmap,file,framing)} finally {bitmap.recycle()}
    }
    fun write(bitmap: Bitmap,file: File,framing: CaptureFraming?=null)=
        publish(file,framing,productionEncoder) {pending->pending.outputStream().buffered().use {check(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it))}}
    private fun publish(file: File,framing: CaptureFraming?,encoder: String,encode: (File)->Unit): Encoding {
        val pending=File(file.parentFile,file.name+".pending")
        try {
            val start=android.os.SystemClock.elapsedRealtimeNanos()
            encode(pending)
            val encoded=android.os.SystemClock.elapsedRealtimeNanos()
            android.media.ExifInterface(pending.path).apply {
                    setAttribute(android.media.ExifInterface.TAG_ORIENTATION,"1")
                    setAttribute(android.media.ExifInterface.TAG_COLOR_SPACE,"1")
                    framing?.optics?.focalMm?.let {setAttribute(android.media.ExifInterface.TAG_FOCAL_LENGTH,"${kotlin.math.round(it*1_000_000).toLong()}/1000000")}
                    (framing?.targetMm ?: framing?.optics?.equivalentMm)?.let {setAttribute(android.media.ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,kotlin.math.round(it).toInt().toString())}
                    saveAttributes()
            }
            check(pending.renameTo(file)) { "Cannot publish photo" }
            return Encoding(encoder,(encoded-start)/1e6,(android.os.SystemClock.elapsedRealtimeNanos()-encoded)/1e6)
        } finally { pending.delete() }
    }
    fun result(dir: File)=file(dir,"final")
    fun file(dir: File,role: String): File? {
        return try {
            val report=JSONObject(File(dir,"report.json").readText())
            val explicit=if(role=="final") report.optJSONObject("output")?.optString("file") else null
            val name=explicit?.takeIf { it.isNotBlank() } ?: report.getJSONObject("files").keys().asSequence().firstOrNull { it.startsWith("$role.") && it.substringAfterLast('.')!="f32" } ?: return null
            if(File(name).name!=name) return null
            File(dir,name).takeIf { it.isFile }
        } catch(_: Exception) { null }
    }
}
