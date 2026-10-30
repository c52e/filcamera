package io.github.c52e.filcamera

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import io.github.c52e.filcamera.imaging.RawLimits
import io.github.c52e.filcamera.strings.R as Texts
import io.github.c52e.filcamera.strings.tr
import java.io.File
import kotlin.math.sqrt

/** ImageDecoder applies EXIF orientation and embedded color profiles before conversion. */
internal object BitmapEditInput {
    private val proPhoto=ColorSpace.get(ColorSpace.Named.PRO_PHOTO_RGB) as ColorSpace.Rgb
    internal val linearProPhoto=ColorSpace.Rgb("Linear ProPhoto RGB",proPhoto.primaries,proPhoto.whitePoint,1.0)

    fun decode(file: File,maxPixels: Int=RawLimits.MAX_PIXELS,maxEdge: Int=RawLimits.MAX_DIMENSION,supportedOnly: Boolean=true,wideColor: Boolean=false,onSize: ((Int,Int)->Unit)?=null): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) {decoder,info,_->
            require((!supportedOnly || info.mimeType in setOf("image/jpeg","image/heic","image/heif")) && RawLimits.supports(info.size.width,info.size.height)) {tr(Texts.string.invalid_bitmap_image)}
            onSize?.invoke(info.size.width,info.size.height)
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            // A float bitmap retains wide gamut colors through the conversion to ProPhoto.
            decoder.setTargetColorSpace(ColorSpace.get(if(wideColor) ColorSpace.Named.EXTENDED_SRGB else ColorSpace.Named.SRGB))
            val scale=minOf(1.0,sqrt(maxPixels.toDouble()/(info.size.width.toLong()*info.size.height)),maxEdge.toDouble()/maxOf(info.size.width,info.size.height))
            decoder.setTargetSize((info.size.width*scale).toInt().coerceAtLeast(1),(info.size.height*scale).toInt().coerceAtLeast(1))
        }

    fun dimensions(file: File): Pair<Int,Int> {
        var size=1 to 1
        decode(file,maxEdge=1,supportedOnly=false,onSize={w,h->size=w to h}).recycle()
        return size
    }

    internal val srgbToProPhoto: FloatArray by lazy {
        val connector=ColorSpace.connect(ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB),linearProPhoto)
        val r=connector.transform(1f,0f,0f);val g=connector.transform(0f,1f,0f);val b=connector.transform(0f,0f,1f)
        floatArrayOf(r[0],g[0],b[0],r[1],g[1],b[1],r[2],g[2],b[2])
    }
    fun prepareGpu(file: File,fullResolution: Boolean,cancelled: ()->Boolean,timing: CaptureTiming): io.github.c52e.filcamera.rawvulkan.VulkanRawDeveloper.FilmInput {
        if(cancelled()) throw InterruptedException()
        val bitmap=timing.stage("bitmapImageDecode") {decode(file,if(fullResolution) RawLimits.MAX_PIXELS else 3_000_000,wideColor=true)}
        try {
            if(cancelled()) throw InterruptedException()
            val converted=timing.stage("bitmapGpuConvert") {
                io.github.c52e.filcamera.rawvulkan.VulkanRawDeveloper.convertSrgbBitmap(bitmap,srgbToProPhoto)
            }
            try {if(cancelled()) throw InterruptedException();return converted} catch(e: Throwable) {converted.close();throw e}
        } finally {bitmap.recycle()}
    }
}
