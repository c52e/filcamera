package io.github.c52e.filcamera

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val Accent=Color(0xffe1c18c)
internal val Muted=Color(0xffaaa9a5)
class CameraActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle=androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle=androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {MaterialTheme(colorScheme=darkColorScheme(primary=Accent,background=Color(0xff101113),surface=Color(0xff1b1d20),onPrimary=Color.Black)) {CameraApp()}}
    }
}

@OptIn(ExperimentalMaterial3Api::class,ExperimentalFoundationApi::class)
@Composable internal fun CameraApp(model: CameraViewModel=viewModel()) {
    val ui by model.state.collectAsState();val camera by model.camera.state.collectAsState();val edit by model.editor.state.collectAsState()
    val context=LocalContext.current
    var page by rememberSaveable {mutableStateOf("camera")}
    var selectedId by rememberSaveable {mutableStateOf("")}
    var versionId by rememberSaveable {mutableStateOf("")}
    var previewEpoch by remember {mutableIntStateOf(0)}
    var panel by rememberSaveable {mutableStateOf("")}
    var deletion by remember {mutableStateOf("")}
    var selectedPhotos by rememberSaveable {mutableStateOf(arrayListOf<String>())}
    var batchDelete by rememberSaveable {mutableStateOf(false)}
    fun togglePhoto(id: String) {selectedPhotos=ArrayList(if(id in selectedPhotos) selectedPhotos-id else selectedPhotos+id)}
    LaunchedEffect(page,ui.photos) {
        selectedPhotos=ArrayList(if(page=="library") selectedPhotos.filter {id->ui.photos.any {it.id==id}} else emptyList())
    }
    var permission by remember {mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)}
    val prefs=remember {context.getSharedPreferences("product",0)}
    var asked by remember {mutableStateOf(prefs.getBoolean("cameraAsked",false))}
    val permissionRequest=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {permission=it}
    var exportId by rememberSaveable {mutableStateOf("")}
    val importDng=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {it?.let(model::import)}
    val exportDng=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/x-adobe-dng")) {uri->
        ui.photos.firstOrNull {it.id==exportId}?.let {photo->if(uri!=null) model.export(photo,uri)}
    }
    var editExpanded by rememberSaveable {mutableStateOf(true)}
    LaunchedEffect(page) {if(page!="edit") editExpanded=true}
    val photo=ui.photos.firstOrNull {it.id==selectedId}
    val version=photo?.versions?.firstOrNull {it.id==versionId} ?: photo?.latest
    fun back() {if(page=="edit" && edit.saving) return;panel="";if(page=="edit") {model.editor.close();page="photo"} else if(page=="photo") page="library" else page="camera"}
    BackHandler(page!="camera" || panel.isNotEmpty()) {if(batchDelete) batchDelete=false else if(selectedPhotos.isNotEmpty()) selectedPhotos=arrayListOf() else if(panel.isNotEmpty()) panel="" else back()}
    LaunchedEffect(page,photo?.id) {if(page=="edit" && photo!=null && !model.editor.isOpen) model.edit(photo,version,{page=="edit" && selectedId==photo.id}) {}}
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_RESUME) {
            permission=context.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
        }}
        owner.lifecycle.addObserver(observer);onDispose {owner.lifecycle.removeObserver(observer)}
    }
    DisposableEffect(page,owner) {
        val editing=page=="edit"
        val observer=LifecycleEventObserver {_,event->if(editing) {
            if(event==Lifecycle.Event.ON_STOP) model.editor.pause()
            if(event==Lifecycle.Event.ON_START) model.editor.resume()
        }}
        owner.lifecycle.addObserver(observer)
        onDispose {owner.lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(Unit) {if(!permission && !asked) {asked=true;prefs.edit().putBoolean("cameraAsked",true).apply();permissionRequest.launch(Manifest.permission.CAMERA)}}
    fun saveCopy() {model.editor.save {result,error->if(result!=null) {versionId=result.id;model.refresh();model.editor.close();page="photo"};model.message(error.orEmpty())}}
    val accessibility=LocalAccessibilityManager.current
    LaunchedEffect(ui.messageId,ui.message,ui.messagePersistent,owner,accessibility) {
        if(ui.message.isNotBlank() && !ui.messagePersistent) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val timeout=accessibility?.calculateRecommendedTimeoutMillis(3000L,containsIcons=false,containsText=true,containsControls=false) ?: 3000L
            delay(timeout)
            model.dismissMessage(ui.messageId)
        }
    }
    @Composable fun notice() {
        if(ui.message.isNotBlank()) Surface(
            modifier=Modifier.padding(horizontal=12.dp).widthIn(max=420.dp).semantics {liveRegion=LiveRegionMode.Polite},
            shape=RoundedCornerShape(12.dp),color=Color(0xff343027),shadowElevation=6.dp) {
            Row(Modifier.padding(start=16.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(ui.message,Modifier.weight(1f).heightIn(max=96.dp).verticalScroll(remember(ui.messageId) {ScrollState(0)}).padding(vertical=12.dp),fontSize=13.sp)
                if(ui.messagePersistent) TextButton(onClick={model.dismissMessage(ui.messageId)}) {Text(tr(Texts.string.close))}
            }
        }
    }
    val landscape=LocalConfiguration.current.screenWidthDp>LocalConfiguration.current.screenHeightDp
    DisposableEffect(landscape,context) {
        val window=(context as android.app.Activity).window
        val bars=androidx.core.view.WindowCompat.getInsetsController(window,window.decorView)
        bars.systemBarsBehavior=androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if(landscape) bars.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        else bars.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose {bars.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())}
    }
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            if(page=="camera") {
                CameraScreen(model,camera,ui.recipe,ui.photos.firstOrNull()?.latest?.file,permission,previewEpoch,landscape,panel,
                    openPanel={panel=it},openLibrary={page="library"},capture={model.camera.capture(model.captureOrientation.displayDegreesForCapture(rotation(context)))},
                    requestPermission={permissionRequest.launch(Manifest.permission.CAMERA)},retry={previewEpoch++},notice={notice()})
            } else {
                if(page!="edit") Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    CameraIconButton(if(selectedPhotos.isNotEmpty()) CameraIcons.Close else CameraIcons.Back,if(selectedPhotos.isNotEmpty()) tr(Texts.string.cancel) else tr(Texts.string.back),!ui.working) {if(selectedPhotos.isNotEmpty()) selectedPhotos=arrayListOf() else back()}
                    Text(if(selectedPhotos.isNotEmpty()) tr(Texts.string.value_selected, selectedPhotos.size) else when(page) {"library"->tr(Texts.string.library);"edit"->tr(Texts.string.darkroom);else->tr(Texts.string.photo)},fontSize=20.sp,fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f))
                    if(page=="library") {
                        if(selectedPhotos.isNotEmpty()) {
                            CameraIconButton(CameraIcons.SelectAll,tr(Texts.string.select_all),!ui.working) {selectedPhotos=ArrayList(ui.photos.map {it.id})}
                            CameraIconButton(CameraIcons.Delete,tr(Texts.string.delete),!ui.working) {batchDelete=true}
                        } else TextButton(onClick={importDng.launch(arrayOf("*/*"))},enabled=!ui.working) {Text(tr(Texts.string.import_dng))}
                    }
                }
                when(page) {
                    "library"-> {
                        if(ui.photos.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {Text(tr(Texts.string.no_photos_yet_ntake_a_photo_or_import_a_dng_original),color=Muted)}
                        else LazyVerticalGrid(GridCells.Adaptive(110.dp),Modifier.weight(1f),contentPadding=PaddingValues(8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            items(ui.photos,key={it.id}) {entry->Column(Modifier.clipRounded().semantics {contentDescription=tr(Texts.string.open_photo_value, entry.id)}.combinedClickable(enabled=!ui.working,onLongClick={togglePhoto(entry.id)},onLongClickLabel=tr(Texts.string.select_photo),onClick={if(selectedPhotos.isNotEmpty()) togglePhoto(entry.id) else {selectedId=entry.id;versionId=entry.latest?.id.orEmpty();page="photo"}})) {
                                Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xff25272a)).then(if(entry.id in selectedPhotos) Modifier.border(2.dp,Accent) else Modifier),contentAlignment=Alignment.Center) {
                                    entry.latest?.let {GalleryImage(it,model.repository)} ?: Text(tr(Texts.string.dng_nready_to_develop),color=Accent)
                                    if(selectedPhotos.isNotEmpty()) Checkbox(entry.id in selectedPhotos,{if(!ui.working) togglePhoto(entry.id)},enabled=!ui.working,modifier=Modifier.align(Alignment.TopEnd).background(Color.Black.copy(alpha=.6f),CircleShape))
                                }
                                Text(SimpleDateFormat("MM/dd HH:mm",Locale.getDefault()).format(Date(entry.date)),fontSize=11.sp,color=Muted,modifier=Modifier.padding(6.dp))
                            }}
                        }
                    }
                    "photo"-> {
                        if(photo==null) Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {Text(tr(Texts.string.photo_removed))}
                        else {
                            val photoIndex=ui.photos.indexOfFirst {it.id==photo.id}
                            key(photo.id) {ZoomPhoto(version?.file,Modifier.weight(1f).fillMaxWidth(),photoIndex>0,photoIndex<ui.photos.lastIndex) {direction->
                                ui.photos.getOrNull(photoIndex+direction)?.let {target->selectedId=target.id;versionId=target.latest?.id.orEmpty()}
                            }}
                            Column(Modifier.fillMaxWidth().heightIn(max=200.dp).verticalScroll(rememberScrollState()).padding(8.dp)) {
                                if(photo.versions.size>1) Row(Modifier.horizontalScroll(rememberScrollState())) {photo.versions.forEachIndexed {i,v->TextButton(onClick={versionId=v.id}) {Text(if(v==version) tr(Texts.string.version_value, i+1) else tr(Texts.string.version_value_2, i+1))}}}
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                                    Button(onClick={model.edit(photo,version,{page=="photo" && selectedId==photo.id}) {page="edit"}},enabled=!ui.working) {Text(tr(Texts.string.edit_and_develop))}
                                    TextButton(onClick={panel="photoActions"}) {Text(tr(Texts.string.more))}
                                }
                            }
                        }
                    }
                    "edit"-> EditorScreen(edit,version?.file,landscape,editExpanded,{editExpanded=!editExpanded},
                        model.editor::change,::back,::saveCopy,!ui.working && !edit.saving,notice={notice()})

                }
            }
        }
        if(ui.working) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        // An overlay never participates in measuring the photo, grid or controls.
        if(page!="camera" && page!="edit") Box(Modifier.align(Alignment.TopCenter).padding(top=56.dp)) {notice()}

        }
    }
    if(batchDelete && selectedPhotos.isNotEmpty()) AlertDialog(onDismissRequest={batchDelete=false},
        title={Text(tr(Texts.string.delete_value_photos, selectedPhotos.size))},
        text={Text(tr(Texts.string.the_selected_originals_and_all_developed_versions_in_the_app_will))},
        confirmButton={TextButton(enabled=!ui.working,onClick={model.deletePhotos(selectedPhotos.toSet());selectedPhotos=arrayListOf();batchDelete=false}) {Text(tr(Texts.string.confirm_delete),color=MaterialTheme.colorScheme.error)}},
        dismissButton={TextButton(onClick={batchDelete=false}) {Text(tr(Texts.string.cancel))}})
    if(panel.isNotEmpty() && !(page=="camera" && isCameraAdjustment(panel))) CameraParameterPanel(landscape && page=="camera",onDismiss={panel=""}) {
        Column(Modifier.fillMaxWidth().then(if(landscape && page=="camera") Modifier.fillMaxHeight() else Modifier.heightIn(max=(LocalConfiguration.current.screenHeightDp*.8f).dp)).verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=28.dp)) {
            when(panel) {
                "settings"->{
                    Text("FILCamera",fontSize=26.sp,fontWeight=FontWeight.Bold);Text(tr(Texts.string.value_raw_film_camera, BuildConfig.VERSION_NAME),color=Muted)
                    Spacer(Modifier.height(20.dp));Text(tr(Texts.string.jpg_95_photos_are_saved_to_the_system_gallery_dng_originals_stay),color=Muted)
                    TextButton(onClick={page="library";panel=""}) {Text(tr(Texts.string.open_library_import_dng))}
                    TextButton(onClick={panel="license"}) {Text(tr(Texts.string.open_source_licenses_and_attribution))}
                    TextButton(onClick={panel="developer"}) {Text(tr(Texts.string.developer_options))}
                }
                "license"->{Text(tr(Texts.string.open_source_licenses),fontSize=24.sp);val notice by produceState("") {value=withContext(Dispatchers.IO) {context.assets.open("third-party-notices.txt").bufferedReader().use {it.readText()}}};Text(notice,fontSize=12.sp)}
                "developer"->{Text(tr(Texts.string.diagnostics),fontSize=24.sp);var comparison by remember {mutableStateOf(model.camera.comparison)}
                    Row(verticalAlignment=Alignment.CenterVertically) {Text(tr(Texts.string.save_preview_comparisons),Modifier.weight(1f));Switch(comparison,{comparison=it;model.camera.comparison=it})}
                    Text(tr(Texts.string.off_by_default_adds_shutter_delay_and_two_comparison_jpgs),color=Muted)
                    TextButton(onClick={model.camera.regenerate()}) {Text(tr(Texts.string.rebuild_preview_lut))};Text(camera.diagnostics,fontSize=12.sp,color=Muted)
                }
                "photoActions"->if(photo!=null) {
                    Text(tr(Texts.string.photo_actions),fontSize=22.sp)
                    if(version!=null) {
                        TextButton(onClick={val uri=FileProvider.getUriForFile(context,context.packageName+".photos",version.file);context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {type="image/jpeg";putExtra(Intent.EXTRA_STREAM,uri);clipData=android.content.ClipData.newRawUri(tr(Texts.string.photo),uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},tr(Texts.string.share_photo)));panel=""}) {Text(tr(Texts.string.share_jpg))}
                        TextButton(onClick={model.publish(version);panel=""}) {Text(tr(Texts.string.save_to_system_gallery))}
                        TextButton(onClick={deletion="version";panel=""}) {Text(tr(Texts.string.delete_this_developed_version))}
                    }
                    TextButton(onClick={exportId=photo.id;exportDng.launch("FILCamera_${photo.id}.dng");panel=""}) {Text(tr(Texts.string.export_dng_original))}
                    TextButton(onClick={deletion="photo";panel=""}) {Text(tr(Texts.string.delete_original_and_all_versions),color=MaterialTheme.colorScheme.error)}
                }
            }
        }
    }
    if(deletion.isNotEmpty() && photo!=null) AlertDialog(onDismissRequest={deletion=""},title={Text(if(deletion=="photo") tr(Texts.string.delete_this_photo) else tr(Texts.string.delete_this_version))},text={Text(if(deletion=="photo") tr(Texts.string.the_in_app_dng_original_and_all_developed_versions_will_be_perman) else tr(Texts.string.only_this_developed_version_will_be_deleted_the_dng_original_and))},confirmButton={TextButton(onClick={model.delete(photo,if(deletion=="version") version else null);if(deletion=="photo") page="library";deletion=""}) {Text(tr(Texts.string.delete))}},dismissButton={TextButton(onClick={deletion=""}) {Text(tr(Texts.string.cancel))}})
}

