package io.github.c52e.filcamera

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

import android.content.Context
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import io.github.c52e.filcamera.rawvulkan.DngRawFile
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** Files are authoritative; catalog and thumbnails may always be rebuilt. All IO on FilmWorker. */
class PhotoRepository(private val context: Context) {
    data class Version(val id: String,val file: File,val record: File)
    data class Photo(val id: String,val source: File,val record: File,val date: Long,val versions: List<Version>) {
        val latest get()=versions.lastOrNull()
    }
    private val lab=File(context.filesDir,"lab")
    private val renders=File(context.filesDir,"renders")
    private val album=File(lab,"album.json")
    internal var failPublish=false
    private fun json(file: File)=runCatching {JSONObject(file.readText())}.getOrNull()
    private fun inside(file: File): File {
        val path=file.canonicalFile
        require(path.path.startsWith(context.filesDir.canonicalPath+File.separator)) {tr(Texts.string.invalid_photo_path)}
        return path
    }
    fun scan(): List<Photo> {
        val edits=renders.listFiles()?.filter {it.extension=="json"}?.mapNotNull {file->
            json(file)?.let {j->
                val image=File(renders,j.optJSONObject("output")?.optString("file").orEmpty())
                if(image.isFile && image.parentFile==renders && image.extension=="jpg") Triple(file,j,image) else null
            }
        }.orEmpty()
        val result=mutableListOf<Photo>()
        fun add(id: String,source: File,record: File,base: Version?) {
            if(!source.isFile) return
            val relative=source.relativeTo(lab).invariantSeparatorsPath
            val versions=mutableListOf<Version>();base?.let(versions::add)
            edits.filter {it.second.optString("photoId").takeIf {key->key.isNotBlank()}?.let {key->key==id} ?: (it.second.optJSONObject("input")?.optString("file")==relative)}
                .sortedBy {it.first.lastModified()}.forEach {versions+=Version(it.first.nameWithoutExtension,it.third,it.first)}
            result+=Photo(id,source,record,record.lastModified().takeIf {it>0} ?: source.lastModified(),versions)
        }
        File(lab,"pairs").listFiles()?.filter {it.isDirectory}?.forEach {dir->
            val record=File(dir,"report.json");val image=PhotoOutput.result(dir)
            add(dir.name,File(dir,"original.dng"),record,image?.let {Version(dir.name,it,record)})
        }
        File(lab,"imports").listFiles()?.filter {it.extension=="json"}?.forEach {record->
            val entry=json(record) ?: return@forEach
            if(entry.optString("kind")!="dng") return@forEach
            val source=inside(File(lab,entry.getString("file")))
            add("import_"+record.nameWithoutExtension,source,record,null)
        }
        val sorted=result.sortedByDescending {it.date}
        atomic(File(lab,"catalog.json"),JSONObject().put("schema",1).put("photos",JSONArray(sorted.map {
            JSONObject().put("id",it.id).put("source",it.source.relativeTo(lab).invariantSeparatorsPath).put("date",it.date)
        })))
        return sorted
    }
    fun captureRecipe(photo: Photo): FilmRecipe {
        val record=json(photo.record) ?: DngRawFile.read(photo.source).record
        return FilmRecipe.restore(record.optJSONObject("captureRecipe") ?: record)
    }
    fun recipe(photo: Photo,version: Version?)=version?.let {json(it.record)}?.let {FilmRecipe.restore(it)} ?: captureRecipe(photo)
    fun thumbnail(version: Version): File {
        val cache=File(context.cacheDir,"thumbnails").apply {mkdirs()}
        val key=PreviewLut.digest((version.file.path+version.file.length()+version.file.lastModified()).toByteArray())
        val dest=File(cache,"$key.jpg")
        if(!dest.isFile) {
            val size=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeFile(version.file.path,size)
            var sample=1;while(maxOf(size.outWidth,size.outHeight)/sample>512) sample*=2
            val bmp=checkNotNull(BitmapFactory.decodeFile(version.file.path,BitmapFactory.Options().apply {inSampleSize=sample}))
            try {PhotoOutput.write(bmp,dest)} finally {bmp.recycle()}
            cache.listFiles()?.sortedByDescending {it.lastModified()}?.drop(160)?.forEach {it.delete()}
        }
        return dest
    }
    fun importDng(uri: Uri): Photo {
        require(android.os.StatFs(lab.apply {mkdirs()}.path).availableBytes>DngRawFile.MAX_FILE_BYTES+64L*1024*1024) {tr(Texts.string.not_enough_storage_free_up_space_first)}
        val id=UUID.randomUUID().toString();val dir=File(lab,"imports").apply {mkdirs()}
        val pending=File(dir,"$id.pending");val source=File(dir,"$id.dng");val record=File(dir,"$id.json")
        try {
            requireNotNull(context.contentResolver.openInputStream(uri)).use {input->pending.outputStream().use {output->
                val bytes=ByteArray(65536);var total=0L
                while(true) {val n=input.read(bytes);if(n<0) break;total+=n;require(total<=DngRawFile.MAX_FILE_BYTES) {tr(Texts.string.dng_file_is_too_large)};output.write(bytes,0,n)}
            }}
            val decoded=DngRawFile.read(pending)
            check(pending.renameTo(source))
            atomic(record,JSONObject().put("kind","dng").put("file","imports/$id.dng").put("sha256",PairCaptureStore.hash(source)).put("captureRecipe",decoded.record))
            return scan().first {it.id=="import_$id"}
        } catch(e: Exception) {source.delete();record.delete();throw e} finally {pending.delete()}
    }
    fun verifySource(photo: Photo) {
        val record=json(photo.record)
        val expected=record?.optString("sha256")?.takeIf {it.isNotBlank()} ?: record?.optJSONObject("files")?.optString("original.dng")?.takeIf {it.isNotBlank()}
        if(expected!=null) require(PairCaptureStore.hash(photo.source)==expected) {tr(Texts.string.dng_checksum_failed_original_unchanged)}
    }
    fun exportDng(photo: Photo,uri: Uri) {
        verifySource(photo)
        checkNotNull(context.contentResolver.openOutputStream(uri,"w")).use {output->photo.source.inputStream().use {it.copyTo(output,65536)}}
    }
    fun saveEdit(photo: Photo,temporary: File,details: JSONObject): Version {
        renders.mkdirs();val id="edit_"+UUID.randomUUID();val image=File(renders,"$id.jpg");val record=File(renders,"$id.json")
        val pending=File(renders,"$id.pending")
        try {
            temporary.inputStream().use {input->pending.outputStream().use {input.copyTo(it,65536)}}
            check(pending.renameTo(image))
            details.put("photoId",photo.id).put("output",details.optJSONObject("output")?.put("file",image.name) ?: PhotoOutput.metadata(image))
            atomic(record,details)
            return Version(id,image,record)
        } catch(e: Exception) {image.delete();record.delete();throw e} finally {pending.delete()}
    }
    fun publish(version: Version): Uri {
        check(!failPublish) {tr(Texts.string.test_gallery_write_failed)}
        val resolver=context.contentResolver;val saved=json(album) ?: JSONObject()
        saved.optString(version.id).takeIf {it.isNotBlank()}?.let {text->
            val uri=Uri.parse(text)
            if(runCatching {resolver.openFileDescriptor(uri,"r")?.use {true} ?: false}.getOrDefault(false)) return uri
        }
        val name="FILCamera_${version.id}.jpg";val path="Pictures/FILCamera/"
        // Recover a completed/pending publication if the process died before recording its URI.
        resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,arrayOf(MediaStore.Images.Media._ID,MediaStore.Images.Media.IS_PENDING),
            "${MediaStore.Images.Media.DISPLAY_NAME}=? AND ${MediaStore.Images.Media.RELATIVE_PATH}=?",arrayOf(name,path),null)?.use {cursor->
            while(cursor.moveToNext()) {
                val uri=android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,cursor.getLong(0))
                if(cursor.getInt(1)==0) {saved.put(version.id,uri.toString());atomic(album,saved);return uri}
                resolver.delete(uri,null,null)
            }
        }
        val values=ContentValues().apply {put(MediaStore.Images.Media.DISPLAY_NAME,name);put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");put(MediaStore.Images.Media.RELATIVE_PATH,path);put(MediaStore.Images.Media.IS_PENDING,1)}
        val uri=checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values))
        try {
            checkNotNull(resolver.openOutputStream(uri)).use {out->version.file.inputStream().use {it.copyTo(out,65536)}}
            check(resolver.update(uri,ContentValues().apply {put(MediaStore.Images.Media.IS_PENDING,0)},null,null)==1)
        } catch(e: Exception) {resolver.delete(uri,null,null);throw e}
        saved.put(version.id,uri.toString());atomic(album,saved);return uri
    }
    fun deleteVersion(photo: Photo,version: Version) {
        require(version in photo.versions)
        check(!inside(version.file).exists() || version.file.delete())
        if(version.record!=photo.record) check(!inside(version.record).exists() || version.record.delete())
    }
    fun deletePhoto(photo: Photo) {
        photo.versions.forEach {deleteVersion(photo,it)}
        if(photo.id.startsWith("import_")) {
            check(inside(photo.source).delete());inside(photo.record).delete()
        } else {
            val dir=inside(photo.source.parentFile!!)
            require(dir.parentFile==File(lab,"pairs").canonicalFile)
            dir.listFiles()?.filter {it.isFile}?.forEach {check(inside(it).delete())};check(dir.delete())
        }
    }
    companion object {
        fun atomic(file: File,value: JSONObject) {
            file.parentFile!!.mkdirs();val pending=File(file.parentFile,file.name+".pending")
            try {pending.writeText(value.toString(2));java.nio.file.Files.move(pending.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)} finally {pending.delete()}
        }
    }
}
