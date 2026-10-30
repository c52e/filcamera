package io.github.c52e.filcamera.rawvulkan

import io.github.c52e.filcamera.imaging.*
import io.github.c52e.filcamera.imaging.CalibratedWhiteBalance as W
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/** Standard, rectangular, three-color Bayer DNGs. File calibration only, never Camera2 metadata. */
internal object StandardDngReader {
    const val VERSION="standard-bayer-dng-v1"
    fun read(file: File,cancelled: ()->Boolean): DngRawFile.Decoded = DngTiff(file).use {tiff->
        fun checkActive() {if(cancelled()) throw InterruptedException()}
        checkActive()
        val root=tiff.directories.firstOrNull {it.containsKey(50706)} ?: error("Missing DNGVersion")
        val version=tiff.numbers(root.getValue(50706))
        require(version.size==4 && version[0]==1.0) {"Unsupported DNG version"}
        val raw=tiff.directories.singleOrNull {tiff.scalar(it,262,0.0)==32803.0 && tiff.scalar(it,254,0.0)==0.0}
            ?: error("A single undemosaiced Bayer DNG image is required")
        fun tag(id: Int)=raw[id] ?: root[id]
        fun values(id: Int,default: DoubleArray?=null)=tag(id)?.let(tiff::numbers) ?: requireNotNull(default) {"Missing DNG tag $id"}
        fun scalar(id: Int,default: Double?=null)=tag(id)?.let {tiff.numbers(it).single()} ?: requireNotNull(default) {"Missing DNG tag $id"}
        fun integer(value: Double): Int {require(value>=0 && value<=Int.MAX_VALUE && value==value.toInt().toDouble());return value.toInt()}
        val width=integer(tiff.scalar(raw,256));val height=integer(tiff.scalar(raw,257))
        require(RawLimits.supports(width,height) && width>=2 && height>=2) {"DNG exceeds supported image dimensions"}
        val bits=integer(tiff.scalar(raw,258));val compression=integer(tiff.scalar(raw,259))
        require(bits in listOf(8,10,12,14,16) && compression in listOf(1,7) && (compression!=7 || bits==16)) {
            "Supported DNG encodings: 8/10/12/14/16-bit uncompressed Bayer or 16-bit lossless JPEG Bayer"
        }
        require(tiff.scalar(raw,277,1.0)==1.0 && tiff.scalar(raw,339,1.0)==1.0 && tiff.scalar(raw,284,1.0)==1.0) {"Unsupported DNG sample layout"}
        require(scalar(50711,1.0)==1.0 && values(33421).contentEquals(doubleArrayOf(2.0,2.0))) {"Only rectangular 2x2 Bayer CFA is supported"}
        require(values(50710,doubleArrayOf(0.0,1.0,2.0)).contentEquals(doubleArrayOf(0.0,1.0,2.0))) {"Unsupported DNG color planes"}
        val cfa=values(33422).map(::integer)
        val rgb=intArrayOf(0,1,1,2)
        val pattern=BayerPattern.entries.singleOrNull {p->p.channels.map {rgb[it]}==cfa} ?: error("Unsupported DNG Bayer pattern")
        val area=values(50829,doubleArrayOf(0.0,0.0,height.toDouble(),width.toDouble())).map(::integer)
        require(area.size==4 && area[0]<area[2] && area[1]<area[3] && area[2]<=height && area[3]<=width) {"Invalid DNG active area"}
        val top=area[0];val left=area[1];val bottom=area[2];val right=area[3]
        val activeWidth=right-left;val activeHeight=bottom-top
        require(activeWidth>=2 && activeHeight>=2)
        require(values(50718,doubleArrayOf(1.0,1.0)).contentEquals(doubleArrayOf(1.0,1.0))) {"Non-square DNG pixels are not supported"}
        val cropOrigin=values(50719,doubleArrayOf(0.0,0.0)).map(::integer)
        val cropSize=values(50720,doubleArrayOf(activeWidth.toDouble(),activeHeight.toDouble())).map(::integer)
        require(cropOrigin.size==2 && cropSize.size==2 && cropSize.all {it>=2} && cropOrigin[0].toLong()+cropSize[0]<=activeWidth && cropOrigin[1].toLong()+cropSize[1]<=activeHeight) {"Invalid DNG default crop"}
        val orientation=integer(scalar(274,1.0))
        val rotation=when(orientation) {1->0;3->180;6->90;8->270;else->error("Mirrored DNG orientations are not supported")}
        val repeat=values(50713,doubleArrayOf(1.0,1.0)).map(::integer)
        require(repeat==listOf(1,1) || repeat==listOf(2,2)) {"Unsupported DNG black-level pattern"}
        val blackValues=values(50714,doubleArrayOf(0.0))
        require(blackValues.size==repeat[0]*repeat[1])
        for(id in listOf(50715,50716)) tag(id)?.let {require(tiff.numbers(it).all {v->v==0.0}) {"DNG row/column black-level deltas are not supported"}}
        // DNG black-repeat coordinates start at ActiveArea, RawFrame's pattern starts at the full array.
        val black=FloatArray(4) {site->blackValues[if(blackValues.size==1) 0 else ((site/2-top).mod(2))*2+(site%2-left).mod(2)].toFloat()}
        val white=scalar(50717,((1 shl bits)-1).toDouble()).toFloat()
        require(white<=65535 && white.isFinite() && black.all {it.isFinite() && it>=0 && it<white}) {"Invalid DNG black/white levels"}
        fun matrix(id: Int)=tag(id)?.let {W.Matrix(tiff.numbers(it).toList())}
        val matchingSignatures=(tag(50931)?.let(tiff::text).orEmpty()==tag(50932)?.let(tiff::text).orEmpty())
        fun temperature(value: Int): Double=when(value) {
            3,17->2850.0;24->3200.0;23->5000.0;1,4,9,18,20->5500.0;10,19,21->6500.0;11,22->7500.0
            else->error("Unsupported DNG calibration illuminant $value")
        }
        val color1=requireNotNull(matrix(50721)) {"Missing DNG ColorMatrix1"}
        val color2=matrix(50722)
        val dual=color2!=null && scalar(50778,0.0)!=scalar(50779,0.0)
        val first=W.Anchor(if(dual) temperature(integer(scalar(50778))) else 5000.0,color1,
            if(matchingSignatures) matrix(50723) ?: W.identity else W.identity,matrix(50964))
        val second=if(dual) W.Anchor(temperature(integer(scalar(50779))),requireNotNull(color2),
            if(matchingSignatures) matrix(50724) ?: W.identity else W.identity,matrix(50965)) else null
        val color=W.solveDng(first,second,tag(50728)?.let {tiff.numbers(it).toList()},tag(50729)?.let {tiff.numbers(it).toList()},values(50727,doubleArrayOf(1.0,1.0,1.0)).toList())
        // Invert the existing shader's sRGB -> Rec2020 -> ProPhoto bridge. DNG's PCS is XYZ D50.
        val rec2020ToProPhoto=W.Matrix(listOf(.8135929278602886,.05952871161117486,.12705749581445122,
            .04234095353045031,.9233873261332325,.03423093930423864,-.003466230634951545,.016734144578857215,.9870063956795253))
        val xyzToProPhoto=W.Matrix(listOf(1.3459433,-.2556075,-.0511118,-.5445989,1.5081673,.0205351,0.0,0.0,1.2118128))
        val bridge=rec2020ToProPhoto*W.Matrix(ColorMath.srgbTo2020.map(Float::toDouble))
        val exposure=scalar(50730,0.0);require(exposure in -10.0..10.0)
        val cameraToSrgb=(bridge.inverse()*xyzToProPhoto*color.balancedCameraToD50).scale(2.0.pow(exposure))
        val opcodes=DngOpcodes.read(tiff,raw,root,width,height,left,top,activeWidth,activeHeight,pattern)
        val pixels=readPixels(tiff,raw,width,height,bits,compression,cancelled)
        tag(50712)?.let {tag->
            val linear=tiff.numbers(tag)
            require(linear.isNotEmpty() && linear.all {it>=0 && it<=65535 && it==it.toInt().toDouble()}) {"Unsupported DNG linearization table"}
            val b=ByteBuffer.wrap(pixels).order(ByteOrder.LITTLE_ENDIAN)
            for(i in 0 until width*height) {
                if(i%width==0) checkActive()
                val value=b.getShort(i*2).toInt() and 65535
                require(value<linear.size) {"DNG sample exceeds linearization table"};b.putShort(i*2,linear[value].toInt().toShort())
            }
        }
        val frame=RawFrame(width,height,width*2,2,ByteBuffer.wrap(pixels),pattern,black,white,
            color.gains.map(Double::toFloat).toFloatArray(),cameraToSrgb.values.map(Double::toFloat).toFloatArray(),left,top,right,bottom,opcodes.shading)
        frame.validate();checkActive()
        val record=JSONObject().put("rawImport",VERSION).put("developer",VulkanRawDeveloper.ID)
            .put("dngCrop",JSONArray(cropOrigin+cropSize)).put("orientation",orientation)
            .put("skippedOptionalOpcodes",JSONArray(opcodes.skipped)).put("baselineExposure",exposure)
            .put("whiteXYZ",JSONArray(color.whiteXYZ)).put("gainMapApplied",opcodes.shading!=null)
        DngRawFile.Decoded(frame,rotation,record)
    }

