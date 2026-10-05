package io.github.c52e.filcamera

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Original 24-unit line icons, shared by camera and darkroom. No raster assets. */
internal object CameraIcons {
    private fun line(name: String,data: String)=ImageVector.Builder(name,24.dp,24.dp,24f,24f).addPath(
        PathParser().parsePathString(data).toNodes(),stroke=SolidColor(Color.White),strokeLineWidth=1.7f,
        strokeLineCap=StrokeCap.Round,strokeLineJoin=StrokeJoin.Round).build()
    // Eight identical teeth: horizontal and vertical extents are exactly equal.
    val Settings=line("Settings",buildString {
        for(tooth in 0..7) for((corner,offset) in listOf(-7.0,7.0,15.0,30.0).withIndex()) {
            val angle=Math.toRadians(tooth*45.0+offset)
            val radius=if(corner<2) 10.0 else 7.8
            append(if(tooth==0 && corner==0) "M" else "L")
            append(12+radius*kotlin.math.cos(angle));append(" ");append(12+radius*kotlin.math.sin(angle));append(" ")
        }
        append("Z M16 12 A4 4 0 1 1 8 12 A4 4 0 1 1 16 12")
    })
    val Exposure=line("Exposure","M16 12 A4 4 0 1 1 8 12 A4 4 0 1 1 16 12 M12 2 L12 4 M12 20 L12 22 M2 12 L4 12 M20 12 L22 12 M5 5 L6.5 6.5 M17.5 17.5 L19 19 M5 19 L6.5 17.5 M17.5 6.5 L19 5")
    val WhiteBalance=line("WhiteBalance","M9 14 L9 5 A3 3 0 0 1 15 5 L15 14 A5 5 0 1 1 9 14 Z M12 8 L12 17 M12 17 L12 19 M18 5 L21 5 M18 9 L20 9")
    val Reset=line("Reset","M4 9 A8 8 0 1 1 4 16 M4 3 L4 9 L10 9")
    val Tune=line("Tune","M3 6 L8 6 M12 6 L21 6 M3 12 L14 12 M18 12 L21 12 M3 18 L6 18 M10 18 L21 18 M8 3 L12 3 L12 9 L8 9 Z M14 9 L18 9 L18 15 L14 15 Z M6 15 L10 15 L10 21 L6 21 Z")
    val Photo=line("Photo","M3 4 L21 4 L21 20 L3 20 Z M3 17 L9 11 L13 15 L16 12 L21 17 M16 8 L16.01 8")
    val Back=line("Back","M14 5 L7 12 L14 19 M7 12 L21 12")
    val Close=line("Close","M6 6 L18 18 M18 6 L6 18")
    val Delete=line("Delete","M3 6 L21 6 M9 6 L9 3 L15 3 L15 6 M5 6 L6 21 L18 21 L19 6 M10 10 L10 17 M14 10 L14 17")
    val SelectAll=line("SelectAll","M3 16 L3 3 L16 3 M7 7 L21 7 L21 21 L7 21 Z M10 14 L13 17 L18 11")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CameraIconButton(icon: ImageVector,label: String,enabled: Boolean=true,
    value: String?=null,selected: Boolean=false,hint: String=label,onClick: ()->Unit) {
    TooltipBox(positionProvider=TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip={PlainTooltip {Text(hint)}},state=rememberTooltipState()) {
        TextButton(onClick=onClick,enabled=enabled,contentPadding=PaddingValues(horizontal=8.dp),
            colors=ButtonDefaults.textButtonColors(contentColor=if(selected) Accent else Muted),
            modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).semantics {
                contentDescription=label
                if(value!=null) stateDescription=value
                this.selected=selected
            }) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Icon(icon,contentDescription=null,modifier=Modifier.size(23.dp))
                if(value!=null) Text(value,fontSize=13.sp,maxLines=1)
            }
        }
    }
}
