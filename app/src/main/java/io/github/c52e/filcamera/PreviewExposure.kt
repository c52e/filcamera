package io.github.c52e.filcamera

import kotlin.math.exp

/** Frame-time based scalar smoothing; no CPU pixel processing. */
class PreviewExposure {
    var value=0f; private set
    private var previous=0L
    fun update(target: Float,enabled: Boolean,timestampNs: Long): Float {
        require(target.isFinite())
        val dt=if(previous==0L) 0.0 else ((timestampNs-previous)/1e9).coerceIn(0.0,.25)
        value=if(enabled) value+((target.coerceIn(-4f,2f)-value)*(1-exp(-dt/.35))).toFloat() else 0f
        previous=timestampNs
        return value
    }
}
