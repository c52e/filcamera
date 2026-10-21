package io.github.c52e.filcamera

import io.github.c52e.filcamera.camera.FocalOption
import io.github.c52e.filcamera.camera.FocalOptions
import io.github.c52e.filcamera.camera.RawLens

/** Automatic fallback cannot revisit a failed route until a new user attempt. */
internal class CameraRouteFailures {
    private val failedRoutes=mutableSetOf<String>()

    fun fallback(failed: FocalOption,confirmed: FocalOption?,entries: List<RawLens>): FocalOption? {
        val route=failed.lens?.route ?: return null
        failedRoutes+=route.key
        val available=entries.filter {it.route.key !in failedRoutes}
        val alias=FocalOptions.lenses(available.filter {it.route.lensId==route.lensId}).firstOrNull()
        return alias?.let {failed.copy(lens=it)} ?: confirmed?.takeIf {previous->
            available.any {it.route==previous.lens?.route}
        }
    }
    fun resetAttempts() {failedRoutes.clear()}
}
