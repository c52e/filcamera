package io.github.c52e.filcamera

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** Stable composition slots: rotating/reflowing controls does not replace the GL view. */
@Composable private fun ImageWorkspace(landscape: Boolean,toolsWidth: Dp,portraitToolsHeight: Dp?=null,
    image: @Composable ()->Unit,tools: @Composable ()->Unit,header: @Composable ()->Unit) {
    Layout(content={Box {image()};Box {tools()};Box {header()}},modifier=Modifier.fillMaxSize()) {items,c->
        val w=c.maxWidth;val h=c.maxHeight
        val head=items[2].measure(Constraints(maxWidth=w,maxHeight=if(landscape) 0 else h))
        val tw=toolsWidth.roundToPx().coerceAtMost(w)
        val th=portraitToolsHeight?.roundToPx()?.coerceAtMost((h-head.height).coerceAtLeast(0))
        val control=items[1].measure(if(landscape) Constraints.fixed(tw,h) else
            Constraints(minWidth=w,maxWidth=w,minHeight=th ?: 0,maxHeight=th ?: (h-head.height).coerceAtLeast(0)))
        val picture=items[0].measure(Constraints.fixed(if(landscape) w-control.width else w,
            if(landscape) h else (h-head.height-control.height).coerceAtLeast(0)))
        layout(w,h) {
            picture.place(0,if(landscape) 0 else head.height)
            control.place(if(landscape) picture.width else 0,if(landscape) 0 else head.height+picture.height)
            head.place(0,0)
        }
    }
}

