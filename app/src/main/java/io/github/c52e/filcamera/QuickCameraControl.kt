package io.github.c52e.filcamera

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Reuses existing control space; opening this never changes preview constraints. */
@Composable internal fun QuickCameraControl(model: CameraViewModel,camera: CameraCoordinator.State,panel: String,vertical: Boolean=false) {
    val wb=panel=="wb"
    val range=camera.whiteBalance.range
    val minimum=if(wb) range?.minimum ?: 2000 else camera.info?.evMin ?: 0
    val maximum=if(wb) range?.maximum ?: 10000 else camera.info?.evMax ?: 0
    val selected=if(wb) camera.whiteBalance.selection.kelvin ?: range?.default ?: 5500 else camera.ev
    var value by remember(panel,selected,minimum,maximum) {mutableFloatStateOf(selected.toFloat())}
    val enabled=!camera.busy && !camera.switching && (!wb || (camera.whiteBalance.supported && !camera.whiteBalance.pending))
    @Composable fun buttons() {
        if(wb) TextButton(onClick={model.camera.whiteBalance(false)},enabled=enabled,contentPadding=PaddingValues(0.dp),modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp)) {Text(tr(Texts.string.auto),fontSize=12.sp)}
        CameraIconButton(CameraIcons.Reset,tr(Texts.string.reset),enabled,hint=if(wb) tr(Texts.string.reset_white_balance) else tr(Texts.string.reset_exposure)) {if(wb) model.camera.whiteBalance(true,range?.default ?: 5500) else model.camera.exposure(0)}
    }
    @Composable fun slider(modifier: Modifier) {
        if(maximum>minimum) Slider(value,{value=if(wb) (it/50).roundToInt().times(50).coerceIn(minimum,maximum).toFloat() else it.roundToInt().toFloat()},
            valueRange=minimum.toFloat()..maximum.toFloat(),enabled=enabled,
            onValueChangeFinished={if(wb) model.camera.whiteBalance(true,value.roundToInt()) else model.camera.exposure(value.roundToInt())},
            modifier=modifier.height(48.dp).semantics {contentDescription=if(wb) tr(Texts.string.temperature) else tr(Texts.string.camera_exposure)})
    }
    if(vertical) Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
        slider(Modifier.fillMaxWidth());Row {buttons()}
    } else Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        slider(Modifier.weight(1f));buttons()
    }
}
