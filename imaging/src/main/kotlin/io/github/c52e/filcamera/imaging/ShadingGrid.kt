package io.github.c52e.filcamera.imaging

import kotlin.math.min

/** Camera2 row-major R/G-even/G-odd/B residual gains over the pre-correction active array.
 * Grid endpoints coincide with the first/last pixel centers, independent of CFA parity.
 */
class ShadingGrid(val columns: Int, val rows: Int, gains: FloatArray) {
    val gains: FloatArray = gains.copyOf()
    init {
        require(columns in 1..256 && rows in 1..256 && gains.size == columns*rows*4)
        require(gains.all { it.isFinite() && it >= 1f })
    }
    fun gain(channel: Int, x: Float, y: Float): Float {
        val gx=x.coerceIn(0f,1f)*(columns-1); val gy=y.coerceIn(0f,1f)*(rows-1)
        val ix=gx.toInt(); val iy=gy.toInt()
        val jx=min(ix+1,columns-1); val jy=min(iy+1,rows-1)
        val fx=gx-ix; val fy=gy-iy
        val a=gains[(iy*columns+ix)*4+channel]; val b=gains[(iy*columns+jx)*4+channel]
        val c=gains[(jy*columns+ix)*4+channel]; val d=gains[(jy*columns+jx)*4+channel]
        return (a+(b-a)*fx)*(1-fy)+(c+(d-c)*fx)*fy
    }
}

/** Cache horizontal interpolation for two map rows, for both sensor row parities.
 * Per-pixel work then becomes a single vertical blend; scratch is O(sensor width).
 */
internal class ShadingRows(private val grid: ShadingGrid, private val width: Int,
                           private val height: Int, private val left: Int,
                           private val top: Int, private val pattern: BayerPattern) {
    private val cache=Array(2){FloatArray(width*2)}
    private val ids=intArrayOf(-1,-1)
    private fun horizontal(row: Int): FloatArray {
        val slot=row%2; val out=cache[slot]
        if(ids[slot]!=row) {
            for(parity in 0..1) for(x in 0 until width) {
                val gx=x.toFloat()/(width-1)*(grid.columns-1)
                val ix=gx.toInt(); val jx=min(ix+1,grid.columns-1); val f=gx-ix
                val channel=pattern.channels[parity*2+((left+x) and 1)]
                val a=grid.gains[(row*grid.columns+ix)*4+channel]
                val b=grid.gains[(row*grid.columns+jx)*4+channel]
                out[parity*width+x]=a+(b-a)*f
            }
            ids[slot]=row
        }
        return out
    }
    fun fill(target: FloatArray,y: Int) {
        val gy=y.toFloat()/(height-1)*(grid.rows-1)
        val iy=gy.toInt(); val jy=min(iy+1,grid.rows-1); val fy=gy-iy
        val a=horizontal(iy); val b=horizontal(jy); val offset=((y+top) and 1)*width
        for(x in 0 until width) target[x]=a[offset+x]*(1-fy)+b[offset+x]*fy
    }
}
