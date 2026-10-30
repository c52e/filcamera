package io.github.c52e.filcamera.rawvulkan

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded classic-TIFF tag access for external DNGs. Never follows EXIF/MakerNote pointers. */
internal class DngTiff(file: File): Closeable {
    private val input=RandomAccessFile(file,"r")
    val order: ByteOrder
    data class Tag(val type: Int,val count: Int,val offset: Long)
    val directories=ArrayList<Map<Int,Tag>>()
    init {
        try {
            require(input.length() in 8..DngRawFile.MAX_FILE_BYTES) {"Unsupported DNG file size"}
            order=when(input.readUnsignedShort()) {0x4949->ByteOrder.LITTLE_ENDIAN;0x4d4d->ByteOrder.BIG_ENDIAN;else->error("Not a TIFF/DNG file")}
            require((buffer(2,2).short.toInt() and 65535)==42) {"BigTIFF DNG is not supported"}
            val seen=HashSet<Long>()
            fun visit(offset: Long) {
                if(offset==0L) return
                require(seen.add(offset) && seen.size<=16) {"Cyclic or excessive DNG directories"}
                val count=buffer(offset,2).short.toInt() and 65535
                require(count<=1024)
                val entries=buffer(offset+2,count*12+4);val tags=HashMap<Int,Tag>()
                repeat(count) {i->
                    val id=entries.short.toInt() and 65535;val type=entries.short.toInt() and 65535
                    val n=entries.int.toLong() and 0xffffffffL;val value=entries.int.toLong() and 0xffffffffL
                    val unit=when(type) {1,2,6,7->1;3,8->2;4,9,11,13->4;5,10,12->8;else->error("Unsupported TIFF type $type")}
                    require(n<=DngRawFile.MAX_FILE_BYTES/unit)
                    val start=if(n*unit<=4) offset+2+i*12+8 else value
                    require(start>=0 && start<=input.length()-n*unit) {"DNG tag is out of bounds"}
                    require(tags.put(id,Tag(type,n.toInt(),start))==null) {"Duplicate DNG tag $id"}
                }
                directories.add(tags)
                tags[330]?.let {tag->
                    require(tag.type in listOf(4,13) && tag.count<=16)
                    numbers(tag).forEach {visit(it.toLong())}
                }
                visit(entries.int.toLong() and 0xffffffffL)
            }
            visit(buffer(4,4).int.toLong() and 0xffffffffL)
        } catch(e: Throwable) {input.close();throw e}
    }
    fun bytes(offset: Long,size: Int): ByteArray {
        require(offset>=0 && size>=0 && offset<=input.length()-size) {"DNG data is truncated or out of bounds"}
        return ByteArray(size).also {input.seek(offset);input.readFully(it)}
    }
    fun bytes(tag: Tag)=bytes(tag.offset,tag.count)
    private fun buffer(offset: Long,size: Int)=ByteBuffer.wrap(bytes(offset,size)).order(order)
    fun text(tag: Tag): String {
        require(tag.type==2 && tag.count<=4096)
        return bytes(tag).toString(Charsets.UTF_8).trimEnd('\u0000')
    }
    fun numbers(tag: Tag): DoubleArray {
        require(tag.count<=65536) {"DNG numeric tag is too large"}
        val unit=when(tag.type) {1->1;3->2;4,9,11,13->4;5,10,12->8;else->error("Unsupported DNG numeric type ${tag.type}")}
        val b=buffer(tag.offset,tag.count*unit)
        return DoubleArray(tag.count) {
            val value=when(tag.type) {
                1->(b.get().toInt() and 255).toDouble()
                3->(b.short.toInt() and 65535).toDouble()
                4,13->(b.int.toLong() and 0xffffffffL).toDouble()
                9->b.int.toDouble();11->b.float.toDouble();12->b.double
                else->{
                    val numerator=if(tag.type==5) (b.int.toLong() and 0xffffffffL).toDouble() else b.int.toDouble()
                    val denominator=if(tag.type==5) (b.int.toLong() and 0xffffffffL).toDouble() else b.int.toDouble()
                    require(denominator!=0.0) {"Invalid DNG rational"};numerator/denominator
                }
            }
            require(value.isFinite()) {"Nonfinite DNG metadata"};value
        }
    }
    fun scalar(tags: Map<Int,Tag>,id: Int,default: Double?=null): Double =
        tags[id]?.let {numbers(it).single()} ?: requireNotNull(default) {"Missing DNG tag $id"}
    override fun close()=input.close()
}
