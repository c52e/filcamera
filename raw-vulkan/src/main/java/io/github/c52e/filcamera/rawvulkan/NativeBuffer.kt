package io.github.c52e.filcamera.rawvulkan

import io.github.c52e.filcamera.imaging.RawLimits

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue

/** Native image storage. Explicit owner-thread close is primary; the queue guards abandoned callers. */
class NativeBuffer private constructor(val pixels: ByteBuffer,private val lease: PhantomReference<ByteBuffer>): AutoCloseable {
    override fun close() {release(lease)}
    companion object {
        private val queue=ReferenceQueue<ByteBuffer>()
        private val allocations=HashMap<PhantomReference<ByteBuffer>,Long>()
        init {
            System.loadLibrary("rawvulkan")
            Thread({while(true) {val ref=queue.remove();@Suppress("UNCHECKED_CAST") release(ref as PhantomReference<ByteBuffer>)}},"RawBufferReaper").apply {isDaemon=true;start()}
        }
        private fun release(ref: PhantomReference<ByteBuffer>) {val pointer=synchronized(allocations) {allocations.remove(ref)};if(pointer!=null) free(pointer);ref.clear()}
        fun allocate(bytes: Int): NativeBuffer {
            require(bytes>0 && bytes<=RawLimits.MAX_PIXELS*12)
            val pointer=allocateNative(bytes)
            try {
                val pixels=view(pointer,bytes).asReadOnlyBuffer().order(ByteOrder.nativeOrder())
                val ref=PhantomReference(pixels,queue)
                synchronized(allocations) {allocations[ref]=pointer}
                return NativeBuffer(pixels,ref)
            } catch(e: Throwable) {free(pointer);throw e}
        }
        @JvmStatic private external fun allocateNative(bytes: Int): Long
        @JvmStatic private external fun view(pointer: Long,bytes: Int): ByteBuffer
        @JvmStatic private external fun free(pointer: Long)
    }
}
