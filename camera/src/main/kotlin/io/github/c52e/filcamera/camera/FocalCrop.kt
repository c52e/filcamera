package io.github.c52e.filcamera.camera

import kotlin.math.floor

/** Exact-aspect, Bayer-aligned dimensions shared by RAW framing and preview stream selection. */
object FocalCrop {
    fun dimensions(width: Int,height: Int,nativeMm: Double,targetMm: Double): Pair<Int,Int> {
        require(width>0 && height>0 && nativeMm.isFinite() && nativeMm>0 && targetMm.isFinite() && targetMm>nativeMm)
        fun gcd(a: Int,b: Int): Int=if(b==0) a else gcd(b,a%b)
        val divisor=gcd(width,height)
        val units=(floor(divisor*nativeMm/targetMm).toInt()/2)*2
        require(units>=2)
        return width/divisor*units to height/divisor*units
    }
}
