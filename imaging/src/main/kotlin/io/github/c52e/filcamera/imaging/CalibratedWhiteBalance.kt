package io.github.c52e.filcamera.imaging

import java.security.MessageDigest
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.sqrt

/** Double-precision, sensor-specific DNG dual-illuminant white balance. No image processing here. */
object CalibratedWhiteBalance {
    const val VERSION = "dng-dual-kelvin-v1"
    class Matrix(values: List<Double>) {
        val values: List<Double> = java.util.Collections.unmodifiableList(values.toList())
        init { require(values.size == 9 && values.all(Double::isFinite)) }
        operator fun times(b: Matrix) = Matrix(List(9) { i -> (0..2).sumOf { k -> values[i/3*3+k]*b.values[k*3+i%3] } })
        fun apply(v: List<Double>) = List(3) { r -> (0..2).sumOf { c -> values[r*3+c]*v[c] } }
        fun scale(v: Double) = Matrix(values.map { it*v })
        fun inverse(): Matrix {
            val a=values
            val cof=listOf(a[4]*a[8]-a[5]*a[7],a[2]*a[7]-a[1]*a[8],a[1]*a[5]-a[2]*a[4],
                a[5]*a[6]-a[3]*a[8],a[0]*a[8]-a[2]*a[6],a[2]*a[3]-a[0]*a[5],
                a[3]*a[7]-a[4]*a[6],a[1]*a[6]-a[0]*a[7],a[0]*a[4]-a[1]*a[3])
            val d=a[0]*cof[0]+a[1]*cof[3]+a[2]*cof[6]
            require(d.isFinite() && abs(d)>1e-10) { "Singular white-balance calibration matrix" }
            return Matrix(cof.map {it/d})
        }
        fun blend(b: Matrix,w: Double) = Matrix(values.zip(b.values) { a,c -> w*a+(1-w)*c })
    }
    fun diagonal(v: List<Double>) = Matrix(List(9) { if(it/3==it%3) v[it/3] else 0.0 })
    val identity get() = diagonal(listOf(1.0,1.0,1.0))
    private val d50=listOf(0.3457/0.3585,1.0,(1-0.3457-0.3585)/0.3585)
    private val d65=listOf(0.3127/0.3290,1.0,(1-0.3127-0.3290)/0.3290)
    private val bradford=Matrix(listOf(.8951,.2664,-.1614,-.7502,1.7135,.0367,.0389,-.0685,1.0296))
    private val xyzToSrgb=Matrix(listOf(3.2409699419045226,-1.537383177570094,-.4986107602930034,
        -.9692436362808796,1.8759675015077202,.04155505740717559,.05563007969699366,-.20397695888897652,1.0569715142428786))
    private fun adapt(from: List<Double>,to: List<Double>): Matrix {
        val a=bradford.apply(from);val b=bradford.apply(to)
        require(a.all {it>0} && b.all {it>0})
        return bradford.inverse()*diagonal(a.zip(b) {x,y->y/x})*bradford
    }

    data class Anchor(val kelvin: Double,val color: Matrix,val calibration: Matrix,val forward: Matrix?) {
        init {
            require(kelvin.isFinite() && kelvin>0)
            color.inverse();calibration.inverse()
            forward?.let {it.inverse();require(it.apply(listOf(1.0,1.0,1.0)).all {v->v>0})}
        }
        internal fun normalizedForward() = forward?.let {m ->
            diagonal(d50.zip(m.apply(listOf(1.0,1.0,1.0))) {a,b->a/b})*m
        }
    }
    class Calibration(val first: Anchor,val second: Anchor) {
        init {require(abs(first.kelvin-second.kelvin)>1.0)}
        val hash: String by lazy {
            val d=MessageDigest.getInstance("SHA-256");d.update(VERSION.toByteArray(Charsets.UTF_8))
            listOf(first,second).sortedBy {it.kelvin}.forEach {a ->
                val numbers=listOf(a.kelvin)+a.color.values+a.calibration.values
                d.update(ByteBuffer.allocate(numbers.size*8).apply {numbers.forEach(::putDouble)}.array())
                d.update(if(a.forward==null) 0.toByte() else 1.toByte())
                a.forward?.values?.forEach {d.update(ByteBuffer.allocate(8).putDouble(it).array())}
            }
            d.digest().joinToString("") {"%02x".format(it.toInt() and 255)}
        }
    }
    data class Solution(val gains: List<Double>,val transform: Matrix,val neutral: List<Double>,val whiteXYZ: List<Double>)

