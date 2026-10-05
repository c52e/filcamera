package io.github.c52e.filcamera

import android.graphics.Bitmap
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt

object FilmOutput {
    fun buffer(w: Int,h: Int): ByteBuffer {
        require(w in 1..16384 && h in 1..16384 && w.toLong()*h<=16_000_000)
        return ByteBuffer.allocateDirect(w*h*4).order(ByteOrder.nativeOrder())
    }
    fun bitmap(rgba: ByteBuffer,w: Int,h: Int): Bitmap {
        require(rgba.isDirect && rgba.capacity().toLong()==w.toLong()*h*4)
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        try { rgba.rewind();bitmap.copyPixelsFromBuffer(rgba);return bitmap }
        catch(e: Exception) {bitmap.recycle();throw e}
    }
    // Independent CPU reference for instrumentation; production uses packed GPU bytes.

    fun bitmap(file: File, w: Int, h: Int): Bitmap {
        require(w in 1..16384 && h in 1..16384 && w.toLong()*h <= 16_000_000 && file.length() == w.toLong()*h*12)
        val pixels = IntArray(w*h)
        val row = ByteArray(w*12); val buffer = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN)
        fun encode(v: Float): Int {
            require(v.isFinite()); val x = v.coerceIn(0f, 1f).toDouble()
            return ((if (x <= 0.0031308) 12.92*x else 1.055*x.pow(1.0/2.4)-0.055)*255).roundToInt().coerceIn(0,255)
        }
        java.io.DataInputStream(file.inputStream().buffered()).use { stream ->
            for (y in 0 until h) {
                stream.readFully(row); buffer.rewind()
                for (x in 0 until w) { val r=encode(buffer.float); val g=encode(buffer.float); val b=encode(buffer.float); pixels[y*w+x]=0xff000000.toInt() or (r shl 16) or (g shl 8) or b }
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}


