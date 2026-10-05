package io.github.c52e.filcamera.rawvulkan

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

import io.github.c52e.filcamera.imaging.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Original, bounded reader for this app's Camera2 16-bit Bayer DNGs (uncompressed or lossless JPEG).
 * Standard TIFF/DNG tags remain intact. ImageDescription carries the exact capture-time
 * calibration used by our developer; no metadata from the receiving phone is consulted.
 * Other DNG encodings/calibration revisions fail explicitly, never guessed or ISP-decoded.
 */
object DngRawFile {
    init {System.loadLibrary("rawvulkan")}
    private external fun decodeJpeg(bytes: ByteArray,width: Int,height: Int): ByteArray
    const val FORMAT="filmcamera-raw-v1"
    const val MAX_FILE_BYTES=128L*1024*1024
    private const val MAX_DESCRIPTION=4*1024*1024
    data class Decoded(val frame: RawFrame,val rotation: Int,val record: JSONObject)

    fun description(frame: RawFrame,rotation: Int,record: JSONObject): String {
        frame.validate();require(rotation in listOf(0,90,180,270))
        val j=JSONObject().put("format",FORMAT).put("developer",VulkanRawDeveloper.ID)
            .put("width",frame.width).put("height",frame.height).put("rotation",rotation)
            .put("pattern",frame.pattern.name).put("black",JSONArray(frame.black.toList()))
            .put("white",frame.white).put("gains",JSONArray(frame.gains.toList()))
            .put("cameraToSrgb",JSONArray(frame.cameraToSrgb.toList()))
            .put("crop",JSONArray(listOf(frame.left,frame.top,frame.right,frame.bottom)))
            .put("record",record)
        frame.shading?.let {j.put("shading",JSONObject().put("columns",it.columns).put("rows",it.rows)
            .put("gains",JSONArray(it.gains.toList())))}
        return j.toString().also {require(it.toByteArray(Charsets.UTF_8).size<MAX_DESCRIPTION)}
    }

