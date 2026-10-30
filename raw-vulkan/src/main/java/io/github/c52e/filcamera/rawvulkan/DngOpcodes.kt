package io.github.c52e.filcamera.rawvulkan

import io.github.c52e.filcamera.imaging.BayerPattern
import io.github.c52e.filcamera.imaging.ShadingGrid
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** DNG GainMap opcode on the four Bayer sites; unsupported required opcodes fail explicitly. */
internal object DngOpcodes {
    data class Result(val shading: ShadingGrid?,val skipped: List<String>)
    private data class GainMap(val site: Int,val rows: Int,val columns: Int,val spacingY: Double,val spacingX: Double,
                               val originY: Double,val originX: Double,val gains: FloatArray,val optional: Boolean)
    fun read(tiff: DngTiff,raw: Map<Int,DngTiff.Tag>,root: Map<Int,DngTiff.Tag>,width: Int,height: Int,
             left: Int,top: Int,activeWidth: Int,activeHeight: Int,pattern: BayerPattern): Result {
        val skipped=ArrayList<String>();val maps=ArrayList<GainMap>()
        fun skip(opcode: Long,stage: Int,optional: Boolean,reason: String) {
            require(optional) {"Unsupported required DNG opcode $opcode in stage $stage: $reason"}
            skipped+="$stage:$opcode"
        }
        for((stage,id) in listOf(51008,51009,51022).withIndex()) {
            val tag=raw[id] ?: root[id] ?: continue
            require(tag.type==7 && tag.count in 4..4*1024*1024) {"Invalid DNG opcode list"}
            val b=ByteBuffer.wrap(tiff.bytes(tag)).order(ByteOrder.BIG_ENDIAN)
            fun uint()=b.int.toLong() and 0xffffffffL
            val count=uint();require(count<=256)
            repeat(count.toInt()) {
                require(b.remaining()>=16)
                val opcode=uint();val version=uint();val flags=uint();val size=uint()
                require(size<=b.remaining() && flags and 0xfffffffcL==0L) {"Invalid DNG opcode header"}
                val payload=ByteArray(size.toInt());b.get(payload)
                val optional=flags and 1L!=0L
                val supported=stage==1 && opcode==9L && version<=0x01030000L
                if(!supported) {
                    skip(opcode,stage+1,optional,"opcode or version");return@repeat
                }
                val p=ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                require(p.remaining()>=76)
                val area=IntArray(8) {p.int}
                require(area[0]>=0 && area[1]>=0 && area[2]>area[0] && area[3]>area[1] &&
                    area[4]>=0 && area[5]>0 && area[6]>0 && area[7]>0) {"Invalid DNG GainMap area"}
                val rows=p.int;val columns=p.int
                require(rows>0 && columns>0) {"Invalid DNG GainMap dimensions"}
                val sy=p.double;val sx=p.double;val oy=p.double;val ox=p.double;val planes=p.int
                require(planes>0 && sy.isFinite() && sx.isFinite() && sy>0 && sx>0 && oy.isFinite() && ox.isFinite()) {"Invalid DNG GainMap grid"}
                val points=rows.toLong()*columns
                require(points<=p.remaining().toLong()/4/planes) {"Truncated DNG GainMap samples"}
                val samples=(points*planes).toInt()
                require(p.remaining()==samples*4) {"Incorrect DNG GainMap sample length"}
                val gains=FloatArray(samples) {p.float.also {g->require(g.isFinite() && g>0) {"Invalid DNG GainMap gain"}}}
                val usable=left==0 && top==0 && activeWidth==width && activeHeight==height &&
                    area[0] in 0..1 && area[1] in 0..1 && area[2]==height && area[3]==width &&
                    area[4]==0 && area[5]==1 && area[6]==2 && area[7]==2 &&
                    rows<=256 && columns<=256 && planes==1 && gains.all {it<=64}
                if(!usable) {skip(opcode,stage+1,optional,"GainMap layout or range");return@repeat}
                maps+=GainMap(area[0]*2+area[1],rows,columns,sy,sx,oy,ox,gains,optional)
            }
            require(!b.hasRemaining()) {"Trailing DNG opcode bytes"}
        }
        if(maps.isEmpty()) return Result(null,skipped)
        fun skipMaps(reason: String): Result {
            // Never apply only part of a correction group. Required maps must remain executable.
            require(maps.all {it.optional}) {"Unsupported required DNG GainMap combination: $reason"}
            maps.forEach {skipped+="2:9"}
            return Result(null,skipped)
        }
        if(maps.size!=4 || maps.map {it.site}.toSet().size!=4) return skipMaps("four Bayer sites are required")
        val first=maps.first()
        if(!maps.all {it.rows==first.rows && it.columns==first.columns && it.spacingX==first.spacingX && it.spacingY==first.spacingY && it.originX==first.originX && it.originY==first.originY}) return skipMaps("grids differ")
        val gains=FloatArray(first.rows*first.columns*4)
        maps.forEach {map->map.gains.forEachIndexed {i,g->gains[i*4+pattern.channels[map.site]]=g}}
        // GainMap coordinates use pixel centers divided by the stage-2 image bounds.
        val xs=if(first.columns==1) 1.0 else (width-1.0)/width/first.spacingX/(first.columns-1)
        val ys=if(first.rows==1) 1.0 else (height-1.0)/height/first.spacingY/(first.rows-1)
        val xo=if(first.columns==1) 0.0 else (.5/width-first.originX)/first.spacingX/(first.columns-1)
        val yo=if(first.rows==1) 0.0 else (.5/height-first.originY)/first.spacingY/(first.rows-1)
        if(!xs.toFloat().isFinite() || !ys.toFloat().isFinite() || xs.toFloat()<=0 || ys.toFloat()<=0 ||
            !xo.toFloat().isFinite() || !yo.toFloat().isFinite()) return skipMaps("coordinate range")
        return Result(ShadingGrid(first.columns,first.rows,gains,xs.toFloat(),ys.toFloat(),xo.toFloat(),yo.toFloat()),skipped)
    }
}
