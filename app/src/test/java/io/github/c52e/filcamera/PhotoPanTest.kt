package io.github.c52e.filcamera

import org.junit.Assert.*
import org.junit.Test

class PhotoPanTest {
    @Test fun originalSizeSwipesAndVerticalGestureDoesNotNavigate() {
        val p=PhotoPan();p.move(-100f,0f,1f,400f,600f,400f,600f)
        p.move(0f,0f,1f,400f,600f,400f,600f)
        assertEquals(1,p.destination(64f,-100f,0f,false))
        assertEquals(0,p.destination(64f,-100f,200f,false))
        assertEquals(0,p.destination(64f,-100f,0f,true))
    }
    @Test fun zoomPansToActualImageEdgeBeforeCountingSwipe() {
        val p=PhotoPan();p.scale=2f;p.move(-250f,0f,1f,400f,600f,400f,600f)
        assertEquals(-200f,p.x,0f);assertEquals(-50f,p.excess,0f)
        assertEquals(0,p.destination(64f,-250f,0f,false))
        p.move(-20f,0f,1f,400f,600f,400f,600f)
        assertEquals(1,p.destination(64f,-270f,0f,false))
        p.move(10f,0f,1f,400f,600f,400f,600f)
        assertEquals(0f,p.excess,0f)
    }
    @Test fun letterboxingDoesNotCreateInvisiblePanArea() {
        val p=PhotoPan();p.scale=2f;p.move(100f,0f,1f,600f,400f,200f,400f)
        assertEquals(0f,p.x,0f)
        assertEquals(-1,p.destination(64f,100f,0f,false))
    }
}
