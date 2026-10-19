package io.github.c52e.filcamera

import io.github.c52e.filcamera.imaging.RawLimits

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CaptureResult as R
import android.hardware.camera2.DngCreator
import android.hardware.camera2.CaptureResult
import android.os.SystemClock
import io.github.c52e.filcamera.camera.CapturedFrame
import io.github.c52e.filcamera.camera.RawMetadata
import io.github.c52e.filcamera.rawvulkan.VulkanRawDeveloper
import io.github.c52e.filcamera.rawvulkan.DngRawFile
import io.github.c52e.filcamera.preview.PreviewSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** All methods run on the activity's single IO/native owner. A saved DNG survives later failures. */
class PairCaptureStore(private val context: Context) {
    private val lab=File(context.filesDir,"lab")
    fun preview(id: String, snapshot: PreviewSnapshot) {
        val dir=File(lab,"pairs/$id").apply { mkdirs() }
        savePixels(File(dir,"isp.jpg"),snapshot.width,snapshot.height,snapshot.isp)
        savePixels(File(dir,"preview-film.jpg"),snapshot.width,snapshot.height,snapshot.film)
    }
    fun finish(id: String, snapshot: PreviewSnapshot?, previewResult: CaptureResult?, frame: CapturedFrame,recipe: FilmRecipe=FilmRecipe(),timing: CaptureTiming=CaptureTiming(), previewMetadata: String="{}",framing: CaptureFraming?=null): File {
        val dir=File(lab,"pairs/$id").apply { mkdirs() }
        var ownedInput: VulkanRawDeveloper.FilmInput?=null
        val grainSeed=PreviewLut.digest(id.toByteArray(Charsets.UTF_8)).take(8).toLong(16)
        val report=JSONObject().put("schema",1).put("status","incomplete")
            .put("pairing",if(snapshot==null) "raw-only" else "preceding preview; not synchronized RAW frame")
            .put("previewTimestampNs",snapshot?.timestampNs ?: 0L).put("previewMetadataMatched",previewResult!=null)
            .put("preview",previewResult?.let(::metadata) ?: JSONObject.NULL)
            .put("raw",metadata(frame.result)).put("rawRotation",frame.rotation).put("whiteBalance",whiteBalance(frame))
            .put("displayDegrees",snapshot?.displayDegrees ?: 0)
            .put("deltaMs",((frame.result[R.SENSOR_TIMESTAMP] ?: 0)-(snapshot?.timestampNs ?: 0L))/1e6)
            .put("previewWidth",snapshot?.width ?: 0).put("previewHeight",snapshot?.height ?: 0)
            .put("previewAutoExposure","Camera2 ISP AE + optional GPU input metering")
            .put("previewEffect",snapshot?.let {JSONObject(it.effectMetadata)} ?: JSONObject(previewMetadata))
            .put("finalAutoExposure","Spektrafilm auto exposure on").put("recipe",recipe.json()).put("recipeHash",recipe.hash)
            .put("grainSeed",grainSeed).put("resolvedSpatial",recipe.resolvedSpatial())
            .put("engineIdentity",JSONObject(context.assets.open("preview/engine.json").bufferedReader().use { it.readText() }))
        val output=File(dir,"report.json")
        try {
            require(frame.image.format==ImageFormat.RAW_SENSOR) { tr(Texts.string.this_session_did_not_provide_raw_for_comparison) }
            frame.use {
                val rawResult=runCatching { timing.stage("rawMetadata") { RawMetadata.frame(frame) } }
                val description=rawResult.mapCatching { DngRawFile.description(it,frame.rotation,JSONObject()
                    .put("whiteBalance",whiteBalance(frame)).put("recipe",recipe.json()).put("grainSeed",grainSeed).put("captureId",id).put("framing",framing?.json() ?: JSONObject.NULL)
                    .put("rawEngineIdentity",JSONObject(context.assets.open("raw-vulkan/engine.json").bufferedReader().use { it.readText() }))) }
                report.put("rawStorage",DngRawFile.FORMAT)
                val dngFile=File(dir,"original.dng");val pendingDng=File(dir,"original.dng.pending")
                timing.stage("dngSave") { try { pendingDng.outputStream().buffered().use { stream ->
                    DngCreator(frame.characteristics,frame.result).use { dng ->
                        description.getOrNull()?.let { dng.setDescription(it) }
                        dng.setOrientation(when(frame.rotation) { 90->6;180->3;270->8;else->1 })
                        dng.writeImage(stream,frame.image)
                    }
                }
                    java.nio.file.Files.move(pendingDng.toPath(),dngFile.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                } finally {pendingDng.delete()} }
                description.getOrThrow()
                if(snapshot!=null) require(File(dir,"isp.jpg").isFile && File(dir,"preview-film.jpg").isFile) { tr(Texts.string.preview_images_were_not_saved_dng_retained) }
                val raw=rawResult.getOrThrow()
                report.put("black",JSONArray(raw.black.toList())).put("white",raw.white)
                    .put("gains",JSONArray(raw.gains.toList())).put("cameraToSrgb",JSONArray(raw.cameraToSrgb.toList()))
                    .put("rawDeveloper",VulkanRawDeveloper.ID)
                    .put("rawEngineIdentity",JSONObject(context.assets.open("raw-vulkan/engine.json").bufferedReader().use { it.readText() }))
                val selected=framing ?: CaptureFraming.create(raw.right-raw.left,raw.bottom-raw.top,null,null)
                require(selected.width==raw.right-raw.left && selected.height==raw.bottom-raw.top) { tr(Texts.string.raw_active_area_changed_dng_retained) }
                selected.optics?.focalMm?.let {expected->
                    val actual=frame.result[R.LENS_FOCAL_LENGTH]?.toDouble()
                    require(actual!=null && kotlin.math.abs(actual-expected)<.0001) { tr(Texts.string.capture_lens_focal_length_changed_dng_retained) }
                }
                require(selected.lens==null || selected.lens==frame.lens) {tr(Texts.string.raw_lens_does_not_match_shutter_record_dng_retained)}
                report.put("framing",selected.json())
                val developer=VulkanRawDeveloper(RawLimits.MAX_PIXELS)
                val linear=timing.stage("rawDevelop") { developer.developGpuProPhoto(raw,frame.rotation,selected.crop) }.also {ownedInput=it}
                report.put("rawDevelop",JSONObject(developer.diagnostics))
                if(verifyDngRoundTrip) {
                    val loaded=timing.stage("testDngRead") {DngRawFile.read(dngFile)}
                    val reread=timing.stage("testDngDevelop") {VulkanRawDeveloper(RawLimits.MAX_PIXELS).developProPhoto(loaded.frame,loaded.rotation,selected.crop)}
                    require(loaded.rotation==frame.rotation && loaded.record.getLong("grainSeed")==grainSeed)
                    require(loaded.record.getJSONObject("recipe").toString()==recipe.json().toString())
                    require(reread.width==linear.width && reread.height==linear.height)
                    var maximum=0.0;var squares=0.0
                    val reference=VulkanRawDeveloper(RawLimits.MAX_PIXELS).developProPhoto(raw,frame.rotation,selected.crop)
                    val original=reference.pixels.asFloatBuffer();val restored=reread.pixels.asFloatBuffer()
                    for(i in 0 until original.capacity()) {val d=kotlin.math.abs(original.get(i).toDouble()-restored.get(i));maximum=maxOf(maximum,d);squares+=d*d}
                    val rms=kotlin.math.sqrt(squares/original.capacity())
                    report.put("dngRoundTrip",JSONObject().put("maxError",maximum).put("rms",rms))
                    reread.close();reference.close()
                    require(maximum<=1e-5 && rms<=1e-6) {"DNG round trip mismatch"}
                }
                frame.close()
                val w=linear.width;val h=linear.height
                timing.stage("assetVerify") { verifyAssets() }
                val rgba=timing.stage("outputAllocate") { FilmOutput.buffer(w,h) }
                val details=timing.stage("nativeCall") { JSONObject(FilmEngine.render(File(lab,"assets").path,"",w,h,recipe,
                    SystemClock.elapsedRealtimeNanos(),"",grainSeed,rgba,linear.cpuPixels,linear.gpuImage)) }
                check(!details.getBoolean("cancelled"))
                val encoding=timing.stage("photoEncode") { PhotoOutput.write(rgba,w,h,File(dir,"final.jpg"),selected) }
                report.put("output",PhotoOutput.metadata(File(dir,"final.jpg"),encoding))
                report.put("render",details).put("status","complete").put("width",w).put("height",h)
            }
        } catch(e: OutOfMemoryError) {report.put("status","failed").put("error",tr(Texts.string.out_of_memory));throw IllegalStateException(tr(Texts.string.out_of_memory_saved_dng_retained),e)
        } catch(e: Exception) { report.put("status","failed").put("error",e.toString());throw e }
        finally {
            ownedInput?.close()
            frame.close()
            try { timing.stage("fileVerify") { report.put("files",JSONObject().also { hashes -> dir.listFiles()?.filter { it.isFile && it.name!="report.json" }?.forEach { hashes.put(it.name,hash(it)) } }) } }
            finally { timing.stage("nativeRelease") { FilmEngine.close() } }
            timing.mark("processingComplete")
            report.put("timing",timing.json())
            output.writeText(report.toString(2))
        }
        return dir
    }
    private fun verifyAssets() {
        val manifest=JSONObject(context.assets.open("lab/manifest.json").bufferedReader().use { it.readText() })
        val entries=manifest.getJSONObject("assets")
        for(key in entries.keys()) {
            val file=File(lab,"assets/$key")
            require(file.canonicalPath.startsWith(File(lab,"assets").canonicalPath+File.separator))
            if(!file.exists()) {
                file.parentFile!!.mkdirs()
                val pending=File(file.parentFile,file.name+".pair-pending")
                try {
                    context.assets.open("lab/assets/$key").use { source -> pending.outputStream().use { source.copyTo(it) } }
                    require(hash(pending)==entries.getString(key));check(pending.renameTo(file))
                } finally { pending.delete() }
            }
            require(hash(file)==entries.getString(key)) { "Asset checksum mismatch: $key" }
        }
    }
    companion object {
        @Volatile var verifyDngRoundTrip=false // Instrumentation only; no extra develop in production.
        fun whiteBalance(frame: CapturedFrame)=JSONObject().put("version",3).put("mode",when(frame.whiteBalance.source) {
                io.github.c52e.filcamera.camera.WhiteBalanceSource.AUTO->"auto"
                io.github.c52e.filcamera.camera.WhiteBalanceSource.NATIVE_CCT->"native-cct"
                io.github.c52e.filcamera.camera.WhiteBalanceSource.CALIBRATED->"calibrated-kelvin"
            })
            .put("calculationVersion",io.github.c52e.filcamera.imaging.CalibratedWhiteBalance.VERSION)
            .put("calibrationHash",frame.whiteBalance.calibrationHash ?: JSONObject.NULL)
            .put("requestedGains",JSONArray(frame.whiteBalance.gains))
            .put("requestedTransform",JSONArray(frame.whiteBalance.transform))
            .put("requestedKelvin",frame.whiteBalance.kelvin ?: JSONObject.NULL)
            .put("actual",metadata(frame.result))
        fun metadata(result: CaptureResult)=JSONObject()
            .put("tonemapMode",result[R.TONEMAP_MODE])
            .put("tonemapGamma",result[R.TONEMAP_GAMMA])
            .put("tonemapPreset",result[R.TONEMAP_PRESET_CURVE])
            .put("tonemapCurve",result[R.TONEMAP_CURVE]?.let {curve->JSONArray(List(3) {channel->
                val values=FloatArray(curve.getPointCount(channel)*2);curve.copyColorCurve(channel,values,0)
                JSONArray(values.toList())
            })})
            .put("aperture",result[R.LENS_APERTURE])
            .put("colorTemperatureKelvin",io.github.c52e.filcamera.camera.NativeColorTemperature.actual(result) ?: JSONObject.NULL)
            .put("sensorTimestampNs",result[R.SENSOR_TIMESTAMP]).put("iso",result[R.SENSOR_SENSITIVITY])
            .put("exposureNs",result[R.SENSOR_EXPOSURE_TIME]).put("aeState",result[R.CONTROL_AE_STATE])
            .put("afMode",result[R.CONTROL_AF_MODE]).put("afState",result[R.CONTROL_AF_STATE])
            .put("focusDistanceDiopters",result[R.LENS_FOCUS_DISTANCE])
            .put("afRegions",result[R.CONTROL_AF_REGIONS]?.let {JSONArray(it.map {r->listOf(r.x,r.y,r.width,r.height,r.meteringWeight)})})
            .put("aeRegions",result[R.CONTROL_AE_REGIONS]?.let {JSONArray(it.map {r->listOf(r.x,r.y,r.width,r.height,r.meteringWeight)})})
            .put("sensorNeutral",result[R.SENSOR_NEUTRAL_COLOR_POINT]?.let {JSONArray(it.map {v->v.toDouble()})})
            .put("awbMode",result[R.CONTROL_AWB_MODE]).put("colorCorrectionMode",result[R.COLOR_CORRECTION_MODE])
            .put("transform",result[R.COLOR_CORRECTION_TRANSFORM]?.let {t->JSONArray(List(9) {t.getElement(it%3,it/3).toDouble()})})
            .put("awbState",result[R.CONTROL_AWB_STATE]).put("aeCompensation",result[R.CONTROL_AE_EXPOSURE_COMPENSATION])
            .put("postRawSensitivityBoost",result[R.CONTROL_POST_RAW_SENSITIVITY_BOOST])
            .put("gains",result[R.COLOR_CORRECTION_GAINS]?.let { JSONArray(listOf(it.red,it.greenEven,it.greenOdd,it.blue)) })
        fun hash(file: File): String {
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { stream -> val buffer=ByteArray(65536)
                while(true) { val n=stream.read(buffer);if(n<0) break;digest.update(buffer,0,n) } }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        private fun savePixels(file: File,w: Int,h: Int,pixels: IntArray) {
            val bitmap=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
            try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)) } }
            finally { bitmap.recycle() }
        }
    }
}