    fun solve(calibration: Calibration,kelvin: Int): Solution {
        require(kelvin in 2000..10000)
        val a=calibration.first;val b=calibration.second
        val white=whiteXYZ(kelvin)
        // The SDK derives interpolation CCT from xy; Robertson's inverse is not exactly
        // the original slider Kelvin between table knots. Preserve that small distinction.
        val cct=correlatedTemperature(white)
        val w=((1.0/cct-1.0/b.kelvin)/(1.0/a.kelvin-1.0/b.kelvin)).coerceIn(0.0,1.0)
        val cc=a.calibration.blend(b.calibration,w)
        // DNG SDK composes each endpoint's individual calibration before interpolating.
        val xyzToCamera=(a.calibration*a.color).blend(b.calibration*b.color,w)
        val rawNeutral=xyzToCamera.apply(white)
        require(rawNeutral.all {it.isFinite() && it>0}) {"Calibration produced an invalid sensor white point"}
        val scale=rawNeutral.max()
        val neutral=rawNeutral.map {it/scale}
        val gains=neutral.map {1/it}
        require(gains.all {it.isFinite() && it in 1.0..16.0}) {"Temperature gains exceed the Camera2 range"}
        val f1=a.normalizedForward();val f2=b.normalizedForward()
        val fm=if(f1!=null && f2!=null) f1.blend(f2,w) else f1 ?: f2
        val toD50=if(fm!=null) {
            val inverseCalibration=cc.inverse()
            val reference=inverseCalibration.apply(neutral)
            require(reference.all {it>0 && it.isFinite()})
            fm*diagonal(reference.map {1/it})*inverseCalibration
        } else adapt(white,d50)*xyzToCamera.inverse().scale(scale)
        // Camera2 applies gains first. Cancel them in the matrix, preserving the complete DNG transform.
        val matrix=xyzToSrgb*adapt(d50,d65)*toD50*diagonal(neutral)
        matrix.inverse()
        return Solution(listOf(gains[0],gains[1],gains[1],gains[2]),matrix,neutral,white)
    }

    /** Robertson iso-temperature table, zero tint; DNG SDK 1.7.1 convention, 2000..10000 K.
     * Table source: Wyszecki & Stiles, Color Science, 2nd ed., p.228. See licenses/DNG-SDK.txt.
     * Copyright 2006-2019 Adobe Systems Incorporated. All Rights Reserved.
     */
    fun whiteXYZ(kelvin: Int): List<Double> {
        require(kelvin in 2000..10000)
        val r=1e6/kelvin
        val i=((r-100)/25).toInt().coerceIn(0,15)
        val w=(100+(i+1)*25-r)/25
        val u=uv[i*2]*w+uv[(i+1)*2]*(1-w)
        val v=uv[i*2+1]*w+uv[(i+1)*2+1]*(1-w)
        val denominator=u-4*v+2
        val x=1.5*u/denominator;val y=v/denominator
        return listOf(x/y,1.0,(1-x-y)/y)
    }
    private val uv=doubleArrayOf(.19032,.29326,.19462,.30141,.19962,.30921,.20525,.31647,
        .21142,.32312,.21807,.32909,.22511,.33439,.23247,.33904,.24010,.34308,
        .24702,.34655,.25591,.34951,.26400,.35200,.27218,.35407,.28039,.35577,
        .28863,.35714,.29685,.35823,.30505,.35907)

    private fun correlatedTemperature(xyz: List<Double>): Double {
        val sum=xyz.sum();val x=xyz[0]/sum;val y=xyz[1]/sum
        val u=2*x/(1.5-x+6*y);val v=3*y/(1.5-x+6*y)
        val values=doubleArrayOf(.18880,.28997)+uv+doubleArrayOf(.31320,.35968)
        val slopes=doubleArrayOf(-.44278,-.47888,-.58204,-.70471,-.84901,-1.0182,-1.2168,
            -1.4512,-1.7298,-2.0637,-2.4681,-2.9641,-3.5814,-4.3633,-5.3762,-6.7262,-8.5955,-11.324,-15.628)
        fun reciprocal(i: Int) = if(i==0) 90.0 else 100.0+(i-1)*25
        var last=0.0
        for(i in slopes.indices) {
            val d=(-(u-values[i*2])*slopes[i]+v-values[i*2+1])/sqrt(1+slopes[i]*slopes[i])
            if(d<=0 && i>0) {
                val f=-d/(last-d)
                return 1e6/(reciprocal(i-1)*f+reciprocal(i)*(1-f))
            }
            last=d
        }
        error("White point is outside the Robertson table")
    }
}
