package io.github.c52e.filcamera

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun isCameraAdjustment(panel: String)=panel in setOf("recipe","stock","ev","wb")

/** A sibling of the preview, never a dialog or a scrim. Shares all model callbacks with the editor. */
@Composable internal fun CameraAdjustments(model: CameraViewModel,camera: CameraCoordinator.State,
    recipe: FilmRecipe,panel: String,close: ()->Unit,shutter: @Composable ()->Unit) {
    var group by rememberSaveable {mutableIntStateOf(0)}
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
        .semantics {contentDescription=tr(Texts.string.viewfinder_controls)}) {
        CompactRecipePanel(recipe,model::recipe,group,{group=it},Modifier.weight(1f),actions={
            CameraIconButton(CameraIcons.Close,tr(Texts.string.close),onClick=close)
            shutter()
        })
    }
}