    private fun readPixels(tiff: DngTiff,raw: Map<Int,DngTiff.Tag>,width: Int,height: Int,bits: Int,compression: Int,cancelled: ()->Boolean): ByteArray {
        fun ints(id: Int)=tiff.numbers(raw.getValue(id)).also {a->require(a.all {it>=0 && it==it.toLong().toDouble()})}.map(Double::toLong)
        val tiled=raw.containsKey(324);val offsets=ints(if(tiled) 324 else 273);val sizes=ints(if(tiled) 325 else 279)
        val tileWidth=if(tiled) tiff.scalar(raw,322).toInt() else width
        val tileHeight=if(tiled) tiff.scalar(raw,323).toInt() else minOf(tiff.scalar(raw,278,height.toDouble()).toInt(),height)
        require(tileWidth in 1..16384 && tileHeight in 1..16384)
        val across=(width+tileWidth-1)/tileWidth;val down=(height+tileHeight-1)/tileHeight
        require(offsets.size==sizes.size && offsets.size==across*down && offsets.size<=16384) {"Invalid DNG block count"}
        var end=0L
        for(i in offsets.indices.sortedBy {offsets[it]}) {
            require(sizes[i] in 1..32L*1024*1024 && offsets[i]>=end) {"Overlapping or oversized DNG blocks"};end=offsets[i]+sizes[i]
            require(end<=DngRawFile.MAX_FILE_BYTES)
        }
        require(tiff.scalar(raw,266,1.0)==1.0 && tiff.scalar(raw,317,1.0)==1.0) {"Unsupported DNG bit order or predictor"}
        val output=ByteArray(width*height*2)
        for(i in offsets.indices) {
            if(cancelled()) throw InterruptedException()
            val x=i%across*tileWidth;val y=i/across*tileHeight
            val blockHeight=if(tiled) tileHeight else minOf(tileHeight,height-y)
            require(tileWidth.toLong()*blockHeight*2<=32L*1024*1024)
            val data=tiff.bytes(offsets[i],sizes[i].toInt())
            val block=if(compression==7) DngRawFile.decodeLosslessJpeg(data,tileWidth,blockHeight) else {
                val stride=(tileWidth*bits+7)/8
                require(data.size==stride*blockHeight) {"Incorrect DNG uncompressed block length"}
                val decoded=ByteArray(tileWidth*blockHeight*2)
                for(row in 0 until blockHeight) for(column in 0 until tileWidth) {
                    val value=if(bits==16) {
                        val p=row*stride+column*2
                        if(tiff.order==ByteOrder.LITTLE_ENDIAN) (data[p].toInt() and 255) or ((data[p+1].toInt() and 255) shl 8)
                        else ((data[p].toInt() and 255) shl 8) or (data[p+1].toInt() and 255)
                    } else {
                        var value=0
                        for(bit in 0 until bits) {val p=column*bits+bit;value=(value shl 1) or ((data[row*stride+p/8].toInt() ushr (7-p%8)) and 1)}
                        value
                    }
                    val p=(row*tileWidth+column)*2;decoded[p]=value.toByte();decoded[p+1]=(value ushr 8).toByte()
                }
                decoded
            }
            require(block.size==tileWidth*blockHeight*2)
            for(row in 0 until minOf(blockHeight,height-y)) {
                if(cancelled()) throw InterruptedException()
                System.arraycopy(block,row*tileWidth*2,output,((y+row)*width+x)*2,minOf(tileWidth,width-x)*2)
            }
        }
        return output
    }
}
