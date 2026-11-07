package io.github.c52e.filcamera

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Prepares the shared model directory on FilmWorker. */
internal fun prepareFilmAssets(context: Context,isCurrent: ()->Boolean={true}): File {
    val root=File(context.filesDir,"lab/assets")
    val prefix=root.canonicalPath+File.separator
    val manifest=context.assets.open("lab/manifest.json").bufferedReader().use {JSONObject(it.readText())}
    val assets=manifest.getJSONObject("assets")
    for(name in assets.keys()) {
        if(!isCurrent()) throw InterruptedException("Film asset preparation cancelled")
        val file=File(root,name)
        require(file.canonicalPath.startsWith(prefix))
        val expected=assets.getString(name)
        if(file.isFile && PairCaptureStore.hash(file)==expected) continue
        val parent=checkNotNull(file.parentFile)
        parent.mkdirs()
        val pending=File(parent,file.name+".pending")
        try {
            context.assets.open("lab/assets/$name").use {input->pending.outputStream().use {input.copyTo(it)}}
            require(PairCaptureStore.hash(pending)==expected) {"Profile checksum mismatch: $name"}
            check(pending.renameTo(file)) {"Cannot install film asset: $name"}
        } finally {pending.delete()}
    }
    return root
}
