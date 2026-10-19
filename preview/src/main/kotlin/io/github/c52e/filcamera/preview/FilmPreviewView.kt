package io.github.c52e.filcamera.preview

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.view.Surface
import io.github.c52e.filcamera.imaging.FilmRecipe
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.nio.ByteBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

/** Optional rendering effect. Methods run on the GL owner thread; recreate resources per context. */
interface PreviewEffect {
    val fragment: String
    fun create(program: Int)
    fun bind(program: Int)
    /** Optional pre-draw work; restore framebuffer, viewport and texture unit when returning. */
    fun frame(texture: Int, matrix: FloatArray, cosine: Float, sine: Float, aspect: Float, timestampNs: Long) {}
    fun sourceChanged() {}
    fun metadata(): Map<String, String> = emptyMap()
}

/** Two views of exactly the same OES frame, cropped to the displayed image rectangle. */
data class PreviewSnapshot(val timestampNs: Long, val width: Int, val height: Int,
    val isp: IntArray, val film: IntArray, val displayDegrees: Int,
    val effectMetadata: Map<String,String> = emptyMap())

/** Frame-driven OES preview: no CPU readback, no per-frame bitmap, one shader draw. */
class FilmPreviewView(context: Context,
    private val targetReady: (SurfaceTexture, Surface, () -> Unit) -> Unit,
    private val metrics: (String) -> Unit,
    private val failure: (String) -> Unit,
    private val effect: PreviewEffect? = null
) : GLSurfaceView(context), GLSurfaceView.Renderer {
    @Volatile var recipe = FilmRecipe()
    @Volatile var bypass = false
    @Volatile var displayRotationDegrees = 0
    @Volatile var sourceWidth = 1280
    @Volatile var sourceHeight = 960
    @Volatile var realtimeTimestamps = false
    private class Target(val texture: SurfaceTexture, val surface: Surface) { var released = false }
    @Volatile private var target: Target? = null
    private var contextEpoch=0
    private var widthPx = 1
    private var heightPx = 1
    private var program = 0
    private var oes = 0
    private var vao = 0
    private val frameAvailable = AtomicBoolean()
    private var hasFrame = false
    private val textureMatrix = FloatArray(16)
    private val producerMatrix = FloatArray(16)
    private var cropScale=1f
    private data class Geometry(val width: Int,val height: Int,val matrix: FloatArray,
        val sourceWidth: Int,val sourceHeight: Int,val rotation: Int)
    @Volatile private var geometry: Geometry?=null
    fun focusPoint(x: Float,y: Float): PreviewGeometry.Point? {
        val g=geometry ?: return null
        if(g.rotation!=displayRotationDegrees || g.width!=width || g.height!=height) return null
        return PreviewGeometry.tap(x,y,g.width,g.height,g.matrix,g.sourceWidth,g.sourceHeight,g.rotation)
    }
    private var cropApplied: (() -> Unit)?=null
    fun setCenterCrop(scale: Float,applied: ()->Unit) {
        require(scale.isFinite() && scale>0f && scale<=1f)
        geometry=null
        queueEvent {cropScale=scale;cropApplied=applied};requestRender()
    }
    private var frameCount = 0
    private var startNs = 0L
    private var drawTotalNs = 0L
    private var failed = false
    private var textureLoc = 0
    private var scaleLoc = 0
    private var rotationLoc = 0
    private var paramsLoc = 0
    private var bypassLoc = 0
    private val snapshot = AtomicReference<((PreviewSnapshot?) -> Unit)?>(null)
    fun snapshotOnce(callback: (PreviewSnapshot?) -> Unit): Boolean {
        if (!snapshot.compareAndSet(null,callback)) return false
        requestRender(); return true
    }
    fun cancelSnapshot() { snapshot.getAndSet(null)?.invoke(null) }

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8,8,8,0,0,0)
        preserveEGLContextOnPause = false
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        failed = false; hasFrame = false; frameAvailable.set(false)
        try {
            program = link(VERTEX,effect?.fragment ?: FRAGMENT)
            effect?.create(program)
            glActiveTexture(GL_TEXTURE0)
            textureLoc=glGetUniformLocation(program,"uTextureMatrix")
            scaleLoc=glGetUniformLocation(program,"uScale")
            rotationLoc=glGetUniformLocation(program,"uRotation")
            paramsLoc=glGetUniformLocation(program,"uParams")
            bypassLoc=glGetUniformLocation(program,"uBypass")
            val ids = IntArray(1)
            glGenVertexArrays(1,ids,0); vao=ids[0]; glBindVertexArray(vao)
            contextEpoch++;createTarget(targetReady)
        } catch (e: Exception) { reportFailure(e) }
    }
    /** Allocate a new producer target without recreating the LUT/effect or EGL context. */
    fun replaceCameraTarget(ready: (SurfaceTexture,Surface,()->Unit)->Unit) {
        geometry=null
        queueEvent {try {createTarget(ready)} catch(e: Exception) {reportFailure(e)}}
    }
    private fun createTarget(ready: (SurfaceTexture,Surface,()->Unit)->Unit) {
        hasFrame=false;frameAvailable.set(false);cropApplied=null;geometry=null
        effect?.sourceChanged();glActiveTexture(GL_TEXTURE0)
        val ids=IntArray(1)
        glGenTextures(1,ids,0); oes=ids[0]
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,oes)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_MIN_FILTER,GL_LINEAR)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_MAG_FILTER,GL_LINEAR)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE)
        val textureId=oes;val epoch=contextEpoch
        val texture = SurfaceTexture(oes)
        val next = Target(texture,Surface(texture))
        target=next
        texture.setOnFrameAvailableListener { if(target===next) {frameAvailable.set(true); requestRender()} }
        startNs=System.nanoTime(); frameCount=0; drawTotalNs=0
        // Called from GL thread; controller serializes attachment on its camera handler.
        ready(texture,next.surface) { synchronized(next) {
            if (!next.released) { next.released=true; next.surface.release(); next.texture.release();queueEvent {if(epoch==contextEpoch) glDeleteTextures(1,intArrayOf(textureId),0)} }
        } }
    }
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { widthPx=width; heightPx=height; glViewport(0,0,width,height) }
    override fun onDrawFrame(gl: GL10?) {
        glClearColor(.025f,.025f,.025f,1f); glClear(GL_COLOR_BUFFER_BIT)
        if (failed) return
        val t=target ?: return
        synchronized(t) {
            if (t.released) return
            try {
                val started=System.nanoTime()
                val fresh=frameAvailable.getAndSet(false)
                if (fresh) { t.texture.updateTexImage(); t.texture.getTransformMatrix(producerMatrix); hasFrame=true }
                if (!hasFrame) return
                PreviewGeometry.centerCrop(producerMatrix,cropScale,textureMatrix)
                val displayDegrees=displayRotationDegrees
                val rotation=PreviewGeometry.rotation(displayDegrees)
                val aspect=PreviewGeometry.aspect(textureMatrix,sourceWidth,sourceHeight,rotation)
                val screenAspect=widthPx.toFloat()/heightPx
                if(fresh) effect?.frame(oes,textureMatrix,rotation.cosine,rotation.sine,aspect,t.texture.timestamp)
                glUseProgram(program); glBindVertexArray(vao)
                glActiveTexture(GL_TEXTURE0); glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,oes)
                glUniformMatrix4fv(textureLoc,1,false,textureMatrix,0)
                glUniform2f(scaleLoc, min(1f,aspect/screenAspect),min(1f,screenAspect/aspect))
                glUniform2f(rotationLoc,rotation.cosine,rotation.sine)
                val r=recipe
                glUniform3f(paramsLoc,r.exposureEv,r.contrast,if (r.monochrome) 1f else 0f)
                glUniform1i(bypassLoc,if (bypass) 1 else 0)
                effect?.bind(program)
                glDrawArrays(GL_TRIANGLE_STRIP,0,4)
                val previousGeometry=geometry
                if(previousGeometry==null || previousGeometry.width!=widthPx || previousGeometry.height!=heightPx ||
                    previousGeometry.sourceWidth!=sourceWidth || previousGeometry.sourceHeight!=sourceHeight ||
                    previousGeometry.rotation!=displayDegrees || !previousGeometry.matrix.contentEquals(textureMatrix))
                    geometry=Geometry(widthPx,heightPx,textureMatrix.copyOf(),sourceWidth,sourceHeight,displayDegrees)
                cropApplied?.let {cropApplied=null;it()}
                snapshot.getAndSet(null)?.let { callback ->
                    try {
                        val w=(widthPx*min(1f,aspect/screenAspect)).roundToInt().coerceIn(1,widthPx)
                        val h=(heightPx*min(1f,screenAspect/aspect)).roundToInt().coerceIn(1,heightPx)
                        fun read(isBypass: Boolean): IntArray {
                            glUniform1i(bypassLoc,if(isBypass) 1 else 0)
                            glDrawArrays(GL_TRIANGLE_STRIP,0,4)
                            val bytes=ByteBuffer.allocateDirect(w*h*4)
                            glReadPixels((widthPx-w)/2,(heightPx-h)/2,w,h,GL_RGBA,GL_UNSIGNED_BYTE,bytes)
                            check(glGetError()==GL_NO_ERROR) { "Snapshot readback failed" }
                            return IntArray(w*h) { i ->
                                val j=((h-1-i/w)*w+i%w)*4
                                (0xff shl 24) or ((bytes.get(j).toInt() and 255) shl 16) or
                                    ((bytes.get(j+1).toInt() and 255) shl 8) or (bytes.get(j+2).toInt() and 255)
                            }
                        }
                        val isp=read(true);val film=read(false)
                        glUniform1i(bypassLoc,if(bypass) 1 else 0);glDrawArrays(GL_TRIANGLE_STRIP,0,4)
                        callback(PreviewSnapshot(t.texture.timestamp,w,h,isp,film,displayDegrees,effect?.metadata() ?: emptyMap()))
                    } catch (e: Exception) { callback(null) }
                }
                drawTotalNs+=System.nanoTime()-started
                if (fresh) frameCount++
                val elapsed=System.nanoTime()-startNs
                if (elapsed >= 2_000_000_000L) {
                    val age=if(realtimeTimestamps) tr(Texts.string.frame_age_0f_ms).format((android.os.SystemClock.elapsedRealtimeNanos()-t.texture.timestamp)/1e6) else ""
                    metrics(tr(Texts.string.message_1f_fps_cpu_submit_2f_ms).format(frameCount*1e9/elapsed,drawTotalNs/1e6/max(frameCount,1))+age)
                    startNs=System.nanoTime(); frameCount=0; drawTotalNs=0
                }
            } catch (e: Exception) { reportFailure(e) }
        }
    }
    private fun reportFailure(e: Exception) {
        android.util.Log.e("FilmPreviewView","GPU preview failed",e)
        failed=true; failure(tr(Texts.string.gpu_preview_failed_value, e.message))
    }
    private fun compile(type: Int, source: String): Int {
        val shader=glCreateShader(type); glShaderSource(shader,source); glCompileShader(shader)
        val ok=IntArray(1); glGetShaderiv(shader,GL_COMPILE_STATUS,ok,0)
        if (ok[0]==0) { val log=glGetShaderInfoLog(shader); glDeleteShader(shader); error(log) }
        return shader
    }
    private fun link(vertex: String,fragment: String): Int {
        val v=compile(GL_VERTEX_SHADER,vertex)
        val f=try { compile(GL_FRAGMENT_SHADER,fragment) } catch (e: Exception) { glDeleteShader(v); throw e }
        val p=glCreateProgram()
        glAttachShader(p,v); glAttachShader(p,f); glLinkProgram(p)
        glDeleteShader(v); glDeleteShader(f)
        val ok=IntArray(1); glGetProgramiv(p,GL_LINK_STATUS,ok,0)
        if (ok[0]==0) { val log=glGetProgramInfoLog(p); glDeleteProgram(p); error(log) }
        return p
    }
    companion object {
        const val VERTEX = """#version 300 es
precision highp float;
uniform mat4 uTextureMatrix;
uniform vec2 uScale;
uniform vec2 uRotation;
out vec2 vUv;
void main() {
    vec2 p=vec2((gl_VertexID & 1)==0 ? -1.0 : 1.0, (gl_VertexID & 2)==0 ? -1.0 : 1.0);
    gl_Position=vec4(p*uScale,0.0,1.0);
    // uTextureMatrix already rotates/mirrors the sensor into natural device orientation.
    // uRotation only maps display coordinates back to that natural orientation.
    vec2 q=vec2(uRotation.x*p.x-uRotation.y*p.y,uRotation.y*p.x+uRotation.x*p.y);
    vUv=(uTextureMatrix*vec4(q*0.5+0.5,0.0,1.0)).xy;
}
"""
        const val FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uCamera;
uniform vec3 uParams;
uniform bool uBypass;
in vec2 vUv;
out vec4 color;
vec3 decode(vec3 x) { x=max(x,vec3(0.0));return mix(x/12.92,pow((x+0.055)/1.055,vec3(2.4)),step(vec3(0.04045),x)); }
vec3 encode(vec3 x) { return mix(12.92*x,1.055*pow(x,vec3(1.0/2.4))-0.055,step(vec3(0.0031308),x)); }
void main() {
    vec3 camera=texture(uCamera,vUv).rgb;
    if (uBypass) { color=vec4(camera,1.0); return; }
    // ISP tone mapping cannot be inverted: this is a display-referred approximation.
    vec3 x=decode(camera)*exp2(uParams.x);
    if (uParams.z>0.5) x=vec3(dot(x,vec3(0.2126,0.7152,0.0722)));
    vec3 p=pow(max(x,vec3(0.0))/0.18,vec3(uParams.y));
    color=vec4(encode(p/(p+4.5555556)),1.0);
}
"""
    }
}
