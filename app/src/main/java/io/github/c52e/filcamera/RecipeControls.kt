package io.github.c52e.filcamera

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun IntControl(label: String,value: Int,min: Int,max: Int,default: Int,format: (Int)->String,changed: (Int)->Unit) {
    var local by remember(value) {mutableFloatStateOf(value.toFloat())}
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked=maxWidth<280.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale>1.2f
        Column {
            if(stacked) Text(label)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                if(!stacked) Text(label,Modifier.weight(1f))
                Text(format(local.toInt()),color=Accent,modifier=if(stacked) Modifier.weight(1f) else Modifier)
                CameraIconButton(CameraIcons.Reset,tr(Texts.string.reset_value, label),hint=tr(Texts.string.reset_value, label)) {local=default.toFloat();changed(default)}
            }
        }
    }
    if(max>min) Slider(local,{local=it},valueRange=min.toFloat()..max.toFloat(),steps=max-min-1,onValueChangeFinished={changed(local.toInt())},modifier=Modifier.heightIn(min=48.dp).semantics {contentDescription=label})
}
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun StockOptions(value: String,values: Map<String,String>,change: (String)->Unit) {
    val current=remember {BringIntoViewRequester()}
    LaunchedEffect(Unit) { current.bringIntoView() }
    values.entries.groupBy {if(it.key==FilmStocks.AUTO_PAPER) tr(Texts.string.default_pairing) else it.value.substringBefore(' ')}.forEach {(brand,entries)->
        Text(brand,Modifier.padding(start=16.dp,top=12.dp,bottom=4.dp),fontSize=12.sp,color=Muted)
        entries.forEach {(id,name)->
            val chosen=id==value
            Row(Modifier.fillMaxWidth().then(if(chosen) Modifier.bringIntoViewRequester(current) else Modifier)
                .selectable(chosen,onClick={change(id)},role=Role.RadioButton)
                .heightIn(min=56.dp).padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                RadioButton(selected=chosen,onClick=null)
                Text(name,Modifier.padding(start=8.dp).weight(1f),fontSize=14.sp,color=if(chosen) Accent else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
@Composable internal fun RecipeTabs(group: Int,select: (Int)->Unit) {
    Row(Modifier.fillMaxWidth()) {
        listOf(tr(Texts.string.film),tr(Texts.string.print),tr(Texts.string.effects)).forEachIndexed {index,label->
            TextButton(onClick={select(index)},contentPadding=PaddingValues(horizontal=2.dp),
                modifier=Modifier.weight(1f).heightIn(min=48.dp).semantics {selected=group==index}) {
                Column(horizontalAlignment=Alignment.CenterHorizontally) {
                    Text(label,maxLines=1,color=if(group==index) MaterialTheme.colorScheme.primary else Muted)
                    if(group==index) HorizontalDivider(Modifier.width(24.dp),thickness=2.dp,color=MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
