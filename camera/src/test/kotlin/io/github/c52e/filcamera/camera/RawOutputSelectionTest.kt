package io.github.c52e.filcamera.camera

import org.junit.Assert.*
import org.junit.Test

class RawOutputSelectionTest {
    private fun select(vararg sizes: Pair<Int,Int>)=smallestRawOutput(sizes.toList()) {it}

    @Test fun prefersBinnedModeRegardlessOfEnumerationOrder() {
        val binned=4096 to 3072
        val full=8192 to 6144
        assertEquals(binned,select(full,binned))
        assertEquals(binned,select(binned,full))
    }
    @Test fun acceptsFiftyAndSixtyMegapixelsWhenTheyAreTheOnlyModes() {
        assertEquals(8192 to 6144,select(8192 to 6144))
        assertEquals(10000 to 6000,select(10000 to 6000))
    }
    @Test fun rejectsUnsupportedSizesInsteadOfOpeningThem() {
        assertNull(select(10000 to 6001,20000 to 1000,0 to 100))
        assertNull(select())
        assertEquals(8192 to 6144,select(16384 to 12288,8192 to 6144))
    }
}
