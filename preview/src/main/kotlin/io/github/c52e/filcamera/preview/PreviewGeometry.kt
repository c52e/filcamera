package io.github.c52e.filcamera.preview

import kotlin.math.hypot

/** Camera2 supplies sensor orientation and front-camera mirroring in the OES producer matrix.
 * Only the inverse display rotation belongs on the consumer side. Photo orientation is separate.
 */
object PreviewGeometry {
    data class Point(val x: Float,val y: Float)

    /** Invert the displayed quad, then apply exactly the texture transform used to draw it.
     * OES producer transforms include the buffer's top-to-bottom flip. Result coordinates
     * are normalized in the original preview buffer, not in the rotated display.
     */
    fun tap(x: Float,y: Float,width: Int,height: Int,matrix: FloatArray,
            sourceWidth: Int,sourceHeight: Int,displayDegrees: Int): Point? {
        if(width<=0 || height<=0 || !x.isFinite() || !y.isFinite()) return null
        val r=rotation(displayDegrees)
        val aspect=aspect(matrix,sourceWidth,sourceHeight,r)
        val screen=width.toFloat()/height
        val sx=minOf(1f,aspect/screen);val sy=minOf(1f,screen/aspect)
        val px=(2f*x/width-1f)/sx;val py=(1f-2f*y/height)/sy
        if(px !in -1f..1f || py !in -1f..1f) return null
        val qx=(r.cosine*px-r.sine*py+1f)*.5f
        val qy=(r.sine*px+r.cosine*py+1f)*.5f
        return Point((matrix[0]*qx+matrix[4]*qy+matrix[12]).coerceIn(0f,1f),
            (matrix[1]*qx+matrix[5]*qy+matrix[13]).coerceIn(0f,1f))
    }
    /** Right-compose a normalized centered crop, preserving the producer's rotation and mirror. */
    fun centerCrop(producer: FloatArray,scale: Float,out: FloatArray) {
        require(producer.size==16 && out.size==16 && producer!==out && scale.isFinite() && scale>0f && scale<=1f)
        producer.copyInto(out)
        val offset=(1f-scale)*.5f
        for(row in 0..3) {
            out[12+row]=producer[12+row]+offset*(producer[row]+producer[4+row])
            out[row]=producer[row]*scale;out[4+row]=producer[4+row]*scale
        }
    }
    data class Rotation(val cosine: Float, val sine: Float)
    private val rotations = arrayOf(Rotation(1f,0f),Rotation(0f,-1f),Rotation(-1f,0f),Rotation(0f,1f))

    fun rotation(displayDegrees: Int): Rotation {
        require(displayDegrees % 90 == 0)
        return rotations[((displayDegrees / 90) % 4 + 4) % 4]
    }

    /** Pixel-space lengths of the two axes of producerMatrix * inverseDisplayRotation.
     * Includes producer crop, quarter-turns and flips without applying them a second time.
     */
    fun aspect(matrix: FloatArray, sourceWidth: Int, sourceHeight: Int, rotation: Rotation): Float {
        val c=rotation.cosine; val s=rotation.sine
        val horizontal=hypot((matrix[0]*c+matrix[4]*s)*sourceWidth,(matrix[1]*c+matrix[5]*s)*sourceHeight)
        val vertical=hypot((-matrix[0]*s+matrix[4]*c)*sourceWidth,(-matrix[1]*s+matrix[5]*c)*sourceHeight)
        return if (horizontal > 0f && vertical > 0f) horizontal/vertical else 1f
    }
}
