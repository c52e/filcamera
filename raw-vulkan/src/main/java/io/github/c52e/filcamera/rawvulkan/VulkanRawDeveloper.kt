package io.github.c52e.filcamera.rawvulkan

import io.github.c52e.filcamera.imaging.*
import java.nio.ByteBuffer
import kotlin.math.*
import android.os.SystemClock

/** Original implementation. No film profiles, GPL engine or image operations on the CPU. */
class VulkanRawDeveloper(private val maxPixels: Int=3_000_000): RawDeveloper {
    var diagnostics: Map<String,Any> = emptyMap();private set
    class FilmInput(val width: Int,val height: Int,private val storage: NativeBuffer?,private var image: Long=0): AutoCloseable {
        private val owner=Thread.currentThread()
        val pixels get()=checkNotNull(storage) { "GPU image requires explicit test readback" }.pixels
        val cpuPixels get()=storage?.pixels
        val gpuImage get(): Long {check(Thread.currentThread()===owner);return image}
        override fun close() {check(Thread.currentThread()===owner);if(image!=0L) {dropImage(image);image=0};storage?.close()}
    }
    override fun develop(frame: RawFrame): LinearImage {
        return run(frame,null).use {result->
            val rgb=FloatArray(result.width*result.height*3);result.pixels.asFloatBuffer().get(rgb)
            LinearImage(result.width,result.height,rgb)
        }
    }
    data class Crop(val left: Int,val top: Int,val width: Int,val height: Int)
    fun developProPhoto(frame: RawFrame,rotation: Int,crop: Crop?=null): FilmInput {
        require(rotation in listOf(0,90,180,270))
        return run(frame,rotation,crop)
    }
    fun developGpuProPhoto(frame: RawFrame,rotation: Int,crop: Crop?=null): FilmInput {
        require(rotation in listOf(0,90,180,270));return run(frame,rotation,crop,true)
    }
    private fun run(frame: RawFrame,rotation: Int?,crop: Crop?=null,resident: Boolean=false): FilmInput {
        val start=SystemClock.elapsedRealtimeNanos()
        frame.validate()
        require(maxPixels in 1..RawLimits.MAX_PIXELS)
        val cw=frame.right-frame.left;val ch=frame.bottom-frame.top
        require(RawLimits.supports(cw,ch)) { "RAW Vulkan supports at most 60 MP active pixels" }
        val region=crop ?: Crop(0,0,cw,ch)
        require(region.left>=0 && region.top>=0 && region.width>0 && region.height>0 && region.left.toLong()+region.width<=cw && region.top.toLong()+region.height<=ch)
        val scale=min(1.0,sqrt(maxPixels.toDouble()/(region.width.toLong()*region.height)))
        val w=max(1,min(maxPixels,floor(region.width*scale).toInt()));val h=max(1,min(maxPixels/w,floor(region.height*scale).toInt()))
        val p=ArrayList<Int>();repeat(40) { p.add(0) }
        p[0]=cw;p[1]=ch;p[2]=w;p[3]=h;p[4]=frame.left;p[5]=frame.top;p[6]=frame.rowStride;p[7]=frame.pixelStride
        val shading=frame.shading
        p[8]=shading?.columns ?: 1;p[9]=shading?.rows ?: 1
        for(i in 0..3) { p[16+i]=frame.pattern.channels[i];p[20+i]=frame.black[i].toRawBits();p[25+i]=frame.gains[i].toRawBits() }
        p[24]=frame.white.toRawBits()
        p[38]=if(rotation==null) 0 else 1;p[39]=rotation ?: 0
        val matrix=ColorMath.multiply(ColorMath.srgbTo2020,frame.cameraToSrgb)
        for(i in 0..8) p[29+i]=matrix[i].toRawBits()
        p[14]=p.size;(shading?.gains ?: floatArrayOf(1f,1f,1f,1f)).forEach { p.add(it.toRawBits()) }
        fun axis(source: Int,target: Int,offsetSlot: Int,weightSlot: Int,origin: Int) {
            val ratio=source.toDouble()/target
            val offsets=IntArray(target+1);val weights=ArrayList<Int>()
            for(o in 0 until target) {
                offsets[o]=weights.size/2
                val begin=o*ratio;val end=(o+1)*ratio
                for(i in floor(begin).toInt() until min(source,ceil(end).toInt())) {
                    weights.add(i+origin);weights.add(((min(end,i+1.0)-max(begin,i.toDouble()))/ratio).toFloat().toRawBits())
                }
            }
            offsets[target]=weights.size/2
            p[offsetSlot]=p.size;offsets.forEach { p.add(it) }
            p[weightSlot]=p.size;p.addAll(weights)
        }
        axis(region.width,w,10,12,region.left);axis(region.height,h,11,13,region.top)
        val count=(frame.height-1L)*frame.rowStride+(frame.width-1L)*frame.pixelStride+2
        require(count<=256L*1024*1024) { "RAW plane exceeds upload limit" }
        val bytes=ByteBuffer.allocateDirect(((count+3)/4*4).toInt())
        bytes.put(frame.bytes.duplicate().apply { limit(position()+count.toInt()) });bytes.rewind()
        if(Thread.currentThread().isInterrupted) throw InterruptedException()
        val storage=if(resident) null else NativeBuffer.allocate(w*h*12);val output=storage?.pixels;val timings=DoubleArray(17);val image=LongArray(1)
        try {
        val prepared=SystemClock.elapsedRealtimeNanos()
        val device=process(bytes,p.toIntArray(),output,timings,image)
        if(Thread.currentThread().isInterrupted) throw InterruptedException()
        diagnostics=mapOf("backend" to ID,"device" to device,"prepareMs" to (prepared-start)/1e6,
            "totalMs" to (SystemClock.elapsedRealtimeNanos()-start)/1e6,"setupUploadMs" to timings[0],
            "calibrateMs" to if(timings[12]>0) 0.0 else timings[1],"greenMs" to timings[2],"demosaicMs" to timings[3],
            "horizontalResizeMs" to timings[4],"verticalColorMs" to timings[5],"readbackMs" to timings[6],"allocationBytes" to timings[7],"releaseMs" to timings[8],"nativeTotalMs" to timings[9])
        diagnostics=diagnostics+mapOf("dispatchCount" to timings[10].toInt(),"submitCount" to timings[11].toInt(),"batchSubmitWaitMs" to timings[12],"outputTransport" to if(resident) "gpu-image" else "test-host-f32",
            "gpuTimestampMs" to timings[13],"cpuWaitMs" to timings[14],"waitCount" to timings[15].toInt(),"timedSubmissions" to timings[16].toInt(),
            "rawUploadBytes" to bytes.capacity().toLong(),"constantsUploadBytes" to p.size.toLong()*4,
            "imageReadbackBytes" to if(resident) 0L else w.toLong()*h*12,"statisticReadbackBytes" to 4)
        return FilmInput(if(rotation==90 || rotation==270) h else w,if(rotation==90 || rotation==270) w else h,storage,image[0])
        } catch(e: Throwable) {if(image[0]!=0L) dropImage(image[0]);storage?.close();throw e}
    }
    private external fun process(bytes: ByteBuffer,params: IntArray,output: ByteBuffer?,timings: DoubleArray,image: LongArray): String
    companion object {
        const val ID="vulkan-directional-difference-area-v1"
        init { System.loadLibrary("rawvulkan") }
        @JvmStatic private external fun dropImage(image: Long)
    }
}
