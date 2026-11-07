package io.github.c52e.filcamera

import io.github.c52e.filcamera.imaging.RawLimits

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

object FilmOutput {
    fun buffer(w: Int,h: Int): ByteBuffer {
        require(RawLimits.supports(w,h))
        return ByteBuffer.allocateDirect(w*h*4).order(ByteOrder.nativeOrder())
    }
    fun bitmap(rgba: ByteBuffer,w: Int,h: Int): Bitmap {
        require(rgba.isDirect && rgba.capacity().toLong()==w.toLong()*h*4)
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        try { rgba.rewind();bitmap.copyPixelsFromBuffer(rgba);return bitmap }
        catch(e: Exception) {bitmap.recycle();throw e}
    }
}


