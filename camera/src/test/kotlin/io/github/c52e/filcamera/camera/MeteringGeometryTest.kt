package io.github.c52e.filcamera.camera

import org.junit.Assert.*
import org.junit.Test

class MeteringGeometryTest {
    @Test fun invertLogicalToPhysicalMappingAndRejectUnreachablePoint() {
        val bounds=MeteringGeometry.Box(0,0,4000,3000)
        val mapping=MeteringGeometry.Mapping(MeteringGeometry.Box(1800,1350,2200,1650),MeteringGeometry.Box(1000,750,3000,2250))
        assertEquals(MeteringGeometry.Box(2000,1350,2200,1500),mapping.inverse(MeteringGeometry.Box(2000,750,3000,1500),bounds))
        val wide=MeteringGeometry.Mapping(MeteringGeometry.Box(1800,1350,2200,1650),MeteringGeometry.Box(1900,1425,2100,1575))
        assertNull(wide.inverse(MeteringGeometry.Box(0,0,100,100),bounds))
    }
    @Test fun centerEdgesAndStreamAspect() {
        val crop=MeteringGeometry.Box(0,0,4000,3000)
        assertEquals(MeteringGeometry.Box(1800,1350,2200,1650),MeteringGeometry.region(.5f,.5f,crop,1280,960))
        assertEquals(MeteringGeometry.Box(0,0,400,300),MeteringGeometry.region(0f,0f,crop,1280,960))
        assertEquals(MeteringGeometry.Box(3600,2700,4000,3000),MeteringGeometry.region(1f,1f,crop,1280,960))
        assertEquals(MeteringGeometry.Box(1950,1463,2050,1538),MeteringGeometry.region(.5f,.5f,crop,1280,960,.25f))
        // 16:9 stream inside 4:3 sensor: top preview edge starts at sensor row 375.
        val wide=MeteringGeometry.region(.5f,0f,crop,1920,1080)
        assertEquals(375,(wide.top+wide.bottom)/2)
    }
    @Test fun cropOffsetsAndTinyArrays() {
        assertEquals(MeteringGeometry.Box(1900,1425,2100,1575),
            MeteringGeometry.region(.5f,.5f,MeteringGeometry.Box(1000,750,3000,2250),1280,960))
        assertEquals(MeteringGeometry.Box(0,0,1,1),
            MeteringGeometry.region(1f,1f,MeteringGeometry.Box(0,0,1,1),1,1))
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsInvalidCoordinates() {
        MeteringGeometry.region(Float.NaN,.5f,MeteringGeometry.Box(0,0,4000,3000),1280,960)
    }
}
