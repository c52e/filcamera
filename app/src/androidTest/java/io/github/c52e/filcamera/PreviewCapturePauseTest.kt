package io.github.c52e.filcamera

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.c52e.filcamera.strings.R as Texts
import io.github.c52e.filcamera.strings.tr
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** On-device regression; retains the captured test photo. */
class PreviewCapturePauseTest {
    @get:Rule(order=0) val foreground=object: org.junit.rules.ExternalResource() {
        override fun before() {
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n ${instrumentation.targetContext.packageName}/io.github.c52e.filcamera.CameraActivity")).use {it.readBytes()}
        }
    }
    @get:Rule(order=1) val ui=createAndroidComposeRule<CameraActivity>()
    @Test fun previewStopsAfterCaptureAndResumesAfterProcessing() {
        lateinit var model: CameraViewModel
        ui.activityRule.scenario.onActivity {model=ViewModelProvider(it)[CameraViewModel::class.java]}
        ui.waitUntil(90000) {model.camera.state.value.let {
            it.ready && !it.busy && !it.updating && !it.framingPending && !it.switching && !it.whiteBalance.pending
        }}
        model.camera.resetFrameProbe()
        ui.waitUntil(10000) {model.camera.frameProbe().getInt("frames")>=3}
        val previous=model.camera.state.value.completed
        val release=CountDownLatch(1)
        // Hold development so the assertion cannot accidentally sample after a fast render.
        FilmWorker.execute {release.await(30,TimeUnit.SECONDS)}
        try {
            ui.onNodeWithContentDescription(tr(Texts.string.capture)).performClick()
            ui.waitUntil(15000) {model.camera.state.value.processing}
            ui.onNodeWithText(tr(Texts.string.capture_complete_can_move)).assertIsDisplayed()
            // Allow the one in-flight GL frame to finish, then verify no further effect work.
            SystemClock.sleep(250)
            val pausedFrames=model.camera.frameProbe().getInt("frames")
            SystemClock.sleep(750)
            assertEquals("Preview effects must stop during development",pausedFrames,model.camera.frameProbe().getInt("frames"))
            assertTrue(model.camera.state.value.busy)
        } finally {release.countDown()}
        ui.waitUntil(120000) {model.camera.state.value.let {it.completed!=previous && !it.busy && it.ready}}
        assertFalse(model.camera.state.value.processing)
        ui.onNodeWithText(tr(Texts.string.capture_complete_can_move)).assertDoesNotExist()
        val finishedFrames=model.camera.frameProbe().getInt("frames")
        ui.waitUntil(10000) {model.camera.frameProbe().getInt("frames")>finishedFrames+2}
    }

}
