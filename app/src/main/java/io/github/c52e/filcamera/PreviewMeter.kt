package io.github.c52e.filcamera

import android.opengl.GLES30.*
import android.opengl.GLES11Ext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Two-pass GPU center-weighted reduction. Only one RGBA float scalar is read back. */
class PreviewMeter(private val external: Boolean=true) {
    private var sampleProgram=0
    private var reduceProgram=0
    private val textures=IntArray(2)
    private val framebuffers=IntArray(2)
    private var vao=0
    private val scalar=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asFloatBuffer()
    fun create() {
        sampleProgram=program(if(external) SAMPLE else SAMPLE.replace("#extension GL_OES_EGL_image_external_essl3 : require","").replace("samplerExternalOES","sampler2D"))
        reduceProgram=program(REDUCE)
        val id=IntArray(1);glGenVertexArrays(1,id,0);vao=id[0]
        glGenTextures(2,textures,0);glGenFramebuffers(2,framebuffers,0)
        for(i in 0..1) {
            val size=if(i==0) 16 else 1
            glBindTexture(GL_TEXTURE_2D,textures[i])
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST)
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,size,size,0,GL_RGBA,GL_FLOAT,null)
            glBindFramebuffer(GL_FRAMEBUFFER,framebuffers[i])
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,textures[i],0)
            check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE) { "Float preview metering target unavailable" }
        }
        glBindFramebuffer(GL_FRAMEBUFFER,0);glBindTexture(GL_TEXTURE_2D,0)
    }
    fun measure(texture: Int,matrix: FloatArray,cosine: Float,sine: Float,width: Int,height: Int): Float {
        require(width in 1..256 && height in 1..256)
        val viewport=IntArray(4);glGetIntegerv(GL_VIEWPORT,viewport,0)
        val previous=IntArray(1);glGetIntegerv(GL_FRAMEBUFFER_BINDING,previous,0)
        try {
            glBindVertexArray(vao);glActiveTexture(GL_TEXTURE0)
            glBindTexture(if(external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GL_TEXTURE_2D,texture)
            glBindFramebuffer(GL_FRAMEBUFFER,framebuffers[0]);glViewport(0,0,16,16);glUseProgram(sampleProgram)
            glUniform1i(glGetUniformLocation(sampleProgram,"uCamera"),0)
            glUniform2i(glGetUniformLocation(sampleProgram,"uSize"),width,height)
            glUniform2f(glGetUniformLocation(sampleProgram,"uRotation"),cosine,sine)
            glUniformMatrix4fv(glGetUniformLocation(sampleProgram,"uMatrix"),1,false,matrix,0)
            glDrawArrays(GL_TRIANGLE_STRIP,0,4)
            glBindFramebuffer(GL_FRAMEBUFFER,framebuffers[1]);glViewport(0,0,1,1);glUseProgram(reduceProgram)
            glBindTexture(GL_TEXTURE_2D,textures[0]);glUniform1i(glGetUniformLocation(reduceProgram,"uTiles"),0)
            glDrawArrays(GL_TRIANGLE_STRIP,0,4)
            scalar.rewind();glReadPixels(0,0,1,1,GL_RGBA,GL_FLOAT,scalar)
            check(glGetError()==GL_NO_ERROR) { "Preview metering failed" }
            val mean=scalar.get(0)/scalar.get(1)
            require(mean.isFinite() && mean>=0) {
                "Invalid preview meter: sum=${scalar.get(0)}, weight=${scalar.get(1)}, mean=$mean, size=${width}x$height"
            }
            return if(mean<=1e-8f) 0f else (ln(.184f/mean)/ln(2f))
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER,previous[0]);glViewport(viewport[0],viewport[1],viewport[2],viewport[3])
            glActiveTexture(GL_TEXTURE0)
        }
    }
    private fun program(fragment: String): Int {
        fun shader(type: Int,source: String): Int {
            val s=glCreateShader(type);glShaderSource(s,source);glCompileShader(s)
            val ok=IntArray(1);glGetShaderiv(s,GL_COMPILE_STATUS,ok,0)
            if(ok[0]==0) { val log=glGetShaderInfoLog(s);glDeleteShader(s);error(log) };return s
        }
        val vertex=shader(GL_VERTEX_SHADER,VERTEX)
        val frag=try { shader(GL_FRAGMENT_SHADER,fragment) } catch(e: Exception) { glDeleteShader(vertex);throw e }
        val p=glCreateProgram();glAttachShader(p,vertex);glAttachShader(p,frag);glLinkProgram(p)
        glDeleteShader(vertex);glDeleteShader(frag)
        val ok=IntArray(1);glGetProgramiv(p,GL_LINK_STATUS,ok,0)
        if(ok[0]==0) { val log=glGetProgramInfoLog(p);glDeleteProgram(p);error(log) };return p
    }
    companion object {
        const val VERTEX="""#version 300 es
void main(){vec2 p=vec2((gl_VertexID&1)==0?-1.:1.,(gl_VertexID&2)==0?-1.:1.);gl_Position=vec4(p,0,1);}
"""
        const val SAMPLE="""#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uCamera;
uniform ivec2 uSize;
uniform mat4 uMatrix;
uniform vec2 uRotation;
out vec4 color;
void main(){
    ivec2 origin=ivec2(gl_FragCoord.xy)*16;
    vec2 sum=vec2(0);
    for(int y=0;y<16;y++) for(int x=0;x<16;x++) {
        ivec2 i=origin+ivec2(x,y);
        if(any(greaterThanEqual(i,uSize))) continue;
        vec2 centered=(vec2(i)/vec2(uSize)-.5)*vec2(uSize)/float(max(uSize.x,uSize.y));
        float weight=exp(-dot(centered,centered)/.08);
        vec2 p=(vec2(i)+.5)/vec2(uSize)*2.-1.;p.y=-p.y;
        vec2 q=vec2(uRotation.x*p.x-uRotation.y*p.y,uRotation.y*p.x+uRotation.x*p.y);
        vec2 uv=(uMatrix*vec4(q*.5+.5,0,1)).xy;
        // External YUV conversion can produce negative RGB. Both mix operands
        // are evaluated, so an unused negative-base pow can still poison the sum.
        vec3 c=max(texture(uCamera,uv).rgb,vec3(0.0));
        vec3 linear=mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c));
        // Y after standard CAT16 sRGB -> ProPhoto conversion (same space as the oracle).
        float luminance=dot(linear,vec3(.21348993,.71272146,.07381137));
        sum+=vec2(luminance*weight,weight);
    }
    color=vec4(sum,0,1);
}
"""
        const val REDUCE="""#version 300 es
precision highp float;
uniform highp sampler2D uTiles;
out vec4 color;
void main(){vec2 sum=vec2(0);for(int y=0;y<16;y++)for(int x=0;x<16;x++)sum+=texelFetch(uTiles,ivec2(x,y),0).rg;color=vec4(sum,0,1);}
"""
    }
}
