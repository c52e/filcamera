package io.github.c52e.filcamera

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.c52e.filcamera.camera.LocalDiagnostics
import io.github.c52e.filcamera.strings.R as Texts
import io.github.c52e.filcamera.strings.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun DiagnosticsPanel() {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val reports by produceState(listOf(LocalDiagnostics.summary())) {
        value=withContext(Dispatchers.IO) {LocalDiagnostics.recentReports()}
    }
    var selected by remember {mutableIntStateOf(0)}
    val report=reports.getOrElse(selected) {reports.first()}
    var busy by remember {mutableStateOf(false)}
    var feedback by remember {mutableStateOf("")}
    Text(tr(Texts.string.diagnostic_details),fontSize=24.sp)
    Text(tr(Texts.string.diagnostic_privacy),color=Muted,fontSize=13.sp)
    Spacer(Modifier.height(8.dp))
    TextButton(onClick={
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("FILCamera diagnostics",report.substringBefore("\nException:\n").take(6000)))
        feedback=tr(Texts.string.diagnostic_copied)
    }) {Text(tr(Texts.string.diagnostic_copy))}
    TextButton(enabled=!busy,onClick={
        busy=true;feedback=""
        scope.launch {
            try {
                val file=withContext(Dispatchers.IO) {LocalDiagnostics.export(context.applicationContext)}
                val uri=FileProvider.getUriForFile(context,context.packageName+".photos",file)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type="application/zip";putExtra(Intent.EXTRA_STREAM,uri)
                    clipData=ClipData.newRawUri("FILCamera diagnostics",uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },tr(Texts.string.diagnostic_export)))
            } catch(e: kotlinx.coroutines.CancellationException) {throw e}
            catch(e: Exception) {feedback=tr(Texts.string.diagnostic_export_failed)}
            finally {busy=false}
        }
    }) {Text(tr(if(busy) Texts.string.diagnostic_exporting else Texts.string.diagnostic_export))}
    if(feedback.isNotBlank()) Text(feedback,color=Accent)
    Text(tr(Texts.string.diagnostic_recent),fontSize=14.sp)
    if(reports.size>1) reports.forEachIndexed {index,text ->
        TextButton(onClick={selected=index}) {Text((if(selected==index) "• " else "")+text.lineSequence().first(),fontSize=11.sp)}
    }
    SelectionContainer {
        Text(report,Modifier.fillMaxWidth().heightIn(max=300.dp).verticalScroll(rememberScrollState()),fontSize=11.sp)
    }
}
