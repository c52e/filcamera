package io.github.c52e.filcamera.imaging

import io.github.c52e.filcamera.imaging.CalibratedWhiteBalance as W
import org.junit.Assert.*
import org.junit.Test

class DngColorTest {
    private val d50=listOf(.3457/.3585,1.0,(1-.3457-.3585)/.3585)
    @Test fun forwardMatrixCancelsIndividualCalibrationAndAnalogBalance() {
        val calibration=W.diagonal(listOf(.8,1.0,1.2))
        val anchor=W.Anchor(5000.0,W.identity,calibration,W.diagonal(d50))
        val result=W.solveDng(anchor,null,listOf(.25,1.0,.5),null,listOf(1.1,.9,1.2))
        assertEquals(listOf(4.0,1.0,1.0,2.0),result.gains)
        val white=result.balancedCameraToD50.apply(listOf(1.0,1.0,1.0))
        d50.zip(white).forEach {(expected,actual)->assertEquals(expected,actual,1e-10)}
        // A diagonal forward matrix cannot mix the three independent primary channels.
        for(i in 0..8) assertEquals(if(i/3==i%3) d50[i/3] else 0.0,result.balancedCameraToD50.values[i],1e-10)
    }
    @Test fun matrixOnlyPathAdaptsAsShotNeutralToD50() {
        val anchor=W.Anchor(6500.0,W.identity,W.identity,null)
        val result=W.solveDng(anchor,null,listOf(.9,1.0,.8),null)
        val white=result.balancedCameraToD50.apply(listOf(1.0,1.0,1.0))
        d50.zip(white).forEach {(expected,actual)->assertEquals(expected,actual,1e-9)}
    }
    @Test fun dualIlluminantsAcceptTintedAsShotNeutralWithoutADeviceCalibration() {
        val cm=W.Matrix(listOf(.7421875,-.2421875,-.109375,-.40625,1.2890625,.09375,-.0625,.296875,.453125))
        val cm2=W.Matrix(listOf(1.21875,-.6328125,-.1484375,-.5078125,1.453125,.296875,-.03125,.1484375,.78125))
        val fm=W.Matrix(listOf(.546875,.2578125,.15625,.1796875,.890625,-.0703125,-.03125,-.40625,1.265625))
        val cc=W.diagonal(listOf(.9921875,1.0,1.015625))
        val result=W.solveDng(W.Anchor(6500.0,cm,cc,fm),W.Anchor(2850.0,cm2,cc,fm),listOf(.3603515625,1.0,.7294921875),null)
        assertTrue(result.whiteXYZ.all {it.isFinite() && it>0})
        assertEquals(1/.3603515625,result.gains[0],1e-12)
        assertEquals(1/.7294921875,result.gains[3],1e-12)
        d50.zip(result.balancedCameraToD50.apply(listOf(1.0,1.0,1.0))).forEach {(a,b)->assertEquals(a,b,1e-10)}
    }
    @Test fun asShotWhiteXYAndNeutralDescribeTheSameSingleIlluminant() {
        val a=W.Anchor(5000.0,W.identity,W.identity,null)
        val neutral=W.solveDng(a,null,d50,null)
        val xy=W.solveDng(a,null,null,listOf(.3457,.3585))
        neutral.balancedCameraToD50.values.zip(xy.balancedCameraToD50.values).forEach {(x,y)->assertEquals(x,y,1e-9)}
    }
    @Test fun mappedShadingUsesDngPixelCentersAndClampsEdges() {
        val values=FloatArray(2*2*4) {i->if(i/4%2==0) .5f else 1.5f}
        val grid=ShadingGrid(2,2,values,.75f,.75f,.125f,.125f)
        assertEquals(.625f,grid.gain(0,0f,0f),1e-6f)
        assertEquals(1.375f,grid.gain(0,1f,1f),1e-6f)
    }
    @Test fun dualColorMatrixEndpointScaleDoesNotChangeTheDevelopment() {
        val cm1=W.Matrix(listOf(.7421875,-.2421875,-.109375,-.40625,1.2890625,.09375,-.0625,.296875,.453125))
        val cm2=W.Matrix(listOf(1.21875,-.6328125,-.1484375,-.5078125,1.453125,.296875,-.03125,.1484375,.78125))
        fun solution(a: Double,b: Double)=W.solveDng(W.Anchor(6500.0,cm1.scale(a),W.identity,null),
            W.Anchor(2850.0,cm2.scale(b),W.identity,null),listOf(.3603515625,1.0,.7294921875),null)
        val expected=solution(1.0,1.0);val scaled=solution(3.0,.2)
        expected.balancedCameraToD50.values.zip(scaled.balancedCameraToD50.values).forEach {(a,b)->assertEquals(a,b,1e-9)}
    }
    @Test(expected=IllegalArgumentException::class) fun invalidNeutralIsRejected() {
        W.solveDng(W.Anchor(5000.0,W.identity,W.identity,null),null,listOf(1.0,0.0,1.0),null)
    }
}