    fun read(file: File,cancelled: ()->Boolean={Thread.currentThread().isInterrupted}): Decoded = RandomAccessFile(file,"r").use { f ->
        require(f.length() in 8..MAX_FILE_BYTES) {tr(Texts.string.unsupported_dng_file_size)}
        val marker=f.readUnsignedShort()
        val order=when(marker) {0x4949->ByteOrder.LITTLE_ENDIAN;0x4d4d->ByteOrder.BIG_ENDIAN;else->error(tr(Texts.string.not_a_tiff_dng_file))}
        fun bytes(offset: Long,size: Int): ByteArray {
            require(offset>=0 && size>=0 && offset<=f.length()-size) {tr(Texts.string.dng_data_is_out_of_bounds_or_truncated)}
            val a=ByteArray(size);f.seek(offset);f.readFully(a);return a
        }
        fun buffer(offset: Long,size: Int)=ByteBuffer.wrap(bytes(offset,size)).order(order)
        fun u32(offset: Long)=buffer(offset,4).int.toLong() and 0xffffffffL
        require((buffer(2,2).short.toInt() and 65535)==42) {tr(Texts.string.bigtiff_is_not_supported)}
        data class Tag(val type: Int,val count: Int,val offset: Long)
        val directories=ArrayList<Map<Int,Tag>>();val seen=HashSet<Long>()
        fun visit(offset: Long) {
            if(offset==0L) return
            require(seen.add(offset) && seen.size<=16) {tr(Texts.string.cyclic_or_excessive_dng_ifds)}
            val count=buffer(offset,2).short.toInt() and 65535
            require(count<=1024)
            val entries=buffer(offset+2,count*12+4);val tags=HashMap<Int,Tag>()
            repeat(count) {i->
                val id=entries.short.toInt() and 65535;val type=entries.short.toInt() and 65535
                val n=entries.int.toLong() and 0xffffffffL;val value=entries.int.toLong() and 0xffffffffL
                val unit=when(type) {1,2,6,7->1;3,8->2;4,9,11,13->4;5,10,12->8;else->error(tr(Texts.string.unsupported_tiff_type_value, type))}
                require(n<=MAX_FILE_BYTES/unit)
                val start=if(n*unit<=4) offset+2+i*12+8 else value
                require(start>=0 && start<=f.length()-n*unit) {tr(Texts.string.dng_tag_is_out_of_bounds)}
                require(tags.put(id,Tag(type,n.toInt(),start))==null) {tr(Texts.string.duplicate_dng_tag)}
            }
            directories.add(tags)
            tags[330]?.let {sub->
                require(sub.type in listOf(4,13) && sub.count<=16)
                repeat(sub.count) {visit(u32(sub.offset+4L*it))}
            }
            visit(entries.int.toLong() and 0xffffffffL)
        }
        visit(u32(4))
        fun values(tag: Tag): LongArray {
            require(tag.count<=16384 && tag.type in listOf(1,3,4,13))
            val b=buffer(tag.offset,tag.count*when(tag.type) {1->1;3->2;else->4})
            return LongArray(tag.count) {when(tag.type) {1->b.get().toLong() and 255;3->b.short.toLong() and 65535;else->b.int.toLong() and 0xffffffffL}}
        }
        fun scalar(tags: Map<Int,Tag>,id: Int,default: Long?=null): Long {
            val t=tags[id] ?: return requireNotNull(default) {tr(Texts.string.missing_dng_tag_value, id)}
            require(t.count==1);return values(t)[0]
        }
        require(directories.any {it.containsKey(50706)}) {tr(Texts.string.missing_dngversion)}
        val raw=directories.singleOrNull {scalar(it,262,0)==32803L}
            ?: error(tr(Texts.string.an_undemosaiced_bayer_dng_image_is_required))
        val compression=scalar(raw,259)
        require(compression in listOf(1L,7L) && scalar(raw,258)==16L && scalar(raw,277,1)==1L && scalar(raw,339,1)==1L) {
            tr(Texts.string.only_this_app_s_16_bit_bayer_dngs_are_supported_uncompressed_or_l)
        }
        require(scalar(raw,284,1)==1L) {tr(Texts.string.chunky_planar_dng_layout_is_not_supported)}
        val w=scalar(raw,256);val h=scalar(raw,257)
        require(w in 2..16384 && h in 2..16384 && w*h<=16_000_000) {tr(Texts.string.dng_exceeds_the_16_megapixel_limit)}
        val description=directories.mapNotNull {it[270]}.map {t->
            require(t.type==2 && t.count in 1..MAX_DESCRIPTION)
            bytes(t.offset,t.count).toString(Charsets.UTF_8).trimEnd('\u0000')
        }.firstOrNull {it.startsWith("{") && runCatching {JSONObject(it).optString("format")==FORMAT}.getOrDefault(false)}
            ?: error(tr(Texts.string.a_dng_saved_by_this_app_is_required_embedded_development_record_m))
        val j=JSONObject(description)
        require(j.getString("developer")==VulkanRawDeveloper.ID) {tr(Texts.string.unsupported_dng_developer_version)}
        require(j.getInt("width").toLong()==w && j.getInt("height").toLong()==h)
        val rotation=j.getInt("rotation");require(rotation in listOf(0,90,180,270))
        val orientation=directories.firstNotNullOfOrNull {it[274]?.let {t->values(t).single()} } ?: 1L
        require(orientation==when(rotation) {90->6L;180->3L;270->8L;else->1L}) {tr(Texts.string.dng_orientation_does_not_match_its_development_record)}
        val pattern=BayerPattern.valueOf(j.getString("pattern"))
        require(values(requireNotNull(raw[33421])).contentEquals(longArrayOf(2,2)))
        val cfa=values(requireNotNull(raw[33422]));val rgb=longArrayOf(0,1,1,2)
        require(cfa.contentEquals(LongArray(4) {rgb[pattern.channels[it]]})) {tr(Texts.string.dng_cfa_does_not_match_its_development_record)}
        fun floats(a: JSONArray,count: Int): FloatArray {
            require(a.length()==count)
            return FloatArray(count) {a.getDouble(it).toFloat().also {v->require(v.isFinite())}}
        }
        val shading=j.optJSONObject("shading")?.let {
            val cols=it.getInt("columns");val rows=it.getInt("rows");require(cols in 1..256 && rows in 1..256)
            ShadingGrid(cols,rows,floats(it.getJSONArray("gains"),cols*rows*4))
        }
        val tiled=raw.containsKey(324)
        val offsets=values(requireNotNull(raw[if(tiled) 324 else 273]))
        val sizes=values(requireNotNull(raw[if(tiled) 325 else 279]))
        val tileWidth=if(tiled) scalar(raw,322) else w
        val tileHeight=if(tiled) scalar(raw,323) else minOf(scalar(raw,278),h)
        require(tileWidth in 1..16384 && tileHeight in 1..16384)
        val across=(w+tileWidth-1)/tileWidth;val down=(h+tileHeight-1)/tileHeight
        require(offsets.size==sizes.size && offsets.size.toLong()==across*down && offsets.size<=16384)
        var previousEnd=0L
        for(i in offsets.indices.sortedBy {offsets[it]}) {
            require(sizes[i]>0 && offsets[i]>=previousEnd && offsets[i]<=f.length()-sizes[i]) {tr(Texts.string.dng_data_blocks_overlap_or_are_out_of_bounds)}
            previousEnd=offsets[i]+sizes[i]
        }
        val pixels=ByteArray((w*h*2).toInt())
        for(i in offsets.indices) {
            if(cancelled()) throw InterruptedException()
            val x=(i%across)*tileWidth;val y=(i/across)*tileHeight
            val blockHeight=if(tiled) tileHeight else minOf(tileHeight,h-y)
            val n=tileWidth*blockHeight*2
            require(n<=32L*1024*1024 && sizes[i]<=32L*1024*1024)
            val block=if(compression==7L) decodeJpeg(bytes(offsets[i],sizes[i].toInt()),tileWidth.toInt(),blockHeight.toInt()) else {
                require(sizes[i]==n) {tr(Texts.string.incorrect_dng_raw_strip_length)}
                bytes(offsets[i],n.toInt()).also {b->
                    if(order==ByteOrder.BIG_ENDIAN) for(k in b.indices step 2) {val a=b[k];b[k]=b[k+1];b[k+1]=a}
                }
            }
            require(block.size.toLong()==n)
            val copyWidth=minOf(tileWidth,w-x).toInt()*2
            for(row in 0 until minOf(blockHeight,h-y).toInt())
                System.arraycopy(block,row*tileWidth.toInt()*2,pixels,((y+row)*w+x).toInt()*2,copyWidth)
        }
        val crop=j.getJSONArray("crop");require(crop.length()==4)
        val frame=RawFrame(w.toInt(),h.toInt(),w.toInt()*2,2,ByteBuffer.wrap(pixels),pattern,
            floats(j.getJSONArray("black"),4),j.getDouble("white").toFloat(),floats(j.getJSONArray("gains"),4),
            floats(j.getJSONArray("cameraToSrgb"),9),crop.getInt(0),crop.getInt(1),crop.getInt(2),crop.getInt(3),shading)
        frame.validate()
        Decoded(frame,rotation,j.getJSONObject("record"))
    }
}
