package io.github.c52e.filcamera

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

import io.github.c52e.filcamera.camera.LocalDiagnostics
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Only the latest recipe may publish an editing preview; saving is explicit. */
class EditSession(private val context: Context,private val repository: PhotoRepository,private val saved: ()->Unit={}) {
    data class State(val recipe: FilmRecipe=FilmRecipe(),val busy: Boolean=false,val saving: Boolean=false,val file: File?=null,val resultHash: String="",val message: String="")
    private val mutable=MutableStateFlow(State());val state=mutable.asStateFlow()
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var generation=0L
    @Volatile private var nativeRequest=0L
    private var photo: PhotoRepository.Photo?=null
    private var running=false
    private var pending=false
    @Volatile private var active=false
    val isOpen get()=active
    private val input=DngEditInput(context)
    init {FilmWorker.execute {
        if(!cleaned) {
            context.cacheDir.listFiles()?.filter {it.isFile && it.name.matches(Regex("edit-preview-[0-9a-f-]{36}\\.jpg(\\.pending)?"))}?.forEach {it.delete()}
            cleaned=true
        }
    }}
    companion object {private var cleaned=false} // Accessed exclusively on FilmWorker.

    private var details: JSONObject?=null
    private val later=Runnable {if(running) pending=true else render()}
    fun open(value: PhotoRepository.Photo,recipe: FilmRecipe) {
        LocalDiagnostics.event("EDIT.open")
        close();photo=value;active=true;mutable.value=State(recipe=recipe,message=tr(Texts.string.reading_original));change(recipe)
    }
    fun change(recipe: FilmRecipe) {
        if(!active) return
        generation++;if(nativeRequest>0) FilmEngine.cancel(nativeRequest)
        mutable.value=mutable.value.copy(recipe=recipe,busy=true,message=tr(Texts.string.settings_changed_updating))
        pending=true;main.removeCallbacks(later);main.postDelayed(later,200)
    }
    private fun render() {
        val original=photo ?: return;if(!active) return
        pending=false;running=true
        val gen=generation;val requested=mutable.value.recipe
        val id=SystemClock.elapsedRealtimeNanos();nativeRequest=id
        val file=File(context.cacheDir,"edit-preview-${UUID.randomUUID()}.jpg")
        fun current()=active && generation==gen
        FilmWorker.execute {
            var rendered: JSONObject?=null;var error: String?=null
            try {
                if(!current()) throw InterruptedException()
                val timing=CaptureTiming()
                timing.stage("inputVerify") {repository.verifySource(original)}
                val prepared=input.prepare(original.source,PairCaptureStore.hash(original.source),timing,cancelled={!current()})
                val assets=PreviewLutStore(context).prepareAssets(::current)
                val seed=requireNotNull(FilmEngine.recordedSeed(prepared.record))
                val rgba=FilmOutput.buffer(prepared.width,prepared.height)
                val result=timing.stage("nativeCall") {JSONObject(FilmEngine.render(assets.path,"",prepared.width,prepared.height,requested,id,"",seed,rgba,prepared.pixels,prepared.owner.gpuImage))}
                if(result.getBoolean("cancelled") || !current()) throw InterruptedException()
                val encoding=timing.stage("photoEncode") {PhotoOutput.write(rgba,prepared.width,prepared.height,file,prepared.framing)}
                result.put("output",PhotoOutput.metadata(file,encoding))
                rendered=result.put("input",JSONObject().put("kind","dng").put("file",original.source.relativeTo(File(context.filesDir,"lab")).invariantSeparatorsPath))
                    .put("framing",prepared.framing.json()).put("renderQuality","editing-preview").put("timing",timing.json()).put("engineIdentity",JSONObject(context.assets.open("preview/engine.json").bufferedReader().use {it.readText()}))
            } catch(e: OutOfMemoryError) {input.close();FilmEngine.close();error=LocalDiagnostics.failure("EDIT-003","editor-memory",tr(Texts.string.out_of_memory_original_and_previous_result_retained),e)}
            catch(e: InterruptedException) { /* Parameter changes reuse the prepared RAW input. Page close releases it on FilmWorker. */ }
            catch(e: Exception) {input.close();FilmEngine.close();error=LocalDiagnostics.failure("EDIT-002","editor-render",e.message ?: tr(Texts.string.development_failed),e,"generation=$gen")}
            main.post {
                running=false
                if(current() && rendered!=null) {
                    mutable.value.file?.delete();details=rendered
                    mutable.value=mutable.value.copy(file=file,resultHash=requested.hash,busy=false,message="")
                } else {
                    file.delete()
                    if(current()) mutable.value=mutable.value.copy(busy=false,message=error ?: tr(Texts.string.update_canceled))
                }
                if(active && pending) {main.removeCallbacks(later);main.post(later)}
            }
        }
    }
    fun save(done: (PhotoRepository.Version?,String?)->Unit) {
        val original=photo ?: return;val s=mutable.value
        if(s.busy || s.resultHash!=s.recipe.hash || s.file==null || details==null) return
        val frozen=s.recipe;val gen=generation
        LocalDiagnostics.event("EDIT.save")
        mutable.value=s.copy(busy=true,saving=true,message=tr(Texts.string.saving_copy))
        FilmWorker.execute {
            var result: PhotoRepository.Version?=null;var error: String?=null
            val file=File(context.cacheDir,"edit-final-${UUID.randomUUID()}.jpg")
            try {
                val timing=CaptureTiming()
                timing.stage("inputVerify") {repository.verifySource(original)}
                // This explicit save owns its request independently of cancelled editing previews.
                input.close()
                val prepared=input.prepare(original.source,PairCaptureStore.hash(original.source),timing,true,{false})
                val assets=PreviewLutStore(context).prepareAssets {true}
                val seed=requireNotNull(FilmEngine.recordedSeed(prepared.record))
                val rgba=FilmOutput.buffer(prepared.width,prepared.height)
                val rendered=timing.stage("nativeCall") {JSONObject(FilmEngine.render(assets.path,"",prepared.width,prepared.height,frozen,SystemClock.elapsedRealtimeNanos(),"",seed,rgba,prepared.pixels,prepared.owner.gpuImage))}
                check(!rendered.getBoolean("cancelled"))
                val encoding=timing.stage("photoEncode") {PhotoOutput.write(rgba,prepared.width,prepared.height,file,prepared.framing)}
                rendered.put("output",PhotoOutput.metadata(file,encoding))
                rendered.put("framing",prepared.framing.json()).put("renderQuality","final").put("width",prepared.width).put("height",prepared.height)
                    .put("input",JSONObject().put("kind","dng").put("file",original.source.relativeTo(File(context.filesDir,"lab")).invariantSeparatorsPath))
                    .put("timing",timing.json()).put("engineIdentity",JSONObject(context.assets.open("preview/engine.json").bufferedReader().use {it.readText()}))
                result=repository.saveEdit(original,file,rendered)
                try {repository.publish(result)} catch(e: Exception) {error=LocalDiagnostics.failure("SAVE-002","gallery-save",tr(Texts.string.copy_retained_gallery_save_failed_you_can_retry),e)}
            } catch(e: OutOfMemoryError) {error=LocalDiagnostics.failure("EDIT-003","editor-memory",tr(Texts.string.out_of_memory_original_and_previous_result_retained),e)}
            catch(e: Exception) {error=LocalDiagnostics.failure("SAVE-001","editor-save",e.message ?: tr(Texts.string.operation_failed),e)}
            finally {file.delete();input.close();FilmEngine.close()}
            main.post {if(result!=null) saved();if(active && generation==gen) {mutable.value=mutable.value.copy(busy=false,saving=false,message=error.orEmpty());done(result,error)}}
        }
    }

    private var suspended: Pair<PhotoRepository.Photo,FilmRecipe>?=null
    fun pause() {val selected=photo?.let {it to mutable.value.recipe};close();suspended=selected}
    fun resume() {val selected=suspended;suspended=null;if(selected!=null) open(selected.first,selected.second)}
    fun close() {
        suspended=null
        active=false;generation++;pending=false;main.removeCallbacks(later)
        if(nativeRequest>0) FilmEngine.cancel(nativeRequest)
        val previous=mutable.value.file;mutable.value=mutable.value.copy(file=null,busy=false,saving=false)
        FilmWorker.execute {input.close();FilmEngine.close();previous?.delete()};photo=null;details=null
    }
}
