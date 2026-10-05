package io.github.c52e.filcamera

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class CameraViewModel(app: Application): AndroidViewModel(app) {
    data class Ui(val photos: List<PhotoRepository.Photo> = emptyList(),val recipe: FilmRecipe=FilmRecipe(),val message: String="",val working: Boolean=false)
    private val loaded=runCatching {FilmRecipe.load(app)}
    private val mutable=MutableStateFlow(Ui(recipe=loaded.getOrDefault(FilmRecipe()),message=loaded.exceptionOrNull()?.let {tr(Texts.string.could_not_restore_the_last_recipe_using_defaults_value, it.message)}.orEmpty()))
    val state=mutable.asStateFlow()
    private val main=Handler(Looper.getMainLooper())
    val repository=PhotoRepository(app)
    val camera=CameraCoordinator(app,repository,::refresh)
    internal val captureOrientation=CaptureOrientation()
    val editor=EditSession(app,repository,::refresh)
    init {refresh()}
    fun recipe(value: FilmRecipe) {value.save(getApplication());mutable.value=mutable.value.copy(recipe=value);camera.changeRecipe(value)}
    fun refresh() {FilmWorker.execute {try {val photos=repository.scan();main.post {mutable.value=mutable.value.copy(photos=photos)}} catch(e: Exception) {message(e.message ?: tr(Texts.string.could_not_read_library))}}}
    fun message(value: String) {main.post {mutable.value=mutable.value.copy(message=value)}}
    fun work(body: ()->String) {
        if(mutable.value.working) return
        mutable.value=mutable.value.copy(working=true)
        FilmWorker.execute {val text=try {body()} catch(e: Exception) {e.message ?: tr(Texts.string.operation_failed)};main.post {mutable.value=mutable.value.copy(working=false,message=text);refresh()}}
    }
    fun import(uri: Uri)=work {repository.importDng(uri);tr(Texts.string.dng_imported_ready_to_edit_and_develop)}
    fun export(photo: PhotoRepository.Photo,uri: Uri)=work {repository.exportDng(photo,uri);tr(Texts.string.dng_original_exported)}
    fun publish(version: PhotoRepository.Version)=work {repository.publish(version);tr(Texts.string.saved_to_system_gallery)}
    fun delete(photo: PhotoRepository.Photo,version: PhotoRepository.Version?)=work {
        if(version==null) repository.deletePhoto(photo) else repository.deleteVersion(photo,version)
        tr(Texts.string.in_app_photo_deleted_system_gallery_copies_retained)
    }
    fun deletePhotos(ids: Set<String>) {
        val photos=mutable.value.photos.filter {it.id in ids}
        work {
            var failed=0
            photos.forEach {photo->try {repository.deletePhoto(photo)} catch(_: Exception) {failed++}}
            tr(Texts.string.deleted_value_photos, photos.size-failed)+(if(failed>0) tr(Texts.string.value_could_not_be_deleted_please_retry, failed) else "")+tr(Texts.string.system_gallery_copies_retained)
        }
    }
    fun edit(photo: PhotoRepository.Photo,version: PhotoRepository.Version?,current: ()->Boolean={true},done: ()->Unit) {
        work {val recipe=repository.recipe(photo,version);main.post {if(current()) {editor.open(photo,recipe);done()}};""}
    }
    override fun onCleared() {camera.close();editor.close();super.onCleared()}
}
