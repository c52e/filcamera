package io.github.c52e.filcamera.camera

import io.github.c52e.filcamera.imaging.RawLimits

/** Call after rejecting modes whose sensor coordinates/calibration are incompatible. */
internal fun <T> smallestRawOutput(sizes: List<T>, dimensions: (T) -> Pair<Int,Int>): T? =
    sizes.filter {size->dimensions(size).let {(w,h)->RawLimits.supports(w,h)}}
        .minByOrNull {size->dimensions(size).let {(w,h)->w.toLong()*h}}