@Composable internal fun CameraScreen(model: CameraViewModel,camera: CameraCoordinator.State,recipe: FilmRecipe,
    recent: File?,permission: Boolean,epoch: Int,landscape: Boolean,panel: String,openPanel: (String)->Unit,
    openLibrary: ()->Unit,capture: ()->Unit,requestPermission: ()->Unit,retry: ()->Unit,notice: @Composable ()->Unit) {
    val context=LocalContext.current
    val canShoot=permission && camera.ready && !camera.busy && !camera.updating && !camera.framingPending && !camera.switching && !camera.whiteBalance.pending
    @Composable fun action(label: String,enabled: Boolean=true,click: ()->Unit) {
        CameraIconButton(if(label==tr(Texts.string.settings)) CameraIcons.Settings else CameraIcons.Tune,label,enabled,onClick=click)
    }
    @Composable fun focal(option: io.github.c52e.filcamera.camera.FocalOption) {
        TextButton(onClick={model.camera.selectFocal(option.id)},enabled=!camera.busy && option.lens!=null,
            contentPadding=PaddingValues(0.dp),modifier=Modifier.size(48.dp).background(if(camera.selectedFocal==option.id) Accent.copy(alpha=.2f) else Color.Transparent,CircleShape)
                .semantics {contentDescription=tr(Texts.string.equivalent_focal_length_valuevalue, option.label, if(option.targetMm==null) tr(Texts.string.native_2) else "")}) {
            Column(horizontalAlignment=Alignment.CenterHorizontally) {Text(option.label,fontSize=12.sp,maxLines=1);Text("mm",fontSize=9.sp)}
        }
    }
    val focalScroll=rememberLazyListState()
    LaunchedEffect(camera.selectedFocal,camera.options,landscape) {
        val index=camera.options.indexOfFirst {it.id==camera.selectedFocal}
        if(index>=0) focalScroll.animateScrollToItem(index)
    }
    @Composable fun shutter(size: Dp=78.dp) {
        Box(Modifier.size(size).border(2.dp,if(canShoot) Accent else Muted,CircleShape).padding(if(size<78.dp) 4.dp else 6.dp)
            .background(if(camera.busy) Color.DarkGray else Accent,CircleShape).clickable(enabled=canShoot,onClick=capture)
            .semantics {contentDescription=tr(Texts.string.capture)},contentAlignment=Alignment.Center) {
            if(camera.busy) CircularProgressIndicator(Modifier.size(30.dp),color=Accent,strokeWidth=2.dp)
        }
    }
    @Composable fun library() {
        Box(Modifier.size(48.dp).background(Color(0xff25272a),RoundedCornerShape(10.dp)).clickable(onClick=openLibrary)
            .semantics {contentDescription=tr(Texts.string.library)},contentAlignment=Alignment.Center) {
            if(recent!=null) PhotoImage(recent,Modifier.fillMaxSize(),256) else Icon(CameraIcons.Photo,null,Modifier.size(24.dp),tint=Muted)
        }
    }
    val adjusting=panel=="recipe" || panel=="stock"
    val quick=panel=="ev" || panel=="wb"
    @Composable fun whiteBalanceButton() {
        if(camera.whiteBalance.supported && !camera.switching) CameraIconButton(CameraIcons.WhiteBalance,tr(Texts.string.white_balance),!camera.busy,
            value=if(camera.whiteBalance.selection.manual) "${camera.whiteBalance.selection.kelvin} K" else tr(Texts.string.auto),selected=panel=="wb",
            hint=if(camera.whiteBalance.controlSource==io.github.c52e.filcamera.camera.WhiteBalanceSource.CALIBRATED) tr(Texts.string.white_balance_lens_calibration) else tr(Texts.string.white_balance)) {openPanel(if(panel=="wb") "" else "wb")}
    }
    @Composable fun quickButtons() {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
            CameraIconButton(CameraIcons.Exposure,tr(Texts.string.exposure),camera.info!=null && !camera.busy,
                value="%+.1f EV".format(camera.ev*(camera.info?.evStep ?: 0f)),selected=panel=="ev",hint=tr(Texts.string.camera_exposure_compensation)) {openPanel(if(panel=="ev") "" else "ev")}
            whiteBalanceButton()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Spend letterbox space, not image area. The larger source aspect is conservative
    // for either producer rotation; never reduce the existing fitted image size.
    val sourceAspect=camera.info?.preview?.let {maxOf(it.width,it.height).toFloat()/minOf(it.width,it.height)}
    val landscapeRail=if(sourceAspect!=null) (maxWidth-maxHeight*sourceAspect).coerceIn(160.dp,300.dp) else 160.dp
    val toolWidth=if(landscape) landscapeRail else 160.dp
    // Use existing letterbox space for one stable dock; opening controls never
    // changes preview constraints. Short windows use paged parameter controls.
    val toolHeight=if(landscape) null else (maxHeight-48.dp-maxWidth*(sourceAspect ?: (4f/3f))).coerceIn(216.dp,320.dp)
    ImageWorkspace(landscape,toolWidth,toolHeight,image={
        Box(Modifier.fillMaxSize().background(Color.Black).semantics {contentDescription=tr(Texts.string.live_viewfinder)},contentAlignment=Alignment.Center) {
            if(permission) key(epoch) {CameraPreview(model)} else Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()),horizontalAlignment=Alignment.CenterHorizontally) {
                Text(tr(Texts.string.let_the_light_in),fontSize=24.sp);Text(tr(Texts.string.allow_camera_access_to_take_photos_or_browse_and_import_dngs_firs),color=Muted)
                Button(onClick=requestPermission) {Text(tr(Texts.string.allow_camera_access))}
                TextButton(onClick={context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+context.packageName)))}) {Text(tr(Texts.string.open_permission_settings))}
            }
            if(camera.processing) Text(tr(Texts.string.capture_complete_can_move),color=Accent,fontSize=16.sp)
            Box(Modifier.align(Alignment.BottomStart)) {notice()}
            val message=when {camera.switching->tr(Texts.string.switching_lens_2);camera.whiteBalance.error.isNotBlank()->camera.whiteBalance.error;camera.whiteBalance.pending->tr(Texts.string.updating_white_balance);camera.updating->tr(Texts.string.updating_preview);camera.framingPending->tr(Texts.string.adjusting_framing);else->camera.message}
            if(message.isNotBlank()) Text(message,Modifier.align(Alignment.TopStart).padding(8.dp)
                .background(Color.Black.copy(alpha=.65f),RoundedCornerShape(10.dp)).padding(8.dp),fontSize=12.sp,color=Accent,maxLines=3)
            if(permission && !camera.ready && !camera.busy && !camera.updating && !camera.framingPending && camera.message!=tr(Texts.string.starting_camera) && camera.info?.raw!=false)
                TextButton(onClick=retry,modifier=Modifier.align(Alignment.BottomCenter)) {Text(tr(Texts.string.retry_camera))}
        }
    },tools={
        if(adjusting) CameraAdjustments(model,camera,recipe,panel,{openPanel("")}) {shutter(48.dp)}
        else if(landscape) Column(Modifier.fillMaxSize().padding(horizontal=8.dp),
            horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.SpaceEvenly) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {action(tr(Texts.string.settings)) {openPanel("settings")}}
            LazyRow(Modifier.fillMaxWidth(),state=focalScroll,horizontalArrangement=Arrangement.Center) {items(camera.options,key={it.id}) {focal(it)}}
            quickButtons()
            // Reserve the same area while closed so the shutter never moves.
            Box(Modifier.fillMaxWidth().height(96.dp)) {if(quick) QuickCameraControl(model,camera,panel,vertical=true)}
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceAround) {
                library();shutter();action(tr(Texts.string.color)) {openPanel("recipe")}
            }
        } else Column(Modifier.fillMaxSize().padding(horizontal=12.dp,vertical=6.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.SpaceEvenly) {
            Box(Modifier.fillMaxWidth().height(48.dp)) {
                if(quick) QuickCameraControl(model,camera,panel)
                else LazyRow(Modifier.fillMaxWidth(),state=focalScroll,horizontalArrangement=Arrangement.Center) {items(camera.options,key={it.id}) {focal(it)}}
            }
            quickButtons()
            Row(Modifier.fillMaxWidth().padding(top=4.dp),horizontalArrangement=Arrangement.SpaceAround,verticalAlignment=Alignment.CenterVertically) {library();shutter();action(tr(Texts.string.color)) {openPanel("recipe")}}
        }
    },header={if(!landscape) Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
        Text("FILCamera",fontWeight=FontWeight.Bold,letterSpacing=3.sp,modifier=Modifier.weight(1f))
        action(tr(Texts.string.settings)) {openPanel("settings")}
    }})
    }
}

