package io.github.c52e.filcamera

import android.opengl.EGL14.*
import android.opengl.EGLConfig
import android.opengl.GLES30.*
import android.opengl.Matrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.pow

/** Real GPU regression, including negative RGB produced by external YUV conversion. */
class PreviewMeterGpuTest {
    @Test fun meteringHandlesBlackGrayWhiteAndNegativeCameraChannels() {
        val display=eglGetDisplay(EGL_DEFAULT_DISPLAY)
        check(eglInitialize(display,IntArray(2),0,IntArray(2),1))
        val configs=arrayOfNulls<EGLConfig>(1)
        val count=IntArray(1)
        check(eglChooseConfig(display,intArrayOf(EGL_RENDERABLE_TYPE,0x40,EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_NONE),0,configs,0,1,count,0))
        val config=checkNotNull(configs[0])
        val context=eglCreateContext(display,config,EGL_NO_CONTEXT,intArrayOf(EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE),0)
        val surface=eglCreatePbufferSurface(display,config,intArrayOf(EGL_WIDTH,16,EGL_HEIGHT,16,EGL_NONE),0)
        try {
            check(eglMakeCurrent(display,surface,surface,context))
            val meter=PreviewMeter(false);meter.create()
            val texture=IntArray(1);glGenTextures(1,texture,0)
            val matrix=FloatArray(16).also {Matrix.setIdentityM(it,0)}
            for(rgb in listOf(floatArrayOf(0f,0f,0f),floatArrayOf(.5f,.5f,.5f),floatArrayOf(1f,1f,1f),floatArrayOf(-.1f,.5f,1f),floatArrayOf(-.2f,-.1f,-.3f))) {
                glBindTexture(GL_TEXTURE_2D,texture[0])
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST)
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST)
                val pixel=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asFloatBuffer()
                pixel.put(floatArrayOf(rgb[0],rgb[1],rgb[2],1f)).rewind()
                glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,1,1,0,GL_RGBA,GL_FLOAT,pixel)
                check(glGetError()==GL_NO_ERROR)
                fun decode(v: Float): Double {
                    val c=v.coerceAtLeast(0f).toDouble()
                    return if(c<=.04045) c/12.92 else ((c+.055)/1.055).pow(2.4)
                }
                val mean=decode(rgb[0])*.21348993+decode(rgb[1])*.71272146+decode(rgb[2])*.07381137
                val expected=if(mean<=1e-8) 0.0 else ln(.184/mean)/ln(2.0)
                for(size in listOf(192 to 256,256 to 192)) repeat(2) {
                    val actual=meter.measure(texture[0],matrix,1f,0f,size.first,size.second)
                    assertTrue("${rgb.toList()}: $actual",actual.isFinite())
                    assertEquals("${rgb.toList()} at $size",expected,actual.toDouble(),.002)
                }
            }
            glDeleteTextures(1,texture,0)
        } finally {
            eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT)
            eglDestroySurface(display,surface);eglDestroyContext(display,context);eglTerminate(display)
        }
    }
}
