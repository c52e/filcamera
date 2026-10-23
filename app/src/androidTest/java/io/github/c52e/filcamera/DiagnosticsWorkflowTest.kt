package io.github.c52e.filcamera

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.c52e.filcamera.camera.CameraController
import io.github.c52e.filcamera.camera.LensRoute
import io.github.c52e.filcamera.camera.LocalDiagnostics
import io.github.c52e.filcamera.strings.R as Texts
import io.github.c52e.filcamera.strings.tr
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

/** Exercises real Camera2 failure + screenshot/copy/share, without changing user media or camera IDs. */
class DiagnosticsWorkflowTest {
    @get:Rule(order=0) val foreground=object: org.junit.rules.ExternalResource() {
        override fun before() {
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n ${instrumentation.targetContext.packageName}/io.github.c52e.filcamera.CameraActivity")).use {it.readBytes()}
        }
    }
    @get:Rule(order=1) val ui=createAndroidComposeRule<CameraActivity>()

    @Test fun boundedRedactedReports() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        repeat(160) {LocalDiagnostics.event("TEST.breadcrumb","index=$it")}
        repeat(7) {
            LocalDiagnostics.failure("IO-001","import-dng","Could not open content://private/photos/secret.dng",
                IllegalArgumentException("/storage/emulated/0/DCIM/private.dng",IllegalStateException("C:\\private\\photo.dng")),
                "test=$it",synchronous=true)
        }
        val archive=LocalDiagnostics.export(context)
        ZipFile(archive).use {zip ->
            val errors=zip.entries().asSequence().filter {it.name.startsWith("error-")}.toList()
            assertEquals(5,errors.size)
            val text=zip.entries().asSequence().joinToString("\n") {zip.getInputStream(it).bufferedReader().readText()}
            assertFalse(text.contains("content://private"))
            assertFalse(text.contains("/storage/emulated/0/DCIM"))
            assertFalse(text.contains("C:\\private"))
            assertTrue(text.contains("Caused by: java.lang.IllegalStateException"))
            assertTrue(text.contains(BuildConfig.DIAGNOSTIC_BUILD))
            assertFalse(zip.getInputStream(zip.getEntry("session.txt")).bufferedReader().readText().contains("index=0\n"))
            assertTrue(archive.length()<2_000_000)
        }
    }

    @Test fun cameraFailureCopyAndShare() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        lateinit var model: CameraViewModel
        ui.activityRule.scenario.onActivity {model=ViewModelProvider(it)[CameraViewModel::class.java]}
        ui.waitUntil(90000) {model.camera.state.value.let {it.ready && !it.updating}}
        val failed=CountDownLatch(1)
        val result=AtomicReference("")
        val texture=SurfaceTexture(false)
        val surface=Surface(texture)
        val controller=CameraController(context,{state ->
            if(state.failedRoute!=null) {result.set(state.message);failed.countDown()}
        },{it.close()})
        try {
            controller.start(texture,surface,{surface.release();texture.release()},
                LensRoute("diagnostic-missing-camera","diagnostic-missing-camera"),73) {}
            assertTrue(failed.await(10,TimeUnit.SECONDS))
            assertTrue(result.get().contains("CAM-012"))
            assertTrue(LocalDiagnostics.summary().contains("CameraController.start"))
            ui.runOnIdle {model.message(result.get())}
            ui.onNodeWithText("CAM-012",substring=true).assertIsDisplayed()
            screenshot("error")
            ui.onNodeWithText(tr(Texts.string.diagnostic_details)).performClick()
            ui.onNodeWithText(tr(Texts.string.diagnostic_copy)).performClick()
            ui.runOnIdle {
                val copied=context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
                assertTrue(copied.contains("CAM-012"))
                assertTrue(copied.contains("attempt=73"))
            }
            screenshot("details")
            val sent=AtomicReference<Intent>()
            val monitor=object: Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if(intent.action!=Intent.ACTION_CHOOSER) return null
                    @Suppress("DEPRECATION")
                    sent.set(intent.getParcelableExtra(Intent.EXTRA_INTENT))
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null)
                }
            }
            instrumentation.addMonitor(monitor)
            try {
                ui.onNodeWithText(tr(Texts.string.diagnostic_export)).performClick()
                ui.waitUntil(15000) {sent.get()!=null}
                val share=sent.get()
                assertEquals("application/zip",share.type)
                assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                @Suppress("DEPRECATION")
                val uri=share.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
                assertEquals("content",uri.scheme)
                assertTrue(context.contentResolver.openInputStream(uri)!!.use {it.readBytes()}.size>100)
                assertEquals(uri,share.clipData!!.getItemAt(0).uri)
            } finally {instrumentation.removeMonitor(monitor)}
            ui.activityRule.scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
            ui.runOnIdle {model.dismissMessage(model.state.value.messageId)}
            ui.waitUntil(90000) {model.camera.state.value.let {it.ready && !it.updating}}
            ui.onNodeWithContentDescription(tr(Texts.string.capture)).assertIsEnabled()
        } finally {controller.close()}
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let {bitmap ->
            File(instrumentation.targetContext.cacheDir,"diagnostics-$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
            bitmap.recycle()
        }
    }
}