@Composable internal fun EditorScreen(edit: EditSession.State,fallback: File?,landscape: Boolean,expanded: Boolean,
    toggle: ()->Unit,change: (FilmRecipe)->Unit,back: ()->Unit,save: ()->Unit,canBack: Boolean,notice: @Composable ()->Unit) {
    var group by rememberSaveable {mutableIntStateOf(0)}
    val zoomSession=remember {Any()}
    val canSave=!edit.busy && edit.resultHash==edit.recipe.hash && edit.file!=null
    @Composable fun toggleButton() {TextButton(onClick=toggle,contentPadding=PaddingValues(2.dp),modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp)
        .semantics {contentDescription=if(expanded) tr(Texts.string.collapse_color_controls) else tr(Texts.string.expand_color_controls)}) {Text(if(expanded) tr(Texts.string.collapse) else tr(Texts.string.color),fontSize=13.sp)}}
    @Composable fun backButton() {CameraIconButton(CameraIcons.Back,tr(Texts.string.back),canBack,onClick=back)}
    @Composable fun saveButton() {TextButton(onClick=save,enabled=canSave,contentPadding=PaddingValues(2.dp),modifier=Modifier.heightIn(min=48.dp)) {Text(tr(Texts.string.save_copy),fontSize=13.sp)}}
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelWidth=if(expanded) (maxWidth*.32f).coerceIn(240.dp,288.dp) else 56.dp
        ImageWorkspace(landscape,panelWidth,if(expanded) minOf(280.dp,maxOf(192.dp,maxHeight*.32f),maxHeight*.55f) else 0.dp,
            image={Box(Modifier.fillMaxSize()) {ZoomPhoto(edit.file ?: fallback,Modifier.fillMaxSize(),false,false,zoomSession) {};Box(Modifier.align(Alignment.BottomStart)) {notice()}}},
            header={if(!landscape) Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                backButton();Text(tr(Texts.string.darkroom),fontSize=18.sp);toggleButton();saveButton()
            }},tools={
                if(landscape && !expanded) Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally) {
                    backButton();toggleButton();saveButton()
                    if(edit.busy) CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp),strokeWidth=2.dp)
                } else if(expanded) Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    if(landscape) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {backButton();toggleButton();saveButton()}
                    // Keep one status line in every state so updates never move the
                    // tabs, parameter buttons or active slider under the user's finger.
                    Text(edit.message.ifBlank { " " },Modifier
                        .height(with(androidx.compose.ui.platform.LocalDensity.current) {20.sp.toDp()})
                        .padding(horizontal=12.dp),
                        fontSize=12.sp,color=Muted,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    CompactRecipePanel(edit.recipe,change,group,{group=it},Modifier.weight(1f),editing=true)
                }
            })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CameraParameterPanel(side: Boolean,onDismiss: ()->Unit,content: @Composable ()->Unit) {
    if(side) {
        androidx.activity.compose.BackHandler(onBack=onDismiss)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.35f))) {
            Box(Modifier.matchParentSize().clickable(onClick=onDismiss))
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                Surface(Modifier.align(Alignment.CenterEnd).width(minOf(400.dp,maxWidth*.52f)).fillMaxHeight()) {
                    Column {
                        Box(Modifier.align(Alignment.End)) {CameraIconButton(CameraIcons.Close,tr(Texts.string.close),onClick=onDismiss)}
                        Box(Modifier.weight(1f)) {content()}
                    }
                }
            }
        }
    } else ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {content()}
}
