package io.github.c52e.filcamera

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

/** Real camera acceptance: exercises buffer changes and crop-only transitions in both directions. */
class CropPreviewTest {
    @get:Rule(order=0) val foreground=object: org.junit.rules.ExternalResource() {
        override fun before() {
            val i=InstrumentationRegistry.getInstrumentation()
            android.os.ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand(
                "am start -W -n ${i.targetContext.packageName}/io.github.c52e.filcamera.CameraActivity")).use {it.readBytes()}
        }
    }
    @get:Rule(order=1) val ui=createAndroidComposeRule<CameraActivity>()

    @Test fun rejectedLargeBuffersRecoverOnSameLensAndStayRemembered() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var model: CameraViewModel
        ui.activityRule.scenario.onActivity {model=ViewModelProvider(it)[CameraViewModel::class.java]}
        fun ready()=ui.waitUntil(90000) {model.camera.state.value.let {
            it.ready && !it.busy && !it.updating && !it.framingPending && !it.switching
        }}
        ready()
        val original=model.camera.state.value.selectedFocal
        val initial=checkNotNull(model.camera.state.value.info)
        val native=model.camera.state.value.options.first {it.targetMm==null && it.lens?.route==initial.route}
        ui.runOnIdle {model.camera.selectFocal(native.id)};ready()
        val nativeInfo=checkNotNull(model.camera.state.value.info)
        val limit=nativeInfo.preview.width.toLong()*nativeInfo.preview.height
        val crops=model.camera.state.value.options.filter {it.targetMm!=null && it.lens?.route==nativeInfo.route}
        val enlarged=crops.firstOrNull {nativeInfo.previewFor(it.targetMm).let {s->s.width.toLong()*s.height>limit}}
        org.junit.Assume.assumeNotNull(enlarged)
        val crop=checkNotNull(enlarged)
        val report=JSONObject().put("device",android.os.Build.MODEL).put("limitPixels",limit)
        try {
            ui.runOnIdle {model.camera.setPreviewFailureLimitForTest(limit)}
            val starts=model.camera.state.value.sourceStarts
            ui.runOnIdle {model.camera.selectFocal(crop.id)};ready()
            val recovered=model.camera.state.value
            val recoveredInfo=checkNotNull(recovered.info)
            assertEquals(crop.id,recovered.selectedFocal)
            assertEquals(nativeInfo.route,recoveredInfo.route)
            assertEquals(nativeInfo.preview,recoveredInfo.preview)
            assertEquals("Session retries must keep the same camera device/target",starts+1,recovered.sourceStarts)
            val failures=model.camera.previewFailureCountForTest()
            assertTrue("Fault injection must exercise recovery",failures>0)
            report.put("injectedFailures",failures).put("recoveredInput",recoveredInfo.preview.toString())
                .put("selectedFocal",recovered.selectedFocal).put("sameRoute",true)
            val restarts=recovered.sourceStarts
            for(option in listOf(native)+crops+listOf(native,crop)) {
                ui.runOnIdle {model.camera.selectFocal(option.id)};ready()
                assertEquals(option.id,model.camera.state.value.selectedFocal)
                assertEquals(restarts,model.camera.state.value.sourceStarts)
                assertEquals("Rejected combinations must not be retried",failures,model.camera.previewFailureCountForTest())
            }
            model.camera.resetFrameProbe();SystemClock.sleep(3000)
            val probe=model.camera.frameProbe()
            assertTrue(probe.toString(),probe.getInt("frames")>20)
            report.put("probe",probe).put("noRepeatFailures",true)
            val previous=model.camera.state.value.completed
            ui.onNodeWithContentDescription(tr(Texts.string.capture)).performClick()
            ui.waitUntil(120000) {model.camera.state.value.let {it.completed!=previous && !it.busy && it.ready}}
            model.camera.resetFrameProbe()
            ui.waitUntil(10000) {model.camera.frameProbe().getInt("frames")>=5}
            report.put("captureId",model.camera.state.value.completed).put("previewResumedAfterCapture",true)
            File(app.cacheDir,"preview-fallback-result.json").writeText(report.toString(2))
        } finally {
            ui.runOnIdle {
                model.camera.setPreviewFailureLimitForTest(Long.MAX_VALUE)
                model.camera.selectFocal(original)
            }
        }
    }

    @Test fun cropBuffersAndSwitchingOnDevice() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var model: CameraViewModel
        ui.activityRule.scenario.onActivity {model=ViewModelProvider(it)[CameraViewModel::class.java]}
        fun ready()=ui.waitUntil(90000) {model.camera.state.value.let {
            it.ready && !it.busy && !it.updating && !it.framingPending && !it.switching
        }}
        ready()
        val original=model.camera.state.value.selectedFocal
        val report=JSONObject().put("device",android.os.Build.MODEL)
        val results=JSONArray();report.put("results",results)
        val options=model.camera.state.value.options.filter {it.lens!=null}
        val natives=options.filter {it.targetMm==null}
        val crops=options.filter {it.targetMm!=null}
        val sequence=natives+crops+crops.reversed()+natives
        try {
            for(option in sequence) {
                val before=model.camera.state.value
                val expectedRestart=before.info?.route!=option.lens?.route || before.info?.previewFor(option.targetMm)!=before.info?.preview
                val started=SystemClock.elapsedRealtime()
                ui.runOnIdle {model.camera.selectFocal(option.id)}
                ready()
                val elapsed=SystemClock.elapsedRealtime()-started
                val state=model.camera.state.value
                assertEquals(option.id,state.selectedFocal)
                val info=checkNotNull(state.info)
                assertEquals(option.lens?.route,info.route)
                assertEquals(info.previewFor(option.targetMm),info.preview)
                assertEquals(if(expectedRestart) 1 else 0,state.sourceStarts-before.sourceStarts)
                val scale=checkNotNull(state.framing).scale
                model.camera.resetFrameProbe()
                SystemClock.sleep(3000)
                val probe=model.camera.frameProbe()
                assertTrue("Preview must keep producing frames: $option $probe",probe.getInt("frames")>20)
                results.put(JSONObject().put("id",option.id).put("nativeMm",info.optics?.equivalentMm)
                    .put("input",info.preview.toString()).put("scale",scale)
                    .put("raw",info.still.toString()).put("supportedStreams",JSONArray(info.previewStreams.map {it.toString()}))
                    .put("effectiveWidth",info.preview.width*scale).put("effectiveHeight",info.preview.height*scale)
                    .put("restart",expectedRestart).put("switchMs",elapsed).put("probe",probe))
                File(app.cacheDir,"crop-preview-result.json").writeText(report.toString(2))
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let {bitmap->
                    File(app.cacheDir,"crop-preview-${option.id.replace(':','-')}.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                    };bitmap.recycle()
                }
            }
            // Confirm the larger preview buffer can coexist with a real RAW capture and resume.
            crops.maxByOrNull {option->
                checkNotNull(model.camera.state.value.info).previewFor(option.targetMm).let {it.width.toLong()*it.height}
            }?.let {option->
                ui.runOnIdle {model.camera.selectFocal(option.id)};ready()
                val previous=model.camera.state.value.completed
                ui.onNodeWithContentDescription(tr(Texts.string.capture)).performClick()
                ui.waitUntil(120000) {model.camera.state.value.let {it.completed!=previous && !it.busy && it.ready}}
                report.put("capturedFocal",option.id).put("captureId",model.camera.state.value.completed)
                model.camera.resetFrameProbe()
                ui.waitUntil(10000) {model.camera.frameProbe().getInt("frames")>=5}
                report.put("previewResumedAfterCapture",true)
                File(app.cacheDir,"crop-preview-result.json").writeText(report.toString(2))
            }
        } finally {
            ui.runOnIdle {model.camera.selectFocal(original)}
        }
    }
}
