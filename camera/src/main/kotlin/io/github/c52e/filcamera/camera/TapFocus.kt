package io.github.c52e.filcamera.camera

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

import kotlin.math.roundToInt

enum class FocusPhase(private val labelId: Int) {
    IDLE(0), SCANNING(Texts.string.focusing), FOCUSED(Texts.string.focused), FAILED(Texts.string.could_not_focus),
    TIMEOUT(Texts.string.focus_timed_out), METERING(Texts.string.spot_metering), ERROR(Texts.string.could_not_set_focus_please_retry), UNAVAILABLE(Texts.string.tap_focus_and_metering_are_unavailable_here);
    val label: String get() = if (labelId == 0) "" else tr(labelId)
}
data class TapFocusStatus(val id: Long=0,val phase: FocusPhase=FocusPhase.IDLE,
    val afState: Int?=null,val afMode: Int?=null,val afRegion: List<Int> = emptyList(),val aeRegion: List<Int> = emptyList(),
    val targetRegion: List<Int> = emptyList())

/** Pure geometry: Camera2 may center-crop a stream to its aspect ratio inside SCALER_CROP_REGION. */
object MeteringGeometry {
    data class Box(val left: Int,val top: Int,val right: Int,val bottom: Int)
    /** Invert the HAL's logical -> physical metering mapping from matching request/result rectangles.
     * No focal-length guess: only use an unclipped, nonempty physical result from this session.
     */
    data class Mapping(val request: Box,val result: Box) {
        fun inverse(region: Box,bounds: Box): Box? {
            val sx=(request.right-request.left).toDouble()/(result.right-result.left)
            val sy=(request.bottom-request.top).toDouble()/(result.bottom-result.top)
            if(!sx.isFinite() || !sy.isFinite() || sx<=0 || sy<=0) return null
            fun x(v: Int)=(request.left+(v-result.left)*sx).roundToInt()
            fun y(v: Int)=(request.top+(v-result.top)*sy).roundToInt()
            val left=x(region.left);val right=x(region.right);val top=y(region.top);val bottom=y(region.bottom)
            if((left.toLong()+right)/2 !in bounds.left.toLong() until bounds.right.toLong() ||
                (top.toLong()+bottom)/2 !in bounds.top.toLong() until bounds.bottom.toLong()) return null
            return Box(left.coerceIn(bounds.left,bounds.right-1),top.coerceIn(bounds.top,bounds.bottom-1),
                right.coerceIn(bounds.left+1,bounds.right),bottom.coerceIn(bounds.top+1,bounds.bottom))
        }
    }
    fun region(x: Float,y: Float,crop: Box,previewWidth: Int,previewHeight: Int,windowScale: Float=1f): Box {
        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
        require(crop.right>crop.left && crop.bottom>crop.top && previewWidth>0 && previewHeight>0)
        require(windowScale.isFinite() && windowScale>0f && windowScale<=1f)
        val w=(crop.right-crop.left).toFloat();val h=(crop.bottom-crop.top).toFloat()
        val ratio=previewWidth.toFloat()/previewHeight
        val visibleW=minOf(w,h*ratio);val visibleH=minOf(h,w/ratio)
        val cx=crop.left+(w-visibleW)*.5f+x*visibleW
        val cy=crop.top+(h-visibleH)*.5f+y*visibleH
        val rw=maxOf(1,(visibleW*.1f*windowScale).roundToInt());val rh=maxOf(1,(visibleH*.1f*windowScale).roundToInt())
        val left=(cx-rw*.5f).roundToInt().coerceIn(crop.left,crop.right-rw)
        val top=(cy-rh*.5f).roundToInt().coerceIn(crop.top,crop.bottom-rh)
        return Box(left,top,left+rw,top+rh)
    }
}
