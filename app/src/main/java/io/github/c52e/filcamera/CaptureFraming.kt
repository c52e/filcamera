package io.github.c52e.filcamera

import io.github.c52e.filcamera.imaging.RawLimits

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

import io.github.c52e.filcamera.camera.CameraOptics
import io.github.c52e.filcamera.rawvulkan.VulkanRawDeveloper
import org.json.JSONObject
import kotlin.math.floor

/** Coordinates are relative to the calibrated RAW active rectangle, before orientation. */
data class CaptureFraming(val width: Int,val height: Int,val left: Int,val top: Int,val cropWidth: Int,val cropHeight: Int,
    val targetMm: Double?,val optics: CameraOptics?,val lens: io.github.c52e.filcamera.camera.LensRoute?=null) {
    init { require(RawLimits.supports(width,height) && left>=0 && top>=0 && cropWidth>0 && cropHeight>0 && left.toLong()+cropWidth<=width && top.toLong()+cropHeight<=height) }
    val scale get()=cropWidth.toFloat()/width
    val crop get()=VulkanRawDeveloper.Crop(left,top,cropWidth,cropHeight)
    fun json(): JSONObject=JSONObject().put("version",1).put("width",width).put("height",height).put("left",left).put("top",top)
        .put("cropWidth",cropWidth).put("cropHeight",cropHeight).put("targetMm",targetMm ?: JSONObject.NULL)
        .put("nativeEquivalentMm",optics?.equivalentMm ?: JSONObject.NULL).put("spatialPolicy","fixed-frame-36mm-v1").put("frameLongEdgeMm",36)
        .put("lens",lens?.let {JSONObject().put("openId",it.openId).put("lensId",it.lensId).put("physicalId",it.physicalId ?: JSONObject.NULL)} ?: JSONObject.NULL)
        .put("optics",optics?.let {JSONObject().put("focalMm",it.focalMm ?: JSONObject.NULL).put("sensorWidthMm",it.sensorWidthMm).put("sensorHeightMm",it.sensorHeightMm)
            .put("pixelWidth",it.pixelWidth).put("pixelHeight",it.pixelHeight).put("left",it.left).put("top",it.top).put("width",it.width).put("height",it.height)} ?: JSONObject.NULL)
    companion object {
        fun create(w: Int,h: Int,target: Double?,optics: CameraOptics?): CaptureFraming {
            if(target==null) return CaptureFraming(w,h,0,0,w,h,null,optics)
            val native=requireNotNull(optics?.equivalentMm) { tr(Texts.string.cannot_calculate_native_equivalent_focal_length) };require(target.isFinite() && target>native)
            fun gcd(a: Int,b: Int): Int=if(b==0) a else gcd(b,a%b)
            val divisor=gcd(w,h);val unitW=w/divisor;val unitH=h/divisor
            val units=(floor(divisor*native/target).toInt()/2)*2;require(units>=2)
            val cw=unitW*units;val ch=unitH*units
            return CaptureFraming(w,h,(w-cw)/2,(h-ch)/2,cw,ch,target,optics)
        }
        fun read(j: JSONObject?,w: Int,h: Int): CaptureFraming {
            if(j==null) return create(w,h,null,null)
            require(j.getInt("version")==1 && j.getString("spatialPolicy")=="fixed-frame-36mm-v1" && j.getInt("frameLongEdgeMm")==36)
            val o=j.optJSONObject("optics")?.let {CameraOptics(if(it.isNull("focalMm")) null else it.getDouble("focalMm"),it.getDouble("sensorWidthMm"),it.getDouble("sensorHeightMm"),it.getInt("pixelWidth"),it.getInt("pixelHeight"),it.getInt("left"),it.getInt("top"),it.getInt("width"),it.getInt("height"))}
            require(j.getInt("width")==w && j.getInt("height")==h) { tr(Texts.string.crop_record_does_not_match_raw_dimensions) }
            val result=CaptureFraming(w,h,j.getInt("left"),j.getInt("top"),j.getInt("cropWidth"),j.getInt("cropHeight"),if(j.isNull("targetMm")) null else j.getDouble("targetMm"),o)
            require(result==create(w,h,result.targetMm,o)) { tr(Texts.string.invalid_crop_record) };return result.copy(lens=j.optJSONObject("lens")?.let {io.github.c52e.filcamera.camera.LensRoute(it.getString("openId"),it.getString("lensId"),if(it.isNull("physicalId")) null else it.getString("physicalId"))})
        }
    }
}