private fun Modifier.clipRounded()=this.then(Modifier.background(Color(0xff25272a),RoundedCornerShape(10.dp)))
@Suppress("DEPRECATION") private fun rotation(context: android.content.Context)=when((context as android.app.Activity).windowManager.defaultDisplay.rotation) {Surface.ROTATION_90->90;Surface.ROTATION_180->180;Surface.ROTATION_270->270;else->0}
@Composable internal fun CameraPreview(model: CameraViewModel) {
    val context=LocalContext.current;val owner=LocalLifecycleOwner.current
    var view by remember {mutableStateOf<io.github.c52e.filcamera.preview.FilmPreviewView?>(null)}
    var resumeGeneration by remember {mutableIntStateOf(0)}
    var showing by remember {mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    DisposableEffect(owner,model) {
        val orientation=object: android.view.OrientationEventListener(context.applicationContext) {
            override fun onOrientationChanged(degrees: Int) {model.captureOrientation.update(degrees)}
        }
        fun start() {if(orientation.canDetectOrientation()) orientation.enable()}
        val observer=LifecycleEventObserver {_,event->when(event) {
            Lifecycle.Event.ON_RESUME->start()
            Lifecycle.Event.ON_PAUSE->orientation.disable()
            else->Unit
        }}
        owner.lifecycle.addObserver(observer)
        if(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        onDispose {orientation.disable();owner.lifecycle.removeObserver(observer)}
    }
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver {_,event->when(event) {Lifecycle.Event.ON_RESUME->showing=true;Lifecycle.Event.ON_PAUSE->{resumeGeneration++;showing=false;view?.let {model.camera.stop(it)}};else->Unit}}
        owner.lifecycle.addObserver(observer);onDispose {owner.lifecycle.removeObserver(observer);view?.let {model.camera.stop(it)}}
    }
    DisposableEffect(context) {
        val displays=context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val listener=object: android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = Unit
            override fun onDisplayRemoved(id: Int) = Unit
            override fun onDisplayChanged(id: Int) {view?.let {if(it.displayRotationDegrees!=rotation(context)) model.camera.clearTapFocus();it.displayRotationDegrees=rotation(context)}}
        }
        displays.registerDisplayListener(listener,android.os.Handler(android.os.Looper.getMainLooper()))
        onDispose {displays.unregisterDisplayListener(listener)}
    }
    val focus by model.camera.focus.collectAsState()
    Box(Modifier.fillMaxSize().onSizeChanged {model.camera.clearTapFocus()}
        .semantics {contentDescription=tr(Texts.string.viewfinder_tap_to_focus_and_meter);onClick(tr(Texts.string.focus_and_meter_at_center)) {
            view?.let {model.camera.tapFocus(it.width*.5f,it.height*.5f)};true
        }}.pointerInput(model) {detectTapGestures {model.camera.tapFocus(it.x,it.y)}}) {
        if(showing) key(resumeGeneration) {AndroidView(factory={model.camera.createPreview(it,rotation(context)).also {created->view=created}},modifier=Modifier.fillMaxSize(),update={it.displayRotationDegrees=rotation(context)})}
        focus?.let {marker->
            val color=when(marker.status.phase) {
                io.github.c52e.filcamera.camera.FocusPhase.FOCUSED->Color(0xff9ddeac)
                io.github.c52e.filcamera.camera.FocusPhase.FAILED,io.github.c52e.filcamera.camera.FocusPhase.TIMEOUT->Color(0xffffb28a)
                else->Accent
            }
            Canvas(Modifier.fillMaxSize().semantics {contentDescription=marker.status.phase.label}) {
                val radius=26.dp.toPx();val center=Offset(marker.x*size.width,marker.y*size.height)
                drawRect(color,center-Offset(radius,radius),Size(radius*2,radius*2),style=Stroke(1.5.dp.toPx()))
                drawCircle(color,2.dp.toPx(),center)
            }
            Text(marker.status.phase.label,Modifier.align(Alignment.TopCenter).padding(12.dp)
                .background(Color.Black.copy(alpha=.5f),RoundedCornerShape(8.dp)).padding(horizontal=10.dp,vertical=4.dp),color=color,fontSize=12.sp)
        }
    }
}
@Composable internal fun PhotoImage(file: File?,modifier: Modifier=Modifier,maxEdge: Int=2048) {
    val bitmap by produceState<android.graphics.Bitmap?>(null,file?.path,file?.lastModified(),maxEdge) {
        value=withContext(Dispatchers.IO) {file?.takeIf {it.isFile}?.let {
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeFile(it.path,bounds)
            var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>maxEdge) sample*=2
            BitmapFactory.decodeFile(it.path,BitmapFactory.Options().apply {inSampleSize=sample})
        }}
    }
    bitmap?.let {Image(it.asImageBitmap(),tr(Texts.string.photo),modifier)} ?: Box(modifier,contentAlignment=Alignment.Center) {Text("RAW",color=Muted)}
}
@Composable private fun GalleryImage(version: PhotoRepository.Version,repository: PhotoRepository) {
    var thumbnail by remember(version.file.path) {mutableStateOf<File?>(null)}
    LaunchedEffect(version.file.path) {thumbnail=withContext(Dispatchers.IO) {runCatching {repository.thumbnail(version)}.getOrNull()}}
    PhotoImage(thumbnail,Modifier.fillMaxSize(),512)
}
