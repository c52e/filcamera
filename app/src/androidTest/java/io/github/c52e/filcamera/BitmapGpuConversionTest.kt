package io.github.c52e.filcamera

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.util.Half
import androidx.test.platform.app.InstrumentationRegistry
import io.github.c52e.filcamera.rawvulkan.VulkanRawDeveloper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class BitmapGpuConversionTest {
    @Test fun rgba8AndExtendedHalfMatchColorSpaceOracle() {
        val task=FutureTask(Callable {
            val rows=JSONArray();val baseline=FilmEngine.liveGpuImages()
            for(half in listOf(false,true)) {
                val space=ColorSpace.get(if(half) ColorSpace.Named.EXTENDED_SRGB else ColorSpace.Named.SRGB)
                val bitmap=Bitmap.createBitmap(259,33,if(half) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888,true,space)
                try {
                    if(half) {
                        val bytes=ByteBuffer.allocate(bitmap.byteCount).order(ByteOrder.nativeOrder())
                        val special=floatArrayOf(-0.3f,-0.04045f,-0.001f,0f,0.001f,0.04045f,0.5f,1f,1.3f)
                        for(y in 0 until bitmap.height) for(x in 0 until bitmap.width) {
                            val at=y*bitmap.rowBytes+x*8
                            val rgb=if(x<special.size) floatArrayOf(special[x],special[x],special[x]) else floatArrayOf(x/258f*1.6f-.3f,y/32f*1.6f-.3f,(x%17)/16f*1.6f-.3f)
                            for(c in 0..2) bytes.putShort(at+c*2,Half.toHalf(rgb[c]))
                            bytes.putShort(at+6,Half.toHalf(1f))
                        }
                        bitmap.copyPixelsFromBuffer(bytes)
                    } else {
                        val pixels=IntArray(bitmap.width*bitmap.height) {i->android.graphics.Color.rgb((i%bitmap.width)%256,(i/bitmap.width)*7,(i*13)%256)}
                        bitmap.setPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                    }
                    val oracle=ColorSpace.connect(space,BitmapEditInput.linearProPhoto)
                    VulkanRawDeveloper.convertSrgbBitmap(bitmap,BitmapEditInput.srgbToProPhoto,readbackForTest=true).use {gpu->
                        val floats=gpu.pixels.asFloatBuffer();var maximum=0f;var square=0.0
                        for(y in 0 until bitmap.height) for(x in 0 until bitmap.width) {
                            val color=bitmap.getColor(x,y);val expected=oracle.transform(color.red(),color.green(),color.blue())
                            repeat(3) {c->val actual=floats.get();assertTrue(actual.isFinite());val error=kotlin.math.abs(actual-expected[c]);maximum=maxOf(maximum,error);square+=error.toDouble()*error}
                        }
                        val metrics=JSONObject().put("format",bitmap.config.toString()).put("maxLinearError",maximum).put("rmsLinearError",kotlin.math.sqrt(square/(bitmap.width*bitmap.height*3)))
                        rows.put(metrics);assertTrue(metrics.toString(),maximum<=0.000005f)
                    }
                    assertEquals(baseline,FilmEngine.liveGpuImages())
                    if(half) {
                        val bytes=ByteBuffer.allocate(bitmap.byteCount).order(ByteOrder.nativeOrder())
                        bitmap.copyPixelsToBuffer(bytes);bytes.putShort(0,Half.toHalf(Float.NaN));bytes.rewind();bitmap.copyPixelsFromBuffer(bytes)
                        assertTrue(runCatching {VulkanRawDeveloper.convertSrgbBitmap(bitmap,BitmapEditInput.srgbToProPhoto)}.isFailure)
                        assertEquals(baseline,FilmEngine.liveGpuImages())
                    }
                } finally {bitmap.recycle()}
            }
            val app=InstrumentationRegistry.getInstrumentation().targetContext
            File(app.cacheDir,"bitmap-gpu-conversion.json").writeText(JSONObject().put("cases",rows).toString(2))
        })
        FilmWorker.execute {task.run()};task.get(90,TimeUnit.SECONDS)
    }
}
