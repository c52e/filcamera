package io.github.c52e.filcamera

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.c52e.filcamera.camera.CameraController
import io.github.c52e.filcamera.camera.CameraInfo
import io.github.c52e.filcamera.camera.*
import io.github.c52e.filcamera.preview.FilmPreviewView
import io.github.c52e.filcamera.preview.PreviewSnapshot
import io.github.c52e.filcamera.imaging.FilmRecipe as CaptureRecipe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

class CameraCoordinator(private val context: Context,private val repository: PhotoRepository,private val changed: ()->Unit) {
    data class FocusMarker(val x: Float,val y: Float,val status: TapFocusStatus)
    private val focusMutable=MutableStateFlow<FocusMarker?>(null)
    val focus=focusMutable.asStateFlow()
    private var focusId=0L
    fun tapFocus(x: Float,y: Float) {
        val s=mutable.value;val preview=view ?: return
        if(!active || !s.ready || s.busy || s.switching || s.framingPending) return
        val point=preview.focusPoint(x,y) ?: return
        val id=++focusId
        focusMutable.value=FocusMarker(x/preview.width,y/preview.height,TapFocusStatus(id,FocusPhase.SCANNING))
        controller.tapFocus(point.x,point.y,id,switchSerial,s.framing?.scale ?: 1f)
    }
    fun clearTapFocus() {
        focusId++;focusMutable.value=null;controller.cancelTapFocus()
    }
    data class State(val ready: Boolean=false,val busy: Boolean=false,val updating: Boolean=false,val message: String=tr(Texts.string.starting_camera),val info: CameraInfo?=null,val ev: Int=0,val diagnostics: String="",val completed: String="",val stage: String="",val sourceStarts: Int=0,val lutRequests: Int=0,val options: List<FocalOption> = emptyList(),val selectedFocal: String="",val switching: Boolean=false,val targetMm: Double?=null,val framing: CaptureFraming?=null,val framingPending: Boolean=false,val whiteBalance: io.github.c52e.filcamera.camera.WhiteBalanceStatus=io.github.c52e.filcamera.camera.WhiteBalanceStatus())
    private var catalog=LensCatalog(context.getSystemService(android.hardware.camera2.CameraManager::class.java))
    private val unavailable=mutableSetOf<String>()
    private fun options()=FocalOptions.create(catalog.entries.filter {it.route.key !in unavailable})
    private var selected=options().firstOrNull {it.targetMm==null && it.lens?.route?.lensId==catalog.defaultLensId}
    private var confirmed: FocalOption?=null
    private var switchSerial=0L
    private var switchError=""
    private val mutable=MutableStateFlow(State(options=options(),selectedFocal=selected?.id.orEmpty()));val state=mutable.asStateFlow()
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var active=false
    @Volatile private var generation=0L
    @Volatile private var request=0L
    @Volatile private var revision=0L
    private var needsLut=true
    private var frames=false
    private var framingRevision=0L
    private var observedFocal: Double?=null
    private var configuredReady=false
    private var view: FilmPreviewView?=null
    private var effect=FilmTableEffect()
    private var recipe=runCatching {FilmRecipe.load(context)}.getOrDefault(FilmRecipe())
    @Volatile var comparison=false
    private val lock=Any()
    private val previewResults=LinkedHashMap<Long,android.hardware.camera2.CaptureResult>()
    private data class Shot(val id: String,val recipe: FilmRecipe,val timing: CaptureTiming,val metadata: String,val snapshot: PreviewSnapshot?,val framing: CaptureFraming?)
    private var shot: Shot?=null
    @Volatile private var saving=false
    private fun update(block: (State)->State) {main.post {mutable.value=block(mutable.value)}}
    private val controller: CameraController=CameraController(context,{s-> main.post {
        if(!active || s.requestTag!=switchSerial) return@post
        if(s.failedRoute!=null) {switchFailed(s.failedRoute!!,s.unsupported,s.message);return@post}
        val old=mutable.value
        configuredReady=s.ready && s.info?.raw==true && !s.busy
        if(!s.busy && !saving && old.busy) synchronized(lock) {shot=null}
        mutable.value=old.copy(ready=active && frames && configuredReady,info=s.info,busy=saving || s.busy,
            message=if(saving || s.ready) old.message else s.message)
    }},{frame->
        val item=synchronized(lock) {shot.also {shot=null}}
        if(item==null) {frame.close();controllerFinished(tr(Texts.string.capture_canceled))}
        else {
            val matchedPreview=item.snapshot?.let {snapshot->synchronized(lock) {previewResults[snapshot.timestampNs]}}
            saving=true;item.timing.mark("rawArrived")
            FilmWorker.execute {
                item.timing.mark("finishWorkerStarted")
                try {
                    val dir=PairCaptureStore(context).finish(item.id,item.snapshot,matchedPreview,frame,item.recipe,item.timing,item.metadata,item.framing)
                    val photo=item.timing.stage("catalogUpdate") {repository.scan().first {it.id==dir.name}}
                    val warning=try {item.timing.stage("albumSave") {repository.publish(checkNotNull(photo.latest))};null} catch(e: Exception) {tr(Texts.string.photo_retained_gallery_save_failed_retry_from_the_photo_page)}
                    val delivered=JSONObject(photo.record.readText()).put("deliveryTiming",item.timing.json()).put("albumStatus",warning ?: "saved")
                    PhotoRepository.atomic(photo.record,delivered)
                    update {it.copy(completed=dir.name,message=warning ?: tr(Texts.string.saved_1f_s).format(item.timing.json().getDouble("totalMs")/1000))}
                } catch(e: Exception) {update {it.copy(message=tr(Texts.string.development_failed_saved_dng_retained_value, e.message))}}
                finally {frame.close();saving=false;controllerFinished(tr(Texts.string.capture_complete));main.post {changed();if(active && needsLut) scheduleLut()}}
            }
        }
    },null,{status->update {it.copy(whiteBalance=status)}},{tag,result->
        if(comparison && tag==switchSerial) result[android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP]?.let {timestamp->
            synchronized(lock) {
                previewResults[timestamp]=result
                while(previewResults.size>180) previewResults.remove(previewResults.keys.first())
            }
        }
        val focal=result[android.hardware.camera2.CaptureResult.LENS_FOCAL_LENGTH]?.toDouble()
        main.post {if(active && tag==switchSerial && focal!=null && focal!=observedFocal) {observedFocal=focal;refreshFraming()}}
    },{tag,status->main.post {
        val marker=focusMutable.value
        if(active && tag==switchSerial && status.id==focusId && marker!=null)
            focusMutable.value=if(status.phase==FocusPhase.IDLE) null else marker.copy(status=status)
    }})
    fun whiteBalance(manual: Boolean,kelvin: Int?=null) {
        if(mutable.value.busy || mutable.value.switching || (manual && !mutable.value.whiteBalance.supported)) return
        mutable.value=mutable.value.copy(whiteBalance=mutable.value.whiteBalance.copy(pending=true,error=""))
        controller.setWhiteBalance(manual,kelvin)
    }
    private fun refreshFraming() {
        clearTapFocus()
        val s=mutable.value;val info=s.info ?: return
        val optics=info.optics?.let {if(observedFocal!=null) it.copy(focalMm=observedFocal) else it}
        val w=optics?.width ?: info.still.width;val h=optics?.height ?: info.still.height
        val target=s.targetMm?.takeIf {optics?.equivalentMm?.let {native->it>native}==true}
        val selectedFraming=CaptureFraming.create(w,h,target,optics).copy(lens=info.route)
        val rev=++framingRevision;val gen=generation
        mutable.value=s.copy(targetMm=target,framing=selectedFraming,framingPending=true)
        view?.setCenterCrop(selectedFraming.scale) {main.post {
            if(active && generation==gen && framingRevision==rev) {
                frames=true;confirmed=selected
                mutable.value=mutable.value.copy(framingPending=false,switching=false,ready=configuredReady && !saving,message=switchError)
                if(needsLut && !mutable.value.updating) scheduleLut()
            }
        }}
    }
    fun focal(target: Double?) { // Retain the test/legacy entry point; product UI selects an identity.
        mutable.value.options.firstOrNull {it.targetMm==target && (target!=null || it.lens?.route==selected?.lens?.route)}?.let {selectFocal(it.id)}
    }
    fun selectFocal(id: String) {
        if(mutable.value.busy || saving) return
        val option=options().firstOrNull {it.id==id && it.lens!=null} ?: return
        val same=selected?.lens?.route==option.lens?.route
        selected=option;switchError=""
        mutable.value=mutable.value.copy(selectedFocal=id,targetMm=option.targetMm)
        if(same && !mutable.value.switching && configuredReady) refreshFraming() else switchLens()
    }
    private fun switchLens() {
        synchronized(lock) {previewResults.clear()}
        clearTapFocus()
        val next=view ?: return
        val serial=++switchSerial;frames=false;configuredReady=false;observedFocal=null;framingRevision++
        mutable.value=mutable.value.copy(ready=false,switching=true,framingPending=true,info=null,message=tr(Texts.string.switching_lens))
        controller.stop()
        next.replaceCameraTarget {texture,surface,release->main.post {attachTarget(next,serial,texture,surface,release)}}
    }
    private fun attachTarget(next: FilmPreviewView,serial: Long,texture: android.graphics.SurfaceTexture,surface: android.view.Surface,release: ()->Unit) {
        val route=selected?.lens?.route
        if(!active || next!==view || serial!=switchSerial || route==null) {
            release();if(active && route==null) mutable.value=mutable.value.copy(framingPending=false,switching=false,message=tr(Texts.string.no_supported_rear_raw_lens_found_you_can_still_browse_and_edit_ph))
            return
        }
        mutable.value=mutable.value.copy(sourceStarts=mutable.value.sourceStarts+1)
        // Primary camera only: measured improvement. Other lenses retained ISP after A/B regression.
        controller.setControlledPreviewTone(route.lensId==catalog.defaultLensId)
        controller.start(texture,surface,release,true,route,serial) {info->
            main.post {if(active && serial==switchSerial && next===view) {
                next.sourceWidth=info.preview.width;next.sourceHeight=info.preview.height;next.realtimeTimestamps=info.realtimeTimestamps
                mutable.value=mutable.value.copy(info=info,ev=info.ev);refreshFraming()
            }}
        }
        main.postDelayed({if(active && serial==switchSerial && !frames) switchFailed(route,false,tr(Texts.string.lens_startup_timed_out_please_retry))},10000)
    }
    private fun switchFailed(route: LensRoute,unsupported: Boolean,message: String) {
        if(route!=selected?.lens?.route) return
        switchSerial++;framingRevision++;frames=false;configuredReady=false;controller.stop()
        if(unsupported) unavailable+=route.key
        val choices=options();val old=confirmed?.let {last->choices.firstOrNull {it.id==last.id && it.lens!=null && it.lens?.route!=route}}
        val retry=choices.firstOrNull {it.id==selected?.id && it.lens!=null && it.lens?.route!=route}
        val fallback=old ?: retry ?: if(unsupported) choices.firstOrNull {it.lens!=null && it.lens?.route!=route} else null
        switchError=message+(if(fallback!=null) tr(Texts.string.available_lens_restored) else "")
        mutable.value=mutable.value.copy(options=choices,ready=false,switching=false,framingPending=false,message=switchError)
        if(fallback!=null) {
            selected=fallback;mutable.value=mutable.value.copy(selectedFocal=fallback.id,targetMm=fallback.targetMm);switchLens()
        } else if(unsupported) {selected=null;mutable.value=mutable.value.copy(selectedFocal="")}
    }
    private fun controllerFinished(message: String): Unit {controller.processingFinished(message)}
    fun createPreview(uiContext: Context,rotation: Int): FilmPreviewView {
        stop()
        // Permission/service failures during initial discovery must not require clearing app data.
        if(catalog.entries.isEmpty()) {
            catalog=LensCatalog(context.getSystemService(android.hardware.camera2.CameraManager::class.java))
            selected=options().firstOrNull {it.targetMm==null && it.lens?.route?.lensId==catalog.defaultLensId}
            mutable.value=mutable.value.copy(options=options(),selectedFocal=selected?.id.orEmpty(),targetMm=selected?.targetMm)
        }
        active=true;generation++;frames=false;needsLut=true;effect=FilmTableEffect()
        mutable.value=mutable.value.copy(ready=false,diagnostics="",message=tr(Texts.string.starting_camera))
        val current=generation;switchSerial++
        frames=false;configuredReady=false;observedFocal=null
        lateinit var next: FilmPreviewView
        next=FilmPreviewView(uiContext,{texture,surface,release->main.post {
            if(active && current==generation && view===next) {
                val serial=++switchSerial;frames=false;configuredReady=false;observedFocal=null;framingRevision++
                mutable.value=mutable.value.copy(ready=false,framingPending=true)
                attachTarget(next,serial,texture,surface,release)
            } else release()
        }},
        {metrics->main.post {if(active && current==generation) mutable.value=mutable.value.copy(diagnostics=metrics+" · "+effect.lutSource+" · "+effect.lutKey)}},
        {error->update {it.copy(message=error,ready=false,framingPending=false,switching=false)}},effect)
        next.displayRotationDegrees=rotation;view=next;return next
    }
    fun stop(expected: FilmPreviewView?=null) {
        if(expected!=null && view!==expected) return
        clearTapFocus()
        active=false;generation++;switchSerial++;main.removeCallbacks(bake)
        if(request>0) FilmEngine.cancelBake(request);request=0;bakingId=0
        synchronized(lock) {shot=null;previewResults.clear()};controller.stop();view?.cancelSnapshot();view?.onPause();view=null
        mutable.value=mutable.value.copy(ready=false,updating=false,busy=saving)
        FilmWorker.execute {FilmEngine.close()}
    }
    fun close() {stop();controller.close()}
    internal fun resetFrameProbe()=effect.resetFrameProbe()
    internal fun setControlledPreviewToneForTest(enabled: Boolean)=controller.setControlledPreviewTone(enabled)
    internal fun frameProbe()=effect.frameProbe()
    fun exposure(value: Int) {val info=mutable.value.info ?: return;val v=value.coerceIn(info.evMin,info.evMax);controller.setExposureCompensation(v);mutable.value=mutable.value.copy(ev=v)}
    fun changeRecipe(value: FilmRecipe) {
        val colorChanged=recipe.colorHash!=value.colorHash;recipe=value
        if(colorChanged) {revision++;needsLut=true;if(request>0) FilmEngine.cancelBake(request);scheduleLut()}
    }
    private val bake=Runnable {bakeLut()}
    private fun scheduleLut() {
        if(!active || !frames) return
        mutable.value=mutable.value.copy(updating=true)
        main.removeCallbacks(bake);main.postDelayed(bake,200)
    }
    fun regenerate() {needsLut=true;force=true;revision++;scheduleLut()}
    private var force=false
    private var bakingId=0L
    private fun bakeLut() {
        if(!active || saving || mutable.value.busy || bakingId!=0L) return
        mutable.value=mutable.value.copy(lutRequests=mutable.value.lutRequests+1)
        val id=android.os.SystemClock.elapsedRealtimeNanos();request=id;bakingId=id
        val gen=generation;val rev=revision;val selected=recipe;val forced=force;force=false;needsLut=false
        fun current()=active && generation==gen && request==id && revision==rev
        fun finish(error: String?=null) {main.post {
            if(bakingId==id) bakingId=0
            if(generation==gen && request==id) mutable.value=mutable.value.copy(updating=false,message=error ?: "")
            if(active && needsLut) scheduleLut()
        }}
        val protected=effect.lutKey
        FilmWorker.execute {
            try {
                if(!current()) {finish();return@execute}
                val store=PreviewLutStore(context,recipe=selected,retainSession=true)
                val table=(if(forced) null else store.cached()) ?: store.bake(id,protected,::current)
                if(!current()) {finish();return@execute}
                view?.queueEvent {
                    try {if(current()) effect.replaceLut(table.lut);finish()}
                    catch(e: Exception) {finish(tr(Texts.string.preview_not_updated_capture_uses_the_selected_recipe_value, e.message))}
                } ?: finish()
            } catch(e: Exception) {finish(if(current()) tr(Texts.string.preview_not_updated_keeping_current_view_value, e.message) else null)}
        }
    }
    fun capture(rotation: Int) {
        val s=mutable.value;if(!active || !s.ready || s.switching || s.busy || s.updating || s.framingPending || s.whiteBalance.pending) return
        if(android.os.StatFs(context.filesDir.path).availableBytes<300L*1024*1024) {mutable.value=s.copy(message=tr(Texts.string.low_storage_keep_at_least_300_mb_free));return}
        val frozen=recipe;mutable.value=s.copy(busy=true,ready=false,message=tr(Texts.string.capturing))
        val timing=CaptureTiming {stage->update {it.copy(stage=stage,message=when(stage) {"rawDevelop"->tr(Texts.string.developing);"nativeCall"->tr(Texts.string.rendering_film_photo);"albumSave"->tr(Texts.string.saving_to_gallery);else->tr(Texts.string.processing_photo)})}}
        val id="pair_"+UUID.randomUUID()
        fun submit(snapshot: PreviewSnapshot?,metadata: String) {
            if(!active) {update {it.copy(busy=false)};return}
            synchronized(lock) {shot=Shot(id,frozen,timing,metadata,snapshot,s.framing)}
            if(snapshot!=null) FilmWorker.execute {runCatching {PairCaptureStore(context).preview(id,snapshot)}}
            controller.capture(CaptureRecipe(),rotation)
        }
        if(comparison) {
            if(view?.snapshotOnce {snapshot->main.post {if(snapshot!=null) submit(snapshot,JSONObject(snapshot.effectMetadata).toString()) else update {it.copy(busy=false,ready=true,message=tr(Texts.string.preview_comparison_not_ready_please_retry))}}}!=true)
                mutable.value=mutable.value.copy(busy=false,ready=true,message=tr(Texts.string.preview_comparison_not_ready))
            main.postDelayed({view?.cancelSnapshot()},3000)
        } else {
            // No readback; metadata describes the table actually on screen at shutter time.
            submit(null,JSONObject(effect.metadata()).toString())
        }
    }
}

