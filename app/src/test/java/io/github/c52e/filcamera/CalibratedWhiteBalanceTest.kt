package io.github.c52e.filcamera

import io.github.c52e.filcamera.imaging.CalibratedWhiteBalance as W
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class CalibratedWhiteBalanceTest {
    private fun cases() = javaClass.getResourceAsStream("/white-balance-oracle.txt")!!.bufferedReader().readLines()
    private fun calibration(tokens: Iterator<Double>): W.Calibration {
        val t1=tokens.next();val t2=tokens.next()
        fun matrix() = W.Matrix(List(9) {tokens.next()})
        val c1=matrix();val c2=matrix();val k1=matrix();val k2=matrix()
        val f1=if(tokens.next()==1.0) matrix() else null
        val f2=if(tokens.next()==1.0) matrix() else null
        return W.Calibration(W.Anchor(t1,c1,k1,f1),W.Anchor(t2,c2,k2,f2))
    }
    @Test fun matchesPinnedAdobeSdkAndNeutralMapsToWhite() {
        var maximum=0.0
        cases().forEach {line->
            val parts=line.split('|');val tokens=parts[1].split(' ').map(String::toDouble).iterator()
            val kelvin=tokens.next().toInt();val c=calibration(tokens);val s=W.solve(c,kelvin)
            val expected=parts[2].split(' ').map(String::toDouble)
            (s.gains+s.transform.values).zip(expected).forEach {(a,b)->maximum=maxOf(maximum,abs(a-b));assertEquals("${parts[0]} $kelvin",b,a,1e-6)}
            val rgb=s.transform.apply(s.neutral.zip(listOf(s.gains[0],s.gains[1],s.gains[3])) {a,b->a*b})
            rgb.forEach {assertEquals(1.0,it,1e-9)}
            val reversed=W.solve(W.Calibration(c.second,c.first),kelvin)
            assertEquals(c.hash,W.Calibration(c.second,c.first).hash)
            (s.gains+s.transform.values).zip(reversed.gains+reversed.transform.values).forEach {(a,b)->assertEquals(a,b,1e-12)}
        }
        println("SDK 1.7.1 build 2724: ${cases().size} cases, max abs error=$maximum")
    }
    @Test fun entireSliderRangeIsFiniteForEachLens() {
        cases().filterIndexed {i,_->i%12==0}.forEach {line->
            val tokens=line.split('|')[1].split(' ').map(String::toDouble).iterator();tokens.next();val c=calibration(tokens)
            (2000..10000 step 50).forEach {k->val s=W.solve(c,k);assertTrue(s.gains.all {it in 1.0..16.0});assertTrue(s.transform.values.all(Double::isFinite))}
        }
    }
    @Test fun invalidCalibrationFailsRatherThanClamping() {
        fun rejected(block: ()->Unit) {try {block();fail("must reject")} catch(_: IllegalArgumentException) {}}
        rejected {W.Matrix(List(9) {Double.NaN})}
        rejected {W.Anchor(2856.0,W.Matrix(List(9) {0.0}),W.identity,null)}
        val a=W.Anchor(2856.0,W.identity,W.identity,null)
        rejected {W.Calibration(a,a)}
        val bad=W.Anchor(6500.0,W.diagonal(listOf(-1.0,1.0,1.0)),W.identity,null)
        rejected {W.solve(W.Calibration(a,bad),10000)}
        val extreme=W.Anchor(6500.0,W.diagonal(listOf(.00001,1.0,1.0)),W.identity,null)
        rejected {W.solve(W.Calibration(a,extreme),10000)}
    }
    @Test fun cameraIlluminantMappingMatchesSdk() {
        val expected=mapOf(17 to 2850.0,21 to 6500.0,20 to 5500.0,23 to 5000.0,24 to 3200.0,22 to 7500.0)
        expected.forEach {(tag,k)->assertEquals(k,io.github.c52e.filcamera.camera.CameraWhiteBalanceCalibration.temperature(tag),0.0)}
    }
}
