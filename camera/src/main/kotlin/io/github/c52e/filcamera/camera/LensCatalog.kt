package io.github.c52e.filcamera.camera

import io.github.c52e.filcamera.imaging.RawLimits

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.CameraManager
import java.util.Locale
import kotlin.math.abs

/** Physical identity is independent of the Camera2 entry used to reach it. */
data class LensRoute(val openId: String, val lensId: String, val physicalId: String? = null) {
    val key get() = "$openId/${physicalId.orEmpty()}"
}
data class RawLens(val route: LensRoute, val equivalentMm: Double?, val optics: CameraOptics?)
data class FocalOption(val id: String, val lens: RawLens?, val targetMm: Double?, val equivalentMm: Double?) {
    val label: String get() = equivalentMm?.let { String.format(Locale.ROOT,"%.1f",it).removeSuffix(".0") } ?: tr(Texts.string.native_focal)
}

object FocalOptions {
    fun lenses(entries: List<RawLens>): List<RawLens> = entries.groupBy { it.route.lensId }.values.map { aliases ->
        aliases.sortedWith(compareBy<RawLens> { it.route.physicalId != null }.thenBy { it.route.key }).first()
    }
    fun create(entries: List<RawLens>): List<FocalOption> {
        val lenses=lenses(entries)
        val natives=lenses.map { FocalOption("native:${it.route.lensId}",it,null,it.equivalentMm) }
        val crops=listOf(35.0,50.0,85.0).filter { target -> lenses.none { it.equivalentMm?.let { n -> abs(n-target)<1.0 } == true } }
            .map { target -> FocalOption("crop:${target.toInt()}",lenses.filter { it.equivalentMm?.let { n -> n<=target } == true }
                .maxByOrNull { it.equivalentMm!! },target,target) }
        return (natives+crops).sortedWith(compareBy<FocalOption> { it.equivalentMm ?: Double.NEGATIVE_INFINITY }.thenBy { it.id })
    }
}

/** Only public IDs and explicitly advertised physical members. No vendor/hidden-ID probing. */
class LensCatalog(manager: CameraManager) {
    val rejected=linkedMapOf<String,String>()
    val entries: List<RawLens>
    val defaultLensId: String?
    init {
        val public=runCatching {manager.cameraIdList.toList()}.onFailure {rejected["camera-service"]=it.message ?: tr(Texts.string.camera_service_is_temporarily_unavailable)}.getOrDefault(emptyList())
        val characteristics=public.mapNotNull { id->runCatching {id to manager.getCameraCharacteristics(id)}.onFailure {rejected[id]=it.message ?: tr(Texts.string.camera_service_is_temporarily_unavailable)}.getOrNull() }.toMap()
        val members=characteristics.values.flatMap { it.physicalCameraIds }.toSet()
        val routes=mutableListOf<RawLens>()
        fun add(route: LensRoute) {
            runCatching {
                val c=manager.getCameraCharacteristics(route.lensId)
                require(c[C.LENS_FACING]==C.LENS_FACING_BACK) {tr(Texts.string.not_rear_facing)}
                require(c[C.REQUEST_AVAILABLE_CAPABILITIES]?.contains(C.REQUEST_AVAILABLE_CAPABILITIES_RAW)==true) {tr(Texts.string.no_raw_capability)}
                require(c[C.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT] in 0..3) {tr(Texts.string.not_bayer)}
                val pixels=requireNotNull(c[C.SENSOR_INFO_PIXEL_ARRAY_SIZE])
                val active=requireNotNull(c[C.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE])
                require(RawLimits.supports(active.width(),active.height())) {tr(Texts.string.effective_raw_exceeds_60_megapixels)}
                require(c[C.SENSOR_BLACK_LEVEL_PATTERN]!=null && c[C.SENSOR_INFO_WHITE_LEVEL]!=null && c[C.SENSOR_COLOR_TRANSFORM1]!=null) {tr(Texts.string.missing_raw_calibration)}
                val sizes=c[C.SCALER_STREAM_CONFIGURATION_MAP]?.getOutputSizes(ImageFormat.RAW_SENSOR).orEmpty()
                require(sizes.filter {it.width.toLong()*it.height<=32_000_000}.any { (it.width==pixels.width && it.height==pixels.height) ||
                    (it.width==active.width() && it.height==active.height() && active.left%2==0 && active.top%2==0) }) {tr(Texts.string.unsupported_raw_coordinates)}
                val optics=CameraOptics.read(c)
                routes+=RawLens(route,optics?.equivalentMm,optics)
            }.onFailure { rejected[route.key]=it.message ?: tr(Texts.string.unavailable) }
        }
        // A logical entry is an alias, not an extra physical lens. Enumerate its members explicitly.
        for(id in characteristics.keys) if(characteristics.getValue(id).physicalCameraIds.isEmpty() || id in members) add(LensRoute(id,id))
        for((id,c) in characteristics) for(member in c.physicalCameraIds.sorted()) add(LensRoute(id,member,member))
        // Preserve the old default when it is physical; otherwise identify its optical match among members.
        val old=characteristics.keys.firstOrNull { characteristics.getValue(it)[C.LENS_FACING]==C.LENS_FACING_BACK &&
            characteristics.getValue(it)[C.REQUEST_AVAILABLE_CAPABILITIES]?.contains(C.REQUEST_AVAILABLE_CAPABILITIES_RAW)==true }
        val oldOptics=old?.let { CameraOptics.read(characteristics.getValue(it)) }
        defaultLensId=routes.firstOrNull { it.route.lensId==old }?.route?.lensId ?: routes.filter { it.route.openId==old }
            .minByOrNull { abs((it.equivalentMm ?: Double.POSITIVE_INFINITY)-(oldOptics?.equivalentMm ?: 0.0)) }?.route?.lensId ?: routes.firstOrNull()?.route?.lensId
        entries=routes
    }
}
