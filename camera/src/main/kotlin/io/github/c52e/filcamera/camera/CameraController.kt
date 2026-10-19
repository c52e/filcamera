package io.github.c52e.filcamera.camera

import io.github.c52e.filcamera.imaging.RawLimits

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.MeteringRectangle
import android.graphics.Rect
import kotlin.math.roundToInt
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import io.github.c52e.filcamera.imaging.FilmRecipe
import io.github.c52e.filcamera.imaging.TimestampPairer
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

data class CameraInfo(val id: String, val raw: Boolean, val preview: Size, val still: Size, val sensorRotation: Int, val front: Boolean, val evMin: Int, val evMax: Int, val evStep: Float, val realtimeTimestamps: Boolean, val optics: CameraOptics?=null, val route: LensRoute?=null, val ev: Int=0)
data class CameraState(val ready: Boolean = false, val busy: Boolean = false, val message: String = tr(Texts.string.camera_has_not_started), val info: CameraInfo? = null, val failedRoute: LensRoute?=null, val unsupported: Boolean=false, val requestTag: Long=0)

/** The receiver owns the Image until close(). Retiring the camera cannot invalidate that Image. */
class CapturedFrame internal constructor(
    val image: Image, val result: CaptureResult, val characteristics: CameraCharacteristics,
    val rotation: Int, val recipe: FilmRecipe, val whiteBalance: WhiteBalance=WhiteBalance(), val lens: LensRoute?=null, private val release: () -> Unit
) : Closeable {
    private val closed = AtomicBoolean()
    override fun close() { if (closed.compareAndSet(false,true)) release() }
}

/** Reader close is deferred until all acquired Images have been returned by the IO worker. */
private class ReaderOwner(val reader: ImageReader) {
    private var held = 0
    private var retired = false
    @Synchronized fun acquire(): Lease? {
        if (retired) return null
        val image = reader.acquireNextImage() ?: return null
        held++
        return Lease(image) { synchronized(this) { image.close(); held--; if (retired && held == 0) reader.close() } }
    }
    @Synchronized fun retire() { if (!retired) { retired = true; if (held == 0) reader.close() } }
    class Lease(val image: Image, private val release: () -> Unit) : Closeable {
        private val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false,true)) release() }
    }
}

