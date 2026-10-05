package io.github.c52e.filcamera

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.c52e.filcamera.camera.WhiteBalanceStatus
import kotlin.math.roundToInt

@Composable internal fun WhiteBalancePanel(status: WhiteBalanceStatus,busy: Boolean,change: (Boolean,Int?)->Unit,compact: Boolean=false) {
    val range=status.range ?: return
    val selected=status.selection
    if(status.controlSource==io.github.c52e.filcamera.camera.WhiteBalanceSource.CALIBRATED) Text(tr(Texts.string.based_on_lens_calibration),fontSize=12.sp,color=Muted)
    if(!compact) Text(tr(Texts.string.white_balance),fontSize=24.sp)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
        TextButton(onClick={change(false,null)},enabled=!busy) {Text(if(!selected.manual) tr(Texts.string.auto_2) else tr(Texts.string.auto))}
        TextButton(onClick={change(true,selected.kelvin ?: range.default)},enabled=!busy && !status.pending) {Text(if(selected.manual) tr(Texts.string.manual) else tr(Texts.string.manual_2))}
    }
    if(selected.manual) {
        var local by remember(selected.kelvin,range) {mutableFloatStateOf(requireNotNull(selected.kelvin).toFloat())}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(tr(Texts.string.temperature_value_k, local.roundToInt()),color=Accent)
            CameraIconButton(CameraIcons.Reset,tr(Texts.string.reset_white_balance),!busy && !status.pending) {change(true,range.default)}
        }
        Slider(local,{local=(it/50f).roundToInt().times(50).coerceIn(range.minimum,range.maximum).toFloat()},
            valueRange=range.minimum.toFloat()..range.maximum.toFloat(),enabled=!busy && !status.pending,
            onValueChangeFinished={change(true,local.roundToInt())},
            modifier=Modifier.heightIn(min=48.dp).semantics {contentDescription=tr(Texts.string.temperature)})
    }
    if(status.pending) Text(tr(Texts.string.waiting_for_camera_confirmation),color=Accent)
    if(status.error.isNotBlank()) Text(status.error,color=MaterialTheme.colorScheme.error)
}
