package io.github.c52e.filcamera

import android.opengl.GLES30.*
import io.github.c52e.filcamera.preview.PreviewEffect
import org.json.JSONObject
import kotlin.math.*

/** Phone-generated film preview table; no table means unmodified ISP preview. */
class FilmTableEffect() : PreviewEffect {
    private var texture=0
    private var meter=PreviewMeter()
    @Volatile var meteringEnabled=true
    @Volatile var appliedEv=0f; private set
    @Volatile var targetEv=0f; private set
    private var lastMeter=0L
    private var smoothing=PreviewExposure()
    @Volatile private var selected: PreviewLut?=null
    private var lastPresented=0L
    private var measureFrom=0L
    private var measuredFrames=0
    private var largestGap=0.0
    private val slowFrames=ArrayList<JSONObject>(8)
    private var awaitingUploadFrame=false
    @Volatile var uploadFrameGapMs=0.0; private set
    @Synchronized fun resetFrameProbe() { measureFrom=android.os.SystemClock.elapsedRealtimeNanos();measuredFrames=0;largestGap=0.0;slowFrames.clear() }
    @Synchronized fun frameProbe(): JSONObject = JSONObject().put("frames",measuredFrames)
        .put("fps",if(measureFrom==0L) 0.0 else measuredFrames*1e9/(android.os.SystemClock.elapsedRealtimeNanos()-measureFrom))
        .put("clock","elapsedRealtimeNanos").put("slowFrames",org.json.JSONArray(slowFrames))
        .put("maxGapMs",largestGap).put("uploadFrameGapMs",uploadFrameGapMs).put("uploadMs",uploadMs)
    @Synchronized private fun observeFrame() {
        val now=android.os.SystemClock.elapsedRealtimeNanos()
        if(lastPresented>0) {
            val gap=(now-lastPresented)/1e6
            if(measureFrom>0 && lastPresented>=measureFrom) {
                largestGap=max(largestGap,gap)
                if(gap>50) {
                    slowFrames.add(JSONObject().put("fromNs",lastPresented).put("toNs",now).put("gapMs",gap))
                    slowFrames.sortByDescending {it.getDouble("gapMs")}
                    if(slowFrames.size>8) slowFrames.removeAt(8)
                }
            }
            if(awaitingUploadFrame) { uploadFrameGapMs=gap;awaitingUploadFrame=false }
        }
        lastPresented=now;if(measureFrom>0) measuredFrames++
    }
    @Volatile var uploadMs=0.0; private set
    @Volatile var lutSource="isp"; private set
    @Volatile var lutHash=""; private set
    val lutKey get()=selected?.key
    fun replaceLut(lut: PreviewLut) {
        val started=System.nanoTime()
        val ids=IntArray(1);glGenTextures(1,ids,0)
        try {
            glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_3D,ids[0])
            glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MIN_FILTER,GL_LINEAR)
            glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MAG_FILTER,GL_LINEAR)
            for(axis in intArrayOf(GL_TEXTURE_WRAP_S,GL_TEXTURE_WRAP_T,GL_TEXTURE_WRAP_R)) glTexParameteri(GL_TEXTURE_3D,axis,GL_CLAMP_TO_EDGE)
            glTexImage3D(GL_TEXTURE_3D,0,GL_RGBA16F,65,65,65,0,GL_RGBA,GL_HALF_FLOAT,lut.pixels())
            check(glGetError()==GL_NO_ERROR) { "LUT texture upload failed" }
            val old=texture;texture=ids[0];selected=lut;lutSource=lut.source;lutHash=lut.sha256
            if(old!=0) glDeleteTextures(1,intArrayOf(old),0)
            uploadMs=(System.nanoTime()-started)/1e6
            awaitingUploadFrame=true
        } catch(e: Exception) {
            glDeleteTextures(1,ids,0);glBindTexture(GL_TEXTURE_3D,texture);throw e
        } finally { glActiveTexture(GL_TEXTURE0) }
    }
    override fun create(program: Int) {
        texture=0 // A new context cannot delete the previous context's integer texture name.
        lastPresented=0;measureFrom=0;measuredFrames=0;largestGap=0.0;uploadFrameGapMs=0.0
        synchronized(this) {slowFrames.clear()}
        // A failed restore must still leave an operational ISP preview.
        selected?.let { lut ->
            try { replaceLut(lut) }
            catch (_: Exception) { selected=null;lutSource="isp";lutHash="" }
        }
        meter=PreviewMeter();meter.create()
        appliedEv=0f;targetEv=0f;lastMeter=0;smoothing=PreviewExposure()
    }
    override fun sourceChanged() {lastMeter=0;smoothing=PreviewExposure();appliedEv=0f;targetEv=0f}
    override fun frame(texture: Int,matrix: FloatArray,cosine: Float,sine: Float,aspect: Float,timestampNs: Long) {
        observeFrame()
        if(meteringEnabled && (lastMeter==0L || timestampNs-lastMeter>=100_000_000L)) {
            val w=if(aspect>=1f) 256 else (256*aspect).roundToInt().coerceIn(1,256)
            val h=if(aspect>=1f) (256/aspect).roundToInt().coerceIn(1,256) else 256
            targetEv=meter.measure(texture,matrix,cosine,sine,w,h).coerceIn(-4f,2f)
            lastMeter=timestampNs
        }
        appliedEv=smoothing.update(targetEv,meteringEnabled,timestampNs)
    }
    override fun metadata(): Map<String,String> {
        val table=selected
        return mapOf("meter" to "gpu-center-weighted-256-v1","enabled" to meteringEnabled.toString(),
        "appliedEv" to appliedEv.toString(),"targetEv" to targetEv.toString(),"smoothingSeconds" to "0.35", "lutSource" to (table?.source ?: "isp"), "lutSha256" to (table?.sha256 ?: ""), "lutKey" to (table?.key ?: ""),"recipe" to (table?.recipe?.json()?.toString() ?: ""),"recipeHash" to (table?.recipe?.hash ?: ""),"colorRecipeHash" to (table?.recipe?.colorHash ?: ""),"omittedEffects" to "DIR spatial,Film diffusion,Print diffusion,Grain,Halation,Scanner sharpening")
    }
    override fun bind(program: Int) {
        glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_3D,texture)
        glUniform1i(glGetUniformLocation(program,"uFilmTable"),1)
        glUniform1i(glGetUniformLocation(program,"uHasLut"),if(texture!=0) 1 else 0)
        glUniform1f(glGetUniformLocation(program,"uInputGain"),2f.pow(appliedEv))
        glActiveTexture(GL_TEXTURE0)
    }
    override val fragment: String get() = FRAGMENT
    companion object {
        const val FRAGMENT="""#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
precision highp sampler3D;
uniform samplerExternalOES uCamera;
uniform sampler3D uFilmTable;
uniform bool uBypass;
uniform bool uHasLut;
uniform float uInputGain;
in vec2 vUv;
out vec4 color;
void main() {
    vec3 camera=texture(uCamera,vUv).rgb;
    if(uBypass || !uHasLut) { color=vec4(camera,1.0);return; }
    // YUV-to-RGB undershoot must not reach the fractional-power branch.
    vec3 signal=max(camera,vec3(0.0));
    vec3 linear=mix(signal/12.92,pow((signal+0.055)/1.055,vec3(2.4)),step(vec3(0.04045),signal));
    // Metered input gain precedes the fixed film model; ISP still cannot be inverted.
    linear*=uInputGain;
    vec3 shaped=log(vec3(1.0)+32.0*clamp(linear,0.0,4.0))/log(129.0);
    color=vec4(texture(uFilmTable,(shaped*64.0+0.5)/65.0).rgb,1.0);
}
"""
    }
}