/** All Camera2 state belongs to one Handler. Callbacks include a generation to reject stale opens. */
class CameraController(context: Context, private val state: (CameraState) -> Unit, private val captured: (CapturedFrame) -> Unit,
    private val previewResult: ((CaptureResult) -> Unit)? = null,
    private val whiteBalanceChanged: (WhiteBalanceStatus)->Unit = {},
    private val sensorSample: (Long,CaptureResult)->Unit = {_,_->},
    private val focusChanged: (Long,TapFocusStatus)->Unit = {_,_->}) : Closeable {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("Camera2").apply { start() }
    private val handler = Handler(thread.looper)
    private var generation = 0
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var owner: ReaderOwner? = null
    private var surface: Surface? = null
    private var chars: CameraCharacteristics? = null
    private var info: CameraInfo? = null
    private var pending = false
    private var shooting = false
    private var processing = false
    private var recipe = FilmRecipe()
    private var rotation = 0
    private var wb=WhiteBalance()
    private var shotWb=WhiteBalance()
    private var wbCapability: NativeColorTemperature.Capability?=null
    private var wbCalibration: io.github.c52e.filcamera.imaging.CalibratedWhiteBalance.Calibration?=null
    private val wbCalibrations=mutableMapOf<String,io.github.c52e.filcamera.imaging.CalibratedWhiteBalance.Calibration?>()
    private val wbRejected=mutableSetOf<String>()
    private var wbRevision=0L
    private var wbError=""
    // Instrumentation fault injection only; ignored in non-debuggable applications.
    @Volatile private var suppressManualWbConfirmationForTest=false
    private val wbTestOverridesAllowed=context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    private var wbPending=false
    private var wbCamera: String?=null
    private val whiteBalances=mutableMapOf<String,WhiteBalance>()
    private var route: LensRoute?=null
    private var requestTag=0L
    private data class PreviewTag(val wb: WhiteBalance,val focus: Long,val shot: Long,val whiteBalanceRevision: Long)
    private var focusRevision=0L
    private var shotRevision=0L
    private var tapId=0L
    private var tapAuto=false
    private var tapPhase=FocusPhase.IDLE
    private var tapRegion: MeteringRectangle?=null
    private var tapTimeout: Runnable?=null
    private var tapRestore: Runnable?=null
    private var triggerFrame=Long.MAX_VALUE
    private var latestResult: CaptureResult?=null
    private var lastFocusStatus=TapFocusStatus()
    private var physicalKeys: Set<CaptureRequest.Key<*>> = emptySet()
    private var logicalChars: CameraCharacteristics?=null
    private var probeRegion: MeteringRectangle?=null
    private var meteringBounds=Rect()
    private val regionMappings=mutableMapOf<CaptureRequest.Key<Array<MeteringRectangle>>,MeteringGeometry.Mapping>()
    private fun rectBox(r: Rect)=MeteringGeometry.Box(r.left,r.top,r.right,r.bottom)
    private fun boxRegion(b: MeteringGeometry.Box)=MeteringRectangle(Rect(b.left,b.top,b.right,b.bottom),MeteringRectangle.METERING_WEIGHT_MAX)
    private fun needsMapping(key: CaptureRequest.Key<Array<MeteringRectangle>>)=route?.physicalId!=null && key !in physicalKeys
    private fun regionControl(builder: CaptureRequest.Builder,key: CaptureRequest.Key<Array<MeteringRectangle>>) {
        val region=tapRegion?.takeUnless {key==CaptureRequest.CONTROL_AF_REGIONS && !tapAuto}
        if(region==null || !needsMapping(key)) {focusControl(builder,key,arrayOf(region ?: MeteringRectangle(0,0,0,0,0)));return}
        val mapped=probeRegion ?: regionMappings[key]?.inverse(rectBox(region.rect),rectBox(logicalBounds()))?.let(::boxRegion)
            ?: throw IllegalArgumentException(tr(Texts.string.selected_position_is_outside_the_logical_camera_control_area))
        builder.set(key,arrayOf(mapped))
    }
    private fun logicalBounds(): Rect {
        val c=logicalChars ?: error(tr(Texts.string.missing_logical_camera_information))
        val active=if(latestResult?.get(CaptureResult.DISTORTION_CORRECTION_MODE)==CaptureResult.DISTORTION_CORRECTION_MODE_OFF)
            c[CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE] ?: c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]
            else c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]
        return requireNotNull(active).let {Rect(0,0,it.width(),it.height())}
    }
    private fun startTapScan() {
        focusRevision++;triggerFrame=Long.MAX_VALUE
        if(tapAuto) session?.capture(previewRequest().apply {
            focusControl(this,CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_START)
        }.build(),previewCallback,handler)
        repeat()
        tapTimeout?.let(handler::removeCallbacks)
        if(tapAuto) {
            val revision=focusRevision
            tapTimeout=Runnable {if(revision==focusRevision && tapPhase==FocusPhase.SCANNING) {
                tapPhase=FocusPhase.TIMEOUT;reportFocus(latestResult);if(!pending) armRestore()
            }}.also {handler.postDelayed(it,2000)}
        } else {tapPhase=FocusPhase.METERING;reportFocus();armRestore()}
    }
    private fun acceptProbe(request: CaptureRequest,result: CaptureResult) {
        fun map(key: CaptureRequest.Key<Array<MeteringRectangle>>,resultKey: CaptureResult.Key<Array<MeteringRectangle>>) {
            if(!needsMapping(key)) return
            val sent=request[key]?.singleOrNull() ?: error(tr(Texts.string.missing_metering_region_request))
            val actual=result[resultKey]?.singleOrNull() ?: error(tr(Texts.string.device_did_not_return_a_physical_metering_region))
            // Clipped probes cannot establish a valid affine mapping.
            val r=actual.rect
            require(actual.meteringWeight>0 && r.width()>1 && r.height()>1 && r.left>0 && r.top>0 &&
                r.right<meteringBounds.right && r.bottom<meteringBounds.bottom) {tr(Texts.string.physical_metering_mapping_is_unavailable)}
            regionMappings[key]=MeteringGeometry.Mapping(rectBox(sent.rect),rectBox(r))
        }
        try {
            if(tapAuto) map(CaptureRequest.CONTROL_AF_REGIONS,CaptureResult.CONTROL_AF_REGIONS)
            if(aeRegions()) map(CaptureRequest.CONTROL_AE_REGIONS,CaptureResult.CONTROL_AE_REGIONS)
            probeRegion=null;startTapScan()
        } catch(e: Exception) {
            restoreFocus(false);tapPhase=FocusPhase.UNAVAILABLE;reportFocus();armRestore()
        }
    }
    private fun afModes()=chars?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
    private fun afRegions()=(chars?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0)>0
    private fun aeRegions()=(chars?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0)>0
    private fun reportFocus(result: CaptureResult?=null) {
        fun region(key: CaptureResult.Key<Array<MeteringRectangle>>)=result?.get(key)?.firstOrNull()?.let {
            listOf(it.x,it.y,it.width,it.height,it.meteringWeight)
        } ?: emptyList()
        val status=TapFocusStatus(tapId,tapPhase,result?.get(CaptureResult.CONTROL_AF_STATE),
            result?.get(CaptureResult.CONTROL_AF_MODE),region(CaptureResult.CONTROL_AF_REGIONS),region(CaptureResult.CONTROL_AE_REGIONS),
            tapRegion?.let {listOf(it.x,it.y,it.width,it.height,it.meteringWeight)} ?: emptyList())
        if(status!=lastFocusStatus) {lastFocusStatus=status;focusChanged(requestTag,status)}
    }
    private fun clearFocus(reportIdle: Boolean=true) {
        tapTimeout?.let(handler::removeCallbacks);tapRestore?.let(handler::removeCallbacks)
        tapTimeout=null;tapRestore=null;focusRevision++;tapRegion=null;probeRegion=null;tapAuto=false;tapPhase=FocusPhase.IDLE
        triggerFrame=Long.MAX_VALUE;if(reportIdle) reportFocus()
    }
    private fun armRestore() {
        tapRestore?.let(handler::removeCallbacks)
        val revision=focusRevision
        tapRestore=Runnable {if(revision==focusRevision && !pending) restoreFocus()}.also {handler.postDelayed(it,3000)}
    }
    private fun restoreFocus(reportIdle: Boolean=true) {
        // CANCEL must remain constructible even if the requested point cannot be mapped.
        tapRegion=null;probeRegion=null
        try {
            if(session!=null) session?.capture(previewRequest().apply {
                focusControl(this,CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            }.build(),null,handler)
            clearFocus(reportIdle);if(session!=null) repeat()
        } catch(e: Exception) {clearFocus(reportIdle);emit(tr(Texts.string.could_not_restore_continuous_focus_value, e.message))}
    }
    fun cancelTapFocus() {handler.post {if(!pending) restoreFocus()}}
    fun tapFocus(x: Float,y: Float,id: Long,expectedCamera: Long,windowScale: Float=1f) {handler.post {
        if(session==null || pending || processing || expectedCamera!=requestTag) return@post
        if(!x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) return@post
        try {
            restoreFocus();focusRevision++;tapId=id
            val c=chars ?: return@post;val i=info ?: return@post
            val result=latestResult ?: error(tr(Texts.string.preview_metering_is_not_ready))
            val active=if(result[CaptureResult.DISTORTION_CORRECTION_MODE]==CaptureResult.DISTORTION_CORRECTION_MODE_OFF)
                c[CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE] ?: c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]
                else c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]
            val array=active ?: error(tr(Texts.string.missing_sensor_metering_area))
            // Camera2 AF/AE coordinates are relative to the active array, not the full pixel array.
            val bounds=Rect(0,0,array.width(),array.height())
            meteringBounds=bounds
            val crop=Rect(result[CaptureResult.SCALER_CROP_REGION] ?: bounds)
            check(crop.intersect(bounds)) {tr(Texts.string.invalid_sensor_crop_region)}
            val box=MeteringGeometry.region(x,y,MeteringGeometry.Box(crop.left,crop.top,crop.right,crop.bottom),i.preview.width,i.preview.height,windowScale)
            tapAuto=afRegions() && afModes().contains(CaptureRequest.CONTROL_AF_MODE_AUTO)
            if(!tapAuto && !aeRegions()) {tapPhase=FocusPhase.UNAVAILABLE;reportFocus();armRestore();return@post}
            tapRegion=MeteringRectangle(Rect(box.left,box.top,box.right,box.bottom),MeteringRectangle.METERING_WEIGHT_MAX)
            tapPhase=if(tapAuto) FocusPhase.SCANNING else FocusPhase.METERING
            reportFocus()
            val missing=(tapAuto && needsMapping(CaptureRequest.CONTROL_AF_REGIONS) && CaptureRequest.CONTROL_AF_REGIONS !in regionMappings) ||
                (aeRegions() && needsMapping(CaptureRequest.CONTROL_AE_REGIONS) && CaptureRequest.CONTROL_AE_REGIONS !in regionMappings)
            if(missing) {
                // Some HALs transform global regions into each physical sensor's coordinates, but
                // expose no per-physical request keys. Observe that transform before issuing START.
                val logical=logicalBounds()
                probeRegion=boxRegion(MeteringGeometry.region(.5f,.5f,rectBox(logical),logical.width(),logical.height(),.8f))
                repeat()
                val revision=focusRevision
                tapTimeout=Runnable {if(revision==focusRevision && probeRegion!=null) {
                    restoreFocus(false);tapPhase=FocusPhase.UNAVAILABLE;reportFocus();armRestore()
                }}.also {handler.postDelayed(it,1000)}
            } else startTapScan()
        } catch(e: Exception) {
            android.util.Log.w("Camera2","Tap focus rejected",e)
            restoreFocus(false);tapId=id;tapPhase=if(e is IllegalArgumentException) FocusPhase.UNAVAILABLE else FocusPhase.ERROR;reportFocus();armRestore()
        }
    }}
    private fun <T> focusControl(builder: CaptureRequest.Builder,key: CaptureRequest.Key<T>,value: T) {
        builder.set(key,value)
        route?.physicalId?.takeIf {physicalKeys.contains(key)}?.let {builder.setPhysicalCameraKey(key,value,it)}
    }
    private var wbTimeout: Runnable?=null
    private fun wbRange(): TemperatureRange? = if(wbCamera in wbRejected) null else wbCapability?.range ?: wbCalibration?.let {TemperatureRange(2000,10000)}
    private fun wbSource() = when {wbRange()==null->WhiteBalanceSource.AUTO;wbCapability!=null->WhiteBalanceSource.NATIVE_CCT;else->WhiteBalanceSource.CALIBRATED}
    private fun reportWb(error: String=wbError)=whiteBalanceChanged(WhiteBalanceStatus(wb,wbRange(),wbPending,error,wbSource()))
    private fun rejectWhiteBalance(reason: String) {
        wbCamera?.let {wbRejected.add(it);whiteBalances[it]=WhiteBalance()}
        wb=WhiteBalance();wbRevision++;wbPending=true;wbError=reason+tr(Texts.string.restoring_auto_white_balance)
        reportWb()
        try {repeat();armWbTimeout()} catch(e: Exception) {fail(tr(Texts.string.could_not_restore_auto_white_balance_value, e.message))}
    }
    private fun armWbTimeout() {
        wbTimeout?.let(handler::removeCallbacks)
        val revision=wbRevision
        wbTimeout=Runnable {
            if(wbPending && revision==wbRevision) {
                if(wb.manual) rejectWhiteBalance(tr(Texts.string.this_lens_did_not_accept_manual_white_balance))
                else {wbError=tr(Texts.string.auto_white_balance_was_not_confirmed_reopen_the_camera);reportWb();fail(wbError)}
            }
        }.also {handler.postDelayed(it,3000)}
    }
    private var ev = 0
    private var alive = true
    private var openRequests = 0
    private var targetOwnedByCamera = false
    private var focusTimeout: Runnable? = null
    private var captureTimeout: Runnable? = null
    private var releaseTarget: (() -> Unit)? = null
    private val pairer = TimestampPairer<ReaderOwner.Lease, CaptureResult>(2, { it.close() }) { lease, result ->
        val cc = chars
        if (!pending || cc == null) lease.close() else {
            pending = false
            captureTimeout?.let(handler::removeCallbacks)
            focusTimeout?.let(handler::removeCallbacks)
            processing = true
            emit(tr(Texts.string.saving_and_developing))
            val frame = CapturedFrame(lease.image,result,cc,rotation,recipe,shotWb,route) { lease.close() }
            try { captured(frame) } catch (e: Exception) { frame.close(); processing = false; emit(tr(Texts.string.could_not_start_processing_value, e.message)) }
            pairerClearLater()
        }
    }
    private fun pairerClearLater() { handler.post { pairer.clear() } }
    private fun emit(message: String) = state(CameraState(session != null && latestResult != null, pending || processing, message, info,requestTag=requestTag))

    /** SurfaceTexture is producer-facing; release is called only once the camera has closed. */
    fun start(texture: SurfaceTexture, target: Surface, release: () -> Unit, preferRaw: Boolean = true, selectedRoute: LensRoute?=null, requestTag: Long=0, configured: (CameraInfo) -> Unit) {
        handler.post {
            if (!alive) { release(); return@post }
            stopInternal()
            val gen = generation
            surface = target; releaseTarget = release; route=selectedRoute;this.requestTag=requestTag
            try {
                val choices = manager.cameraIdList.map { it to manager.getCameraCharacteristics(it) }
                val selected = if(selectedRoute!=null) selectedRoute.openId to manager.getCameraCharacteristics(selectedRoute.lensId) else choices.sortedBy { (_, c) ->
                    (if (c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK) 0 else 10) +
                    (if (c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true) 0 else 1)
                }.firstOrNull() ?: error(tr(Texts.string.no_available_camera_found))
                chars = selected.second
                logicalChars=manager.getCameraCharacteristics(selected.first)
                physicalKeys=if(selectedRoute?.physicalId!=null) logicalChars?.availablePhysicalCameraRequestKeys?.toSet() ?: emptySet() else emptySet()
                configure(selected.first, selected.second, texture, preferRaw, configured)
                open(selected.first, gen, texture, configured)
            } catch (e: Exception) { fail(tr(Texts.string.could_not_open_camera_value, e.message)) }
        }
    }

    private fun configure(id: String, c: CameraCharacteristics, texture: SurfaceTexture, allowRaw: Boolean, configured: (CameraInfo) -> Unit) {
        previewTone=PreviewToneCurve.create(c);toneRejected=false;toneMismatches=0
        val identity=route?.lensId ?: id
        if(wbCamera!=identity) {wbCamera?.let {whiteBalances[it]=wb};wb=whiteBalances[identity] ?: WhiteBalance();wbCamera=identity}
        val previousEv=ev*(info?.evStep ?: 0f)
        wbRevision++;wbError=""
        wbCapability=NativeColorTemperature.capability(c,if(route?.physicalId!=null) logicalChars else null)
        if(!wbCalibrations.containsKey(identity)) {
            wbCalibrations[identity]=if(CameraWhiteBalanceCalibration.controllable(c,logicalChars ?: c)) CameraWhiteBalanceCalibration.read(c)?.takeIf {calibration->
                runCatching {(2000..10000 step 50).forEach {io.github.c52e.filcamera.imaging.CalibratedWhiteBalance.solve(calibration,it)}}.isSuccess
            } else null
        }
        wbCalibration=wbCalibrations[identity]
        if(wbRange()?.let {range->wb.kelvin?.let(range::contains) ?: true} != true) wb=WhiteBalance()
        wbPending=wb.manual;reportWb()
        val map = requireNotNull(c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP])
        val rawSizes = if (allowRaw && c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true) map.getOutputSizes(ImageFormat.RAW_SENSOR)?.filter { it.width.toLong()*it.height <= 32_000_000 }?.toTypedArray() else null
        val compatible=if(route!=null) rawSizes?.filter { size ->
            val pixels=c[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE]
            val active=c[CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE]
            size==pixels || (active!=null && size.width==active.width() && size.height==active.height() && active.left%2==0 && active.top%2==0)
        }?.toTypedArray() else rawSizes
        val raw = !compatible.isNullOrEmpty()
        require(route==null || raw) {tr(Texts.string.this_lens_has_no_supported_raw_mode)}
        val sizes = if (raw) compatible!! else requireNotNull(map.getOutputSizes(ImageFormat.JPEG))
        // Standard Camera2 modes only; do not open a stream above the product ceiling.
        val still = sizes.filter { RawLimits.supports(it.width,it.height) }.maxByOrNull { it.width.toLong()*it.height }
            ?: error(tr(Texts.string.effective_raw_exceeds_60_megapixels))
        val previews = map.getOutputSizes(SurfaceTexture::class.java)
        val bounded = previews.filter { it.width <= 1920 && it.height <= 1080 }
        val preview = (bounded.ifEmpty { previews.toList() }).minWith(compareBy<Size> { abs(it.width.toFloat()/it.height-still.width.toFloat()/still.height) }.thenBy { abs(it.width*it.height - 1280*960) })
        val range = c[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE]
        info = CameraInfo(id,raw,preview,still,c[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0,
            c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT,range?.lower ?: 0,range?.upper ?: 0,c[CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP]?.toFloat() ?: 0f,
            c[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE] == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME, CameraOptics.read(c),route)
        ev = (if(info!!.evStep>0f) (previousEv/info!!.evStep).roundToInt() else 0).coerceIn(info!!.evMin,info!!.evMax)
        info=info!!.copy(ev=ev)
        texture.setDefaultBufferSize(preview.width,preview.height)
        owner?.retire()
        val next = ReaderOwner(ImageReader.newInstance(still.width,still.height,if (raw) ImageFormat.RAW_SENSOR else ImageFormat.JPEG,3))
        owner = next
        next.reader.setOnImageAvailableListener({
            try {
                val lease = next.acquire() ?: return@setOnImageAvailableListener
                if (next !== owner || !pending || !shooting) lease.close() else pairer.image(lease.image.timestamp,lease)
            } catch (e: Exception) { abortCapture(tr(Texts.string.could_not_read_photo_value, e.message)) }
        },handler)
        configured(info!!)
    }

    @SuppressLint("MissingPermission")
    private fun open(id: String, gen: Int, texture: SurfaceTexture, configured: (CameraInfo) -> Unit) {
        val targetRelease = releaseTarget
        openRequests++
        targetOwnedByCamera = true
        try { manager.openCamera(id, object : CameraDevice.StateCallback() {
            private var closed = false
            override fun onOpened(camera: CameraDevice) {
                if (gen != generation || !alive) { camera.close(); return }
                device = camera
                createSession(gen, texture, configured)
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); if (gen == generation) fail(tr(Texts.string.camera_disconnected_please_retry)) }
            override fun onError(camera: CameraDevice, error: Int) { camera.close(); if (gen == generation) fail(tr(Texts.string.camera2_error_value_please_retry, error)) }
            override fun onClosed(camera: CameraDevice) {
                if (!closed) { closed = true; targetRelease?.invoke(); openRequests--; maybeQuit() }
            }
        },handler) } catch (e: Exception) { openRequests--; targetOwnedByCamera=false; throw e }
    }

    @Suppress("DEPRECATION")
    private fun createSession(gen: Int, texture: SurfaceTexture, configured: (CameraInfo) -> Unit) {
        val camera = device ?: return
        try {
            val outputs=listOf(requireNotNull(surface),requireNotNull(owner).reader.surface).map { output ->
                OutputConfiguration(output).apply { route?.physicalId?.let { setPhysicalCameraId(it) } }
            }
            camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,outputs,
                java.util.concurrent.Executor { handler.post(it) },object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (gen != generation) { s.close(); return }
                    session = s
                    try { repeat();if(wbPending) armWbTimeout(); emit(if (info!!.raw) tr(Texts.string.raw_gpu_preview) else tr(Texts.string.jpeg_mode_raw_unavailable)) }
                    catch (e: Exception) { fail(tr(Texts.string.preview_failed_value, e.message)) }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    s.close()
                    if (gen != generation) return
                    if (route!=null) fail(tr(Texts.string.device_rejected_this_lens_s_preview_raw_stream_combination),true)
                    else if (info?.raw == true) {
                        try { configure(info!!.id,chars!!,texture,false,configured); createSession(gen,texture,configured) }
                        catch (e: Exception) { fail(tr(Texts.string.stream_configuration_failed_value, e.message)) }
                    } else fail(tr(Texts.string.device_rejected_the_preview_capture_stream_combination))
                }
            }))
        } catch (e: Exception) {
            if(gen!=generation) return
            if(route!=null) {fail(tr(Texts.string.could_not_create_raw_session_value, e.message),e is IllegalArgumentException);return}
            if (gen == generation && info?.raw == true) {
                try { configure(info!!.id,chars!!,texture,false,configured); createSession(gen,texture,configured) }
                catch (fallback: Exception) { fail(tr(Texts.string.could_not_create_session_value, fallback.message)) }
            } else fail(tr(Texts.string.could_not_create_session_value, e.message))
        }
    }

    private fun sensorResult(total: TotalCaptureResult): CaptureResult? = if(route?.physicalId==null) total else total.physicalCameraResults[route!!.physicalId]

    private fun controls(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,ev)
        focusControl(builder,CaptureRequest.CONTROL_AWB_MODE,if(wb.manual) CaptureRequest.CONTROL_AWB_MODE_OFF else CaptureRequest.CONTROL_AWB_MODE_AUTO)
        if(wb.manual) {
            if(wb.source==WhiteBalanceSource.NATIVE_CCT) {
                val native=requireNotNull(wbCapability)
                focusControl(builder,CaptureRequest.COLOR_CORRECTION_MODE,NativeColorTemperature.MODE_CCT)
                focusControl(builder,native.request,requireNotNull(wb.kelvin))
            } else {
                focusControl(builder,CaptureRequest.COLOR_CORRECTION_MODE,CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                focusControl(builder,CaptureRequest.COLOR_CORRECTION_GAINS,android.hardware.camera2.params.RggbChannelVector(wb.gains[0],wb.gains[1],wb.gains[2],wb.gains[3]))
                focusControl(builder,CaptureRequest.COLOR_CORRECTION_TRANSFORM,android.hardware.camera2.params.ColorSpaceTransform(Array(9) {
                    android.util.Rational((wb.transform[it].toDouble()*1000000).toInt(),1000000)
                }))
            }
        }
        val modes = chars?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val af = when { modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) -> CaptureRequest.CONTROL_AF_MODE_AUTO
            else -> CaptureRequest.CONTROL_AF_MODE_OFF }
        focusControl(builder,CaptureRequest.CONTROL_AF_MODE,if(tapAuto) CaptureRequest.CONTROL_AF_MODE_AUTO else af)
        focusControl(builder,CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
        if(afRegions()) regionControl(builder,CaptureRequest.CONTROL_AF_REGIONS)
        if(aeRegions()) regionControl(builder,CaptureRequest.CONTROL_AE_REGIONS)
        builder.set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_OFF)
        if(android.os.Build.VERSION.SDK_INT>=31 && chars?.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)?.contains(CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)==true)
            builder.set(CaptureRequest.SCALER_ROTATE_AND_CROP,CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)
    }
    private var controlledPreviewTone=false
    private var previewTone: android.hardware.camera2.params.TonemapCurve?=null
    private var toneRejected=false
    private var toneMismatches=0
    /** Select input transfer; never changes RAW capture or film parameters. */
    fun setControlledPreviewTone(enabled: Boolean) { handler.post {
        if(controlledPreviewTone==enabled) return@post
        controlledPreviewTone=enabled
        toneRejected=false;toneMismatches=0
        if(session!=null) runCatching {repeat()}.onFailure {fail(tr(Texts.string.preview_tone_curve_request_failed_value, it.message))}
    } }
    private fun previewRequest() = requireNotNull(device).createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
        addTarget(requireNotNull(surface)); controls(this)
        if(controlledPreviewTone && !toneRejected) previewTone?.let {curve->
            focusControl(this,CaptureRequest.TONEMAP_MODE,CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
            focusControl(this,CaptureRequest.TONEMAP_CURVE,curve)
        }
        setTag(PreviewTag(wb,focusRevision,shotRevision,wbRevision))
    }
    private fun repeat() {
        try { session?.setRepeatingRequest(previewRequest().build(),previewCallback,handler) }
        catch(e: IllegalArgumentException) {
            if(!controlledPreviewTone || toneRejected || previewTone==null) throw e
            toneRejected=true
            android.util.Log.w("Camera2","Standard preview curve rejected; retaining ISP preview",e)
            session?.setRepeatingRequest(previewRequest().build(),previewCallback,handler)
        }
    }
    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(s: CameraCaptureSession,request: CaptureRequest,timestamp: Long,frameNumber: Long) {
            val tag=request.tag as? PreviewTag ?: return
            if(s===session && tag.focus==focusRevision && request[CaptureRequest.CONTROL_AF_TRIGGER]==CaptureRequest.CONTROL_AF_TRIGGER_START) triggerFrame=frameNumber
        }
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, total: TotalCaptureResult) {
            if(s !== session) return
            val result=sensorResult(total) ?: run {fail(tr(Texts.string.missing_captureresult_for_the_selected_physical_lens),true);return}
            if(controlledPreviewTone && !toneRejected && request[CaptureRequest.TONEMAP_MODE]==CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE) {
                val accepted=result[CaptureResult.TONEMAP_MODE]==CaptureResult.TONEMAP_MODE_CONTRAST_CURVE &&
                    previewTone?.let {PreviewToneCurve.error(it,result[CaptureResult.TONEMAP_CURVE])<=.02f}==true
                toneMismatches=if(accepted) 0 else toneMismatches+1
                if(toneMismatches>=8) {
                    toneRejected=true
                    android.util.Log.w("Camera2","HAL did not confirm standard preview curve; retaining ISP preview")
                    try {repeat()} catch(e: Exception) {fail(tr(Texts.string.could_not_restore_preview_value, e.message));return}
                }
            }
            val firstResult=latestResult==null
            latestResult=result
            if(firstResult) emit(if(info?.raw==true) tr(Texts.string.raw_gpu_preview) else tr(Texts.string.jpeg_mode_raw_unavailable))
            val tag=request.tag as? PreviewTag
            if(tag?.focus==focusRevision && probeRegion!=null) {acceptProbe(request,result);return}
            if(tag?.focus==focusRevision && tapPhase!=FocusPhase.IDLE) {
                fun matches(key: CaptureResult.Key<Array<MeteringRectangle>>): Boolean {
                    val target=tapRegion?.rect ?: return true
                    val actual=result[key]?.firstOrNull()?.rect ?: return false
                    return abs(actual.exactCenterX()-target.exactCenterX())<=maxOf(8f,target.width()*.02f) &&
                        abs(actual.exactCenterY()-target.exactCenterY())<=maxOf(8f,target.height()*.02f)
                }
                if(tapRegion!=null && ((tapAuto && !matches(CaptureResult.CONTROL_AF_REGIONS)) || (aeRegions() && !matches(CaptureResult.CONTROL_AE_REGIONS)))) {
                    restoreFocus(false);tapPhase=FocusPhase.UNAVAILABLE;reportFocus();armRestore();return
                }
                val af=result[CaptureResult.CONTROL_AF_STATE]
                if(tapPhase==FocusPhase.SCANNING && total.frameNumber>=triggerFrame && result[CaptureResult.CONTROL_AF_MODE]==CaptureResult.CONTROL_AF_MODE_AUTO &&
                    (af==CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || af==CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)) {
                    tapPhase=if(af==CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED) FocusPhase.FOCUSED else FocusPhase.FAILED
                    tapTimeout?.let(handler::removeCallbacks);if(!pending) armRestore()
                }
                reportFocus(result)
            }
            if (s === session) {
                val gains=result[CaptureResult.COLOR_CORRECTION_GAINS]
                val matrix=result[CaptureResult.COLOR_CORRECTION_TRANSFORM]
                if(wbPending && tag?.wb==wb && tag.whiteBalanceRevision==wbRevision) {
                    val mode=result[CaptureResult.CONTROL_AWB_MODE]
                    val accepted=if(!wb.manual) mode==CaptureResult.CONTROL_AWB_MODE_AUTO else {
                        mode==CaptureResult.CONTROL_AWB_MODE_OFF && gains!=null && matrix!=null &&
                        if(wb.source==WhiteBalanceSource.NATIVE_CCT) result[CaptureResult.COLOR_CORRECTION_MODE]==NativeColorTemperature.MODE_CCT && NativeColorTemperature.actual(result)==wb.kelvin
                        else result[CaptureResult.COLOR_CORRECTION_MODE]==CaptureResult.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX &&
                            listOf(gains.red,gains.greenEven,gains.greenOdd,gains.blue).zip(wb.gains).all {(a,b)->abs(a-b)<=maxOf(.03f,b*.02f)} &&
                            (0..8).all {abs(matrix.getElement(it%3,it/3).toFloat()-wb.transform[it])<=.03f}
                    }
                    if(accepted && !(wb.manual && wbTestOverridesAllowed && suppressManualWbConfirmationForTest)) {
                        wbPending=false;wbTimeout?.let(handler::removeCallbacks)
                        if(wbError.isNotBlank()) wbError=tr(Texts.string.manual_white_balance_was_rejected_auto_restored)
                        reportWb()
                    }
                }
                previewResult?.invoke(result)
                sensorSample(requestTag,result)
            }
            if (s !== session || !pending || shooting || tag?.shot!=shotRevision || tag.focus!=focusRevision) return
            val af = result[CaptureResult.CONTROL_AF_STATE]
            val ae = result[CaptureResult.CONTROL_AE_STATE]
            val focused = request[CaptureRequest.CONTROL_AF_MODE]==CaptureRequest.CONTROL_AF_MODE_OFF ||
                (total.frameNumber>=triggerFrame && (af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED))
            val exposed = ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED || ae == CaptureResult.CONTROL_AE_STATE_LOCKED
            if (focused && exposed) takeStill()
        }
    }
    fun setWhiteBalance(manual: Boolean,kelvin: Int?=null) {handler.post {
        if(pending || processing) {reportWb(tr(Texts.string.adjust_white_balance_after_the_capture_finishes));return@post}
        val previous=wb
        try {
            wb=if(!manual) WhiteBalance() else {
                val range=requireNotNull(wbRange()) {tr(Texts.string.this_lens_does_not_support_temperature_control)}
                val value=kelvin ?: wb.kelvin ?: range.default
                require(range.contains(value)) {tr(Texts.string.temperature_is_outside_this_lens_s_range)}
                if(wbSource()==WhiteBalanceSource.NATIVE_CCT) WhiteBalance(value) else {
                    val calibration=requireNotNull(wbCalibration)
                    val solution=io.github.c52e.filcamera.imaging.CalibratedWhiteBalance.solve(calibration,value)
                    WhiteBalance(value,WhiteBalanceSource.CALIBRATED,calibration.hash,solution.gains.map {it.toFloat()},solution.transform.values.map {it.toFloat()})
                }
            }
            wbRevision++;wbError="";wbPending=true;reportWb();repeat()
            armWbTimeout()
        } catch(e: Exception) {
            if(manual && wb.manual) rejectWhiteBalance(tr(Texts.string.could_not_set_white_balance_value, e.message))
            else {wb=previous;wbPending=false;reportWb(e.message ?: tr(Texts.string.could_not_set_white_balance))}
        }
    }}
    fun setExposureCompensation(value: Int) { handler.post {
        val c = info ?: return@post
        if (pending) return@post
        ev = value.coerceIn(c.evMin,c.evMax)
        try { if (session != null) repeat() } catch (e: Exception) { fail(tr(Texts.string.could_not_set_exposure_value, e.message)) }
    } }
    fun capture(film: FilmRecipe, displayDegrees: Int) { handler.post {
        if (session == null || pending || processing || wbPending) return@post
        recipe = film;shotWb=wb
        val i = info ?: return@post
        rotation = (i.sensorRotation + (if (i.front) displayDegrees else -displayDegrees) + 360) % 360
        pairer.clear(); pending = true; shooting = false
        shotRevision++;tapRestore?.let(handler::removeCallbacks)
        emit(tr(Texts.string.focusing_and_metering))
        try {
            val trigger = previewRequest().apply {
                if (!tapAuto && get(CaptureRequest.CONTROL_AF_MODE) != CaptureRequest.CONTROL_AF_MODE_OFF) {
                    triggerFrame=Long.MAX_VALUE
                    focusControl(this,CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_START)
                }
                set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            }
            session!!.capture(trigger.build(),previewCallback,handler)
            repeat()
            focusTimeout = Runnable { if (pending && !shooting) takeStill() }.also { handler.postDelayed(it,1800) }
        } catch (e: Exception) { abortCapture(tr(Texts.string.shutter_failed_value, e.message)) }
    } }
    private fun takeStill() {
        if (!pending || shooting) return
        shooting = true; focusTimeout?.let(handler::removeCallbacks)
        try {
            val request = requireNotNull(device).createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(requireNotNull(owner).reader.surface); controls(this)
                set(CaptureRequest.JPEG_ORIENTATION,rotation)
                if(info?.raw==true) {
                    // Request metadata only on stills; do not add work to the preview stream.
                    set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                    val shading=chars?.get(CameraCharacteristics.SHADING_AVAILABLE_MODES) ?: intArrayOf()
                    if(shading.contains(CaptureRequest.SHADING_MODE_FAST)) set(CaptureRequest.SHADING_MODE,CaptureRequest.SHADING_MODE_FAST)
                    else if(shading.contains(CaptureRequest.SHADING_MODE_HIGH_QUALITY)) set(CaptureRequest.SHADING_MODE,CaptureRequest.SHADING_MODE_HIGH_QUALITY)
                }
            }
            captureTimeout = Runnable { if (pending) abortCapture(tr(Texts.string.capture_timed_out_please_retry)) }.also { handler.postDelayed(it,10000) }
            session!!.capture(request.build(),object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, total: TotalCaptureResult) {
                    if (s !== session || !pending) return
                    val result=sensorResult(total) ?: run {abortCapture(tr(Texts.string.raw_is_missing_physical_lens_calibration_results));return}
                    val timestamp = result[CaptureResult.SENSOR_TIMESTAMP]
                    if (timestamp == null) abortCapture(tr(Texts.string.missing_sensor_timestamp)) else pairer.result(timestamp,result)
                    unlock()
                }
                override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) { if (s === session) abortCapture(tr(Texts.string.capture_failed_value, failure.reason)) }
                override fun onCaptureBufferLost(s: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) { if (s === session) abortCapture(tr(Texts.string.photo_buffer_was_lost)) }
            },handler)
        } catch (e: Exception) { abortCapture(tr(Texts.string.capture_failed_value_2, e.message)) }
    }
    private fun unlock() {restoreFocus()}
    private fun abortCapture(message: String) {
        pending = false; shooting = false
        focusTimeout?.let(handler::removeCallbacks); captureTimeout?.let(handler::removeCallbacks)
        pairer.clear(); unlock(); emit(message)
    }
    fun processingFinished(message: String) { handler.post { processing = false; emit(message) } }
    fun stop() { handler.post { stopInternal(); emit(tr(Texts.string.camera_paused)) } }
    private fun stopInternal() {
        generation++
        clearFocus();latestResult=null;regionMappings.clear()
        wbTimeout?.let(handler::removeCallbacks);wbRevision++
        pending = false; shooting = false
        focusTimeout?.let(handler::removeCallbacks); captureTimeout?.let(handler::removeCallbacks)
        pairer.clear()
        session?.close(); session = null
        val old = device; device = null
        old?.close()
        // If open is still pending its callback owns release. Releases are idempotent.
        if (!targetOwnedByCamera) releaseTarget?.invoke()
        targetOwnedByCamera = false
        releaseTarget = null; surface = null
        owner?.retire(); owner = null
    }
    private fun fail(message: String,unsupported: Boolean=false) { val failed=route;stopInternal();state(CameraState(false,processing,message,info,failed,unsupported,requestTag)) }
    private fun maybeQuit() { if (!alive && openRequests == 0) thread.quitSafely() }
    override fun close() { handler.post { alive = false; stopInternal(); maybeQuit() } }
}
