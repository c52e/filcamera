package io.github.c52e.filcamera

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Validated immutable LUT. Read-only duplicates may be used only by their owning GL thread. */
class PreviewLut private constructor(private val buffer: ByteBuffer,val sha256: String,val key: String,val source: String,val recipe: FilmRecipe) {
    fun pixels(): ByteBuffer = buffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder()).apply { rewind() }
    companion object {
        const val BYTES=65*65*65*8
        fun digest(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun read(bytes: ByteArray,expected: String,key: String,source: String,recipe: FilmRecipe=FilmRecipe()): PreviewLut {
            require(bytes.size==BYTES && digest(bytes)==expected) { "LUT size or checksum mismatch" }
            val input=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for(i in 0 until BYTES/2) {
                val bits=input.short.toInt() and 65535
                require(if(i%4==3) bits==0x3c00 else bits in 0..0x3c00) { "Invalid RGBA16F LUT value" }
            }
            val direct=ByteBuffer.allocateDirect(BYTES).order(ByteOrder.nativeOrder()).put(bytes).apply { rewind() }
            return PreviewLut(direct,expected,key,source,recipe.withoutSpatial())
        }
    }
}

/** Worker-owned content-addressed cache. A manifest rename commits a pair of immutable files. */
class PreviewLutStore(private val context: Context, private val root: File=File(context.filesDir,"lab/preview-luts"),val recipe: FilmRecipe=FilmRecipe(),private val retainSession: Boolean=false) {
    private val identityBytes=context.assets.open("preview/engine.json").use { it.readBytes() }
    private val identity=JSONObject(String(identityBytes,Charsets.UTF_8))
    val key=PreviewLut.digest(identityBytes+("|recipe-lut-v2|"+recipe.colorCanonical+"|ae0-spatial0-crop0-scale1").toByteArray(Charsets.UTF_8))
    data class Result(val lut: PreviewLut,val details: JSONObject)
    fun cached(): Result? = try {
        val manifest=File(root,"$key.json")
        if(!manifest.isFile) null else {
            val info=JSONObject(manifest.readText())
            require(info.getString("key")==key)
            val hash=info.getString("sha256");require(hash.matches(Regex("[a-f0-9]{64}")))
            val file=File(root,"$key-$hash.rgba16f");require(file.length()==PreviewLut.BYTES.toLong())
            require(FilmRecipe.parse(info.getJSONObject("recipe")).colorHash==recipe.colorHash)
            manifest.setLastModified(System.currentTimeMillis())
            Result(PreviewLut.read(file.readBytes(),hash,key,"device-cache",recipe),info)
        }
    } catch(_: Exception) { null }

    fun bake(request: Long,protectedKey: String?=null,isCurrent: () -> Boolean): Result {
        root.mkdirs()
        val pending=File(root,"$key-$request.pending")
        val manifestPending=File(root,"$key-$request.json.pending")
        try {
            checkCurrent(isCurrent)
            val start=SystemClock.elapsedRealtime()
            val assets=prepareAssets(isCurrent)
            checkCurrent(isCurrent)
            val details=JSONObject(FilmEngine.bakePreviewLut(assets.path,request,pending.path,recipe))
            if(details.getBoolean("cancelled")) throw InterruptedException("LUT generation cancelled")
            checkCurrent(isCurrent)
            require(pending.length()==PreviewLut.BYTES.toLong())
            val bytes=pending.readBytes();val hash=PreviewLut.digest(bytes)
            val lut=PreviewLut.read(bytes,hash,key,"device-generated",recipe)
            details.put("key",key).put("sha256",hash).put("identity",identity)
                .put("wallMs",SystemClock.elapsedRealtime()-start).put("format",identity.getString("lutFormat"))
            val final=File(root,"$key-$hash.rgba16f")
            checkCurrent(isCurrent)
            if(final.isFile && PairCaptureStore.hash(final)==hash) pending.delete()
            else check(pending.renameTo(final)) { "Cannot publish LUT" }
            manifestPending.writeText(details.toString(2))
            checkCurrent(isCurrent)
            check(manifestPending.renameTo(File(root,"$key.json"))) { "Cannot commit LUT manifest" }
            prune(protectedKey)
            return Result(lut,details)
        } finally { pending.delete();manifestPending.delete();if(!retainSession) FilmEngine.close() }
    }
    private fun prune(protectedKey: String?) {
        val manifests=root.listFiles()?.filter { it.name.matches(Regex("[a-f0-9]{64}\\.json")) }
            ?.sortedByDescending { it.lastModified() } ?: return
        val keep=linkedSetOf(key).apply { if(protectedKey!=null) add(protectedKey) }
        for(file in manifests) if(keep.size<16) keep.add(file.nameWithoutExtension)
        for(file in manifests) if(file.nameWithoutExtension !in keep) {
            val prefix=file.nameWithoutExtension+"-"
            file.delete()
            root.listFiles()?.filter { it.name.startsWith(prefix) && it.extension=="rgba16f" }?.forEach { it.delete() }
        }
    }
    fun prepareAssets(isCurrent: () -> Boolean = {true}): File {
        val root=File(context.filesDir,"lab/assets")
        val assets=identity.getJSONObject("assets")
        for(name in assets.keys()) {
            checkCurrent(isCurrent)
            val file=File(root,name)
            require(file.canonicalPath.startsWith(root.canonicalPath+File.separator))
            val expected=assets.getString(name)
            if(!file.isFile || PairCaptureStore.hash(file)!=expected) {
                file.parentFile!!.mkdirs();val pending=File(file.parentFile,file.name+".lut-pending")
                try {
                    context.assets.open("lab/assets/$name").use { input -> pending.outputStream().use { input.copyTo(it) } }
                    require(PairCaptureStore.hash(pending)==expected);check(pending.renameTo(file))
                } finally { pending.delete() }
            }
            require(PairCaptureStore.hash(file)==expected) { "Profile checksum mismatch: $name" }
        }
        return root
    }
    private fun checkCurrent(current: () -> Boolean) { if(!current()) throw InterruptedException("LUT request superseded") }
}
