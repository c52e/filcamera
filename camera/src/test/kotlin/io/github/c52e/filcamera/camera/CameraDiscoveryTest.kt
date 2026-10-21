package io.github.c52e.filcamera.camera

import org.junit.Assert.*
import org.junit.Test

class CameraDiscoveryTest {
    @Test fun stalePublicIdDoesNotBlockOtherCameras() {
        val failures=mutableListOf<String>()
        val entries=readCameraEntries(listOf("0","stale","2"),{id->
            if(id=="stale") throw IllegalArgumentException("Unknown device")
            "metadata:$id"
        }) {id,_->failures+=id}
        assertEquals(listOf("0" to "metadata:0","2" to "metadata:2"),entries)
        assertEquals(listOf("stale"),failures)
    }
}
