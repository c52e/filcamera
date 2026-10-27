package io.github.c52e.filcamera

import android.graphics.ColorSpace
import io.github.c52e.filcamera.rawvulkan.NativeBuffer
import io.github.c52e.filcamera.imaging.RawLimits
import java.io.File

/** Original CPU conversion retained only as an instrumentation oracle. */
internal object BitmapCpuReference {
    fun prepare(file: File,fullResolution: Boolean,cancelled: ()->Boolean,timing: CaptureTiming?=null): Pair<NativeBuffer,Pair<Int,Int>> {
        fun <T> measured(name: String,body: ()->T): T = if(timing==null) body() else timing.stage(name,body)
        val bitmap=measured("jpegImageDecode") {BitmapEditInput.decode(file,if(fullResolution) RawLimits.MAX_PIXELS else 3_000_000,wideColor=true)}
        try {
            val connector=ColorSpace.connect(checkNotNull(bitmap.colorSpace),BitmapEditInput.linearProPhoto)
            val components=FloatArray(3)
            val storage=measured("jpegColorConvert") {NativeBuffer.allocate(bitmap.width*bitmap.height*12) {buffer->
                val floats=buffer.asFloatBuffer()
                for(y in 0 until bitmap.height) {
                    if(cancelled()) throw InterruptedException()
                    for(x in 0 until bitmap.width) {
                        val color=bitmap.getColor(x,y)
                        components[0]=color.red();components[1]=color.green();components[2]=color.blue()
                        connector.transform(components)
                        floats.put(components)
                    }
                }
            }}
            return storage to (bitmap.width to bitmap.height)
        } finally {bitmap.recycle()}
    }
}
