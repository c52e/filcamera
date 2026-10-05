package io.github.c52e.filcamera

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

import android.graphics.BitmapFactory
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/** Counts only drag distance beyond the fitted image's edge, not ordinary panning. */
internal class PhotoPan {
    var scale=1f
    var x=0f
    var y=0f
    var excess=0f
    fun move(dx: Float,dy: Float,zoom: Float,width: Float,height: Float,imageWidth: Float,imageHeight: Float) {
        scale=(scale*zoom).coerceIn(1f,5f)
        val fit=minOf(width/imageWidth,height/imageHeight)
        val limitX=((imageWidth*fit*scale-width)/2).coerceAtLeast(0f)
        val limitY=((imageHeight*fit*scale-height)/2).coerceAtLeast(0f)
        val next=(x+dx).coerceIn(-limitX,limitX)
        val remainder=x+dx-next
        // Reversing towards the image cancels an earlier edge swipe.
        excess=when {
            zoom!=1f->0f
            dx==0f->excess // Pointer-up has no delta; keep the completed edge drag.
            remainder==0f || remainder*excess<0f->remainder
            else->excess+remainder
        }
        x=next;y=(y+dy).coerceIn(-limitY,limitY)
    }
    fun destination(threshold: Float,horizontal: Float,vertical: Float,pinched: Boolean): Int =
        if(pinched || abs(horizontal)<abs(vertical)*1.25f || abs(excess)<threshold) 0 else if(excess<0) 1 else -1
}

@Composable internal fun ZoomPhoto(file: File?,modifier: Modifier,previous: Boolean,next: Boolean,zoomKey: Any?=file,onNavigate: (Int)->Unit) {
    val imageSize by produceState(1f to 1f,file) {
        value=withContext(Dispatchers.IO) {val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            if(file!=null) BitmapFactory.decodeFile(file.path,bounds)
            bounds.outWidth.coerceAtLeast(1).toFloat() to bounds.outHeight.coerceAtLeast(1).toFloat()}
    }
    var transform by remember(zoomKey) {mutableStateOf(Triple(1f,0f,0f))}
    val navigate by rememberUpdatedState(onNavigate)
    val canPrevious by rememberUpdatedState(previous)
    val canNext by rememberUpdatedState(next)
    val threshold=with(LocalDensity.current) {64.dp.toPx()}
    Box(modifier.clipToBounds().semantics {
        contentDescription=tr(Texts.string.photo_viewer)
        stateDescription=tr(Texts.string.zoom_2f).format(java.util.Locale.ROOT,transform.first)
        customActions=buildList {
            if(previous) add(CustomAccessibilityAction(tr(Texts.string.previous_photo)) {navigate(-1);true})
            if(next) add(CustomAccessibilityAction(tr(Texts.string.next_photo)) {navigate(1);true})
        }
    }.pointerInput(zoomKey,imageSize) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed=false)
            val pan=PhotoPan().also {it.scale=transform.first;it.x=transform.second;it.y=transform.third}
            var pinched=false;var totalX=0f;var totalY=0f;var started=false
            do {
                val event=awaitPointerEvent()
                if(event.changes.any {it.isConsumed}) break
                if(event.changes.count {it.pressed}>1) pinched=true
                val delta=event.calculatePan();val zoom=event.calculateZoom()
                totalX+=delta.x;totalY+=delta.y
                if(abs(totalX)>viewConfiguration.touchSlop || abs(totalY)>viewConfiguration.touchSlop || zoom!=1f) started=true
                if(started) {
                    pan.move(delta.x,delta.y,zoom,size.width.toFloat(),size.height.toFloat(),imageSize.first,imageSize.second)
                    transform=Triple(pan.scale,pan.x,pan.y)
                    event.changes.forEach {if(it.positionChanged()) it.consume()}
                }
                if(event.changes.none {it.pressed}) {
                    val direction=pan.destination(threshold,totalX,totalY,pinched)
                    if((direction<0 && canPrevious) || (direction>0 && canNext)) navigate(direction)
                }
            } while(event.changes.any {it.pressed})
        }
    }) {
        PhotoImage(file,Modifier.fillMaxSize().graphicsLayer(scaleX=transform.first,scaleY=transform.first,translationX=transform.second,translationY=transform.third))
    }
}
