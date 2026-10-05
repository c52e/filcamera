package io.github.c52e.filcamera.imaging

import java.nio.ByteOrder
import kotlin.math.*

/** Scalar reference: calibrated CFA -> bilinear demosaic -> area resize -> linear Rec.2020.
 * Three sensor rows and two RGB rows are the only scratch storage; no full-size RGB intermediate.
 * Bilinear is a conservative baseline, not an edge-aware or denoising demosaic.
 */
class BilinearRawDeveloper(maxPixels: Int = DEFAULT_MAX_PIXELS,
                           useShadingCache: Boolean = true) : RawDeveloper by StreamingRawDeveloper(maxPixels,useShadingCache,false) {
    companion object {
        const val ID="bilinear-area-v1"
        const val DEFAULT_MAX_PIXELS=3_000_000
    }
}

/** Directional green followed by local color-difference interpolation, with neighbor bounds.
 * Shares calibration and area resizing with the retained bilinear reference.
 */
class EdgeDirectedRawDeveloper(maxPixels: Int = BilinearRawDeveloper.DEFAULT_MAX_PIXELS) :
    RawDeveloper by StreamingRawDeveloper(maxPixels,true,true) {
    companion object { const val ID="directional-difference-area-v1" }
}

internal class StreamingRawDeveloper(private val maxPixels: Int,
                                     private val useShadingCache: Boolean,
                                     private val directional: Boolean) : RawDeveloper {
    init { require(maxPixels in 1..12_000_000) }

    override fun develop(frame: RawFrame): LinearImage = with(frame) {
        validate()
        val cw=right-left; val ch=bottom-top
        val scale=min(1.0,sqrt(maxPixels.toDouble()/(cw.toLong()*ch)))
        val w=max(1,min(maxPixels,floor(cw*scale).toInt()))
        val h=max(1,min(maxPixels/w,floor(ch*scale).toInt()))
        val dst=FloatArray(w*h*3)
        val src=bytes.slice().order(ByteOrder.LITTLE_ENDIAN)
        val matrix=ColorMath.multiply(ColorMath.srgbTo2020,cameraToSrgb)
        val rowCount=if(directional) 5 else 3
        val mosaic=Array(rowCount) {FloatArray(cw)}
        val rowIds=IntArray(rowCount) {-1}
        val shadingRows=if(useShadingCache) shading?.let {ShadingRows(it,cw,ch,left,top,pattern)} else null
        val shadingRow=if(shadingRows!=null) FloatArray(cw) else null
        // Reflect by one pixel at the active boundary: preserves each CFA site's parity.
        fun row(y: Int): FloatArray {
            val sy=when { y<0 -> -y; y>=ch -> 2*ch-2-y; else -> y }
            val slot=sy%rowCount
            if(rowIds[slot]!=sy) {
                val target=mosaic[slot]
                if(shadingRow!=null) shadingRows!!.fill(shadingRow,sy)
                for(x in 0 until cw) {
                    val site=((sy+top) and 1)*2+((x+left) and 1)
                    val channel=pattern.channels[site]
                    val value=src.getShort((sy+top)*rowStride+(x+left)*pixelStride).toInt() and 65535
                    val correction=shadingRow?.get(x) ?: shading?.gain(channel,x.toFloat()/(cw-1),sy.toFloat()/(ch-1)) ?: 1f
                    target[x]=max(0f,(value-black[site])/(white-black[site]))*gains[channel]*correction
                }
                rowIds[slot]=sy
            }
            return mosaic[slot]
        }
        val greenRows=if(directional) Array(3){FloatArray(cw)} else emptyArray()
        val greenIds=IntArray(3){-1}
        fun green(y: Int): FloatArray {
            val gy=when {y<0 -> -y; y>=ch -> 2*ch-2-y; else -> y}
            val slot=gy%3
            if(greenIds[slot]!=gy) {
                val above=row(gy-1); val center=row(gy); val below=row(gy+1)
                val target=greenRows[slot]
                for(x in 0 until cw) {
                    val channel=pattern.channels[((gy+top) and 1)*2+((x+left) and 1)]
                    if(channel==1 || channel==2) target[x]=center[x] else {
                        val xl=if(x==0) 1 else x-1; val xr=if(x==cw-1) cw-2 else x+1
                        val dh=abs(center[xl]-center[xr]); val dv=abs(above[x]-below[x])
                        val horizontal=(center[xl]+center[xr])*.5f
                        val vertical=(above[x]+below[x])*.5f
                        // Continuous blend avoids a hard direction flip at nearly tied gradients.
                        val direction=(3f*(dv-dh)/(dv+dh+1e-4f)).coerceIn(-1f,1f)
                        target[x]=horizontal*(.5f+.5f*direction)+vertical*(.5f-.5f*direction)
                    }
                }
                greenIds[slot]=gy
            }
            return greenRows[slot]
        }
        val rgb=FloatArray(cw*3)
        val horizontal=FloatArray(w*3)
        val sx=cw.toDouble()/w; val sy=ch.toDouble()/h
        for(y in 0 until ch) {
            if(Thread.currentThread().isInterrupted) throw InterruptedException()
            val ga=if(directional) green(y-1) else null
            val gc=if(directional) green(y) else null
            val gb=if(directional) green(y+1) else null
            val previous=row(y-1); val current=row(y); val next=row(y+1)
            for(x in 0 until cw) {
                val xl=if(x==0) 1 else x-1; val xr=if(x==cw-1) cw-2 else x+1
                val channel=pattern.channels[((y+top) and 1)*2+((x+left) and 1)]
                val center=current[x]; val across=(current[xl]+current[xr])*.5f
                val vertical=(previous[x]+next[x])*.5f
                val r: Float; val g: Float; val b: Float
                if(directional) {
                    g=gc!![x]
                    if(channel==0 || channel==3) {
                        val a=previous[xl]; val b0=previous[xr]; val c0=next[xl]; val d=next[xr]
                        val estimate=g+((a-ga!![xl])+(b0-ga[xr])+(c0-gb!![xl])+(d-gb[xr]))*.25f
                        val opposite=estimate.coerceIn(min(min(a,b0),min(c0,d)),max(max(a,b0),max(c0,d)))
                        r=if(channel==0) center else opposite
                        b=if(channel==3) center else opposite
                    } else {
                        val horizontal=(g+(current[xl]-gc[xl]+current[xr]-gc[xr])*.5f)
                            .coerceIn(min(current[xl],current[xr]),max(current[xl],current[xr]))
                        val vertical=(g+(previous[x]-ga!![x]+next[x]-gb!![x])*.5f)
                            .coerceIn(min(previous[x],next[x]),max(previous[x],next[x]))
                        val horizontalChannel=pattern.channels[((y+top) and 1)*2+((x+left+1) and 1)]
                        r=if(horizontalChannel==0) horizontal else vertical
                        b=if(horizontalChannel==3) horizontal else vertical
                    }
                } else if(channel==0 || channel==3) {
                    val diagonal=(previous[xl]+previous[xr]+next[xl]+next[xr])*.25f
                    g=(across+vertical)*.5f
                    r=if(channel==0) center else diagonal
                    b=if(channel==3) center else diagonal
                } else {
                    g=center
                    val horizontalChannel=pattern.channels[((y+top) and 1)*2+((x+left+1) and 1)]
                    r=if(horizontalChannel==0) across else vertical
                    b=if(horizontalChannel==3) across else vertical
                }
                rgb[x*3]=r; rgb[x*3+1]=g; rgb[x*3+2]=b
            }
            // Exact box overlap, including partial pixels at both ends. Never discards crop edges.
            horizontal.fill(0f)
            for(ox in 0 until w) {
                val begin=ox*sx; val end=(ox+1)*sx
                for(ix in floor(begin).toInt() until min(cw,ceil(end).toInt())) {
                    val weight=((min(end,ix+1.0)-max(begin,ix.toDouble()))/sx).toFloat()
                    for(c in 0..2) horizontal[ox*3+c]+=rgb[ix*3+c]*weight
                }
            }
            val first=min(h-1,floor(y/sy).toInt()); val last=min(h-1,floor((y+1.0)/sy).toInt())
            for(oy in first..last) {
                val weight=(max(0.0,min(y+1.0,(oy+1)*sy)-max(y.toDouble(),oy*sy))/sy).toFloat()
                if(weight==0f) continue
                for(i in horizontal.indices) dst[oy*w*3+i]+=horizontal[i]*weight
            }
        }
        // Linear color transform commutes with resizing; run it on the bounded output only.
        for(y in 0 until h) {
            if(Thread.currentThread().isInterrupted) throw InterruptedException()
            for(x in 0 until w) {
                val i=(y*w+x)*3; val r=dst[i]; val g=dst[i+1]; val b=dst[i+2]
                for(c in 0..2) dst[i+c]=matrix[c*3]*r+matrix[c*3+1]*g+matrix[c*3+2]*b
            }
        }
        LinearImage(w,h,dst)
    }
}
