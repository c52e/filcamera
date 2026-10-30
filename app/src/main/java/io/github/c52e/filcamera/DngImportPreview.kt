package io.github.c52e.filcamera

import android.graphics.Bitmap
import android.graphics.ColorSpace
import io.github.c52e.filcamera.rawvulkan.DngRawFile
import io.github.c52e.filcamera.rawvulkan.VulkanRawDeveloper
import java.io.File

/** Small, rebuildable library preview; the original remains scene-linear RAW for editing. */
internal object DngImportPreview {
    fun write(raw: DngRawFile.Decoded,file: File) {
        val frame=raw.frame
        val framing=CaptureFraming.dng(raw.record.getJSONArray("dngCrop"),frame.right-frame.left,frame.bottom-frame.top)
        VulkanRawDeveloper(262144).developProPhoto(frame,raw.rotation,framing.crop).use {image->
            val values=image.pixels.asFloatBuffer();val pixels=IntArray(image.width*image.height)
            val connector=ColorSpace.connect(BitmapEditInput.linearProPhoto,ColorSpace.get(ColorSpace.Named.SRGB))
            val rgb=FloatArray(3)
            for(i in pixels.indices) {
                for(c in 0..2) rgb[c]=values.get()
                connector.transform(rgb)
                fun channel(c: Int)=(rgb[c].coerceIn(0f,1f)*255+.5f).toInt()
                pixels[i]=(255 shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
            }
            val bitmap=Bitmap.createBitmap(pixels,image.width,image.height,Bitmap.Config.ARGB_8888)
            try {PhotoOutput.write(bitmap,file)} finally {bitmap.recycle()}
        }
    }
}
