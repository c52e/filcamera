package io.github.c52e.filcamera.camera

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Local, bounded breadcrumbs. No images, content URIs, account IDs or network uploads. */
object LocalDiagnostics {
    private const val MAX_EVENTS = 120
    private const val MAX_REPORTS = 5
    private val events = ArrayDeque<String>()
    private val session = UUID.randomUUID().toString().take(8)
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task,"LocalDiagnostics").apply { isDaemon=true } }
    private var writeQueued=false
    private var sessionText=""
    private val pendingReports=linkedMapOf<String,String>()
    private val fileLock=Any()
    private var directory: File? = null
    private var environment = ""
    private var build = ""
    @Volatile var latest: String = ""
        private set

    @Synchronized fun initialize(context: Context, identity: String) {
        if(directory!=null) return
        directory=File(context.noBackupFilesDir,"diagnostics").apply {mkdirs()}
        build=identity
        environment="FILCamera $identity\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\nBuild: ${Build.DISPLAY}\nABI: ${Build.SUPPORTED_ABIS.joinToString()}"
        runCatching {File(directory,"session.txt").takeIf {it.isFile}?.copyTo(File(directory,"previous-session.txt"),overwrite=true)}
        latest=runCatching {reports().firstOrNull()?.readText().orEmpty()}.getOrDefault("")
        event("APP.start","session=$session")
    }

    /** Redact paths/URIs even when they arrive inside third-party exception messages. */
    fun redact(text: String): String = text
        .replace(Regex("(?i)(?:content|file|https?)://[^\\s)\\]}]+"),"<uri>")
        .replace(Regex("(?i)(?:[a-z]:[\\\\/]|/(?=[a-z0-9_.-]))[^\\s)\\]}]+"),"<path>")

    @Synchronized fun event(stage: String, details: String="") {
        events.addLast("${System.currentTimeMillis()} +${SystemClock.elapsedRealtime()}ms [$session] $stage ${redact(details).take(2000)}")
        while(events.size>MAX_EVENTS) events.removeFirst()
        sessionText=environment+"\n\n"+events.joinToString("\n")
        scheduleWrite()
    }

    /** Call at the failure site, before cleanup changes the state; returned text is screenshot-ready. */
    @Synchronized fun failure(code: String, stage: String, message: String, cause: Throwable?=null,
        details: String="", synchronous: Boolean=false): String {
        val id=UUID.randomUUID().toString().take(8)
        val header="$code · $stage · #$id\nFILCamera $build"
        event("ERROR $code", "event=$id stage=$stage $details")
        val report="$header\n${redact(message).take(2000)}\n${redact(details).take(4000)}\n\n$environment\n\n"+
            "Exception:\n${redact(cause?.stackTraceToString().orEmpty()).take(16000)}\n\nRecent events:\n${events.joinToString("\n")}"
        latest=report
        val name="error-${System.currentTimeMillis()}-$id.txt"
        if(synchronous) runCatching {synchronized(fileLock) {writeAtomic(name,report);trimReports()}}
        else {
            pendingReports[name]=report
            while(pendingReports.size>MAX_REPORTS) pendingReports.remove(pendingReports.keys.first())
            scheduleWrite()
        }
        val readable=redact(message)
        return readable.take(120) + (if(readable.length>120) "…" else "") + "\n$header" +
            (if(details.isBlank()) "" else "\n${redact(details).take(180)}")
    }

    @Synchronized fun summary(): String = latest.ifBlank {environment+"\n\n"+events.joinToString("\n")}

    @Synchronized fun recentReports(): List<String> = (listOf(summary()) + reports().mapNotNull {
        runCatching {it.readText()}.getOrNull()
    }).distinct().take(MAX_REPORTS)

    /** At most one flush is queued, so rapid events cannot grow memory or block the camera on disk IO. */
    private fun scheduleWrite() {
        if(writeQueued) return
        writeQueued=true
        writer.execute {
            val (sessionSnapshot,errorSnapshots)=synchronized(this) {
                writeQueued=false
                (sessionText to pendingReports.toMap()).also {pendingReports.clear()}
            }
            runCatching {synchronized(fileLock) {
                writeAtomic("session.txt",sessionSnapshot)
                errorSnapshots.forEach { (name,text) -> writeAtomic(name,text) }
                trimReports()
            }}
        }
    }

    /** Export on a worker thread, after writes queued at the time of the request. */
    fun export(context: Context): File {
        val result=java.util.concurrent.FutureTask {
            val out=File(context.cacheDir,"diagnostic-exports").apply {mkdirs()}
            out.listFiles()?.filter {it.name.endsWith(".zip") && System.currentTimeMillis()-it.lastModified()>7*86400000L}?.forEach {it.delete()}
            out.listFiles()?.filter {it.extension=="zip"}?.sortedByDescending {it.lastModified()}?.drop(9)?.forEach {it.delete()}
            val target=File(out,"FILCamera-diagnostics-${UUID.randomUUID().toString().take(8)}.zip")
            ZipOutputStream(target.outputStream()).use {zip ->
                fun entry(name: String, text: String) {zip.putNextEntry(ZipEntry(name));zip.write(text.toByteArray(Charsets.UTF_8));zip.closeEntry()}
                entry("summary.txt",summary())
                synchronized(fileLock) {directory?.let {dir ->
                    (reports()+File(dir,"session.txt")+File(dir,"previous-session.txt")).filter {it.isFile}.forEach {entry(it.name,it.readText())}
                }}
            }
            target
        }
        writer.execute(result)
        return result.get(15,TimeUnit.SECONDS)
    }

    private fun reports(): List<File> = directory?.listFiles()?.filter {it.name.startsWith("error-") && it.extension=="txt"}
        ?.sortedByDescending {it.name}.orEmpty()
    private fun trimReports() {reports().drop(MAX_REPORTS).forEach {it.delete()}}
    private fun writeAtomic(name: String,text: String) {
        val dir=directory ?: return
        val file=AtomicFile(File(dir,name));val stream=file.startWrite()
        try {stream.write(text.toByteArray(Charsets.UTF_8));file.finishWrite(stream)}
        catch(e: Exception) {file.failWrite(stream);throw e}
    }
}
