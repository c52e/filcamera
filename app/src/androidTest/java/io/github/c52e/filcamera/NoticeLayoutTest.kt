package io.github.c52e.filcamera

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.c52e.filcamera.strings.R as Texts
import io.github.c52e.filcamera.strings.tr
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Uses existing debug photos without modifying originals or published media. */
class NoticeLayoutTest {
    @get:Rule(order=0) val foreground=object: org.junit.rules.ExternalResource() {
        override fun before() {
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n ${instrumentation.targetContext.packageName}/io.github.c52e.filcamera.CameraActivity")).use {it.readBytes()}
        }
    }
    @get:Rule(order=1) val ui=createAndroidComposeRule<CameraActivity>()

    @Test fun noticesKeepWorkspaceStableInBothOrientations() {
        lateinit var model: CameraViewModel
        ui.activityRule.scenario.onActivity {model=ViewModelProvider(it)[CameraViewModel::class.java]}
        ui.waitUntil(15000) {model.state.value.photos.isNotEmpty()}
        val photo=model.state.value.photos.first {it.latest!=null}
        val orientation=ui.activity.requestedOrientation
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        fun back() {ui.activityRule.scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()};ui.waitForIdle()}
        fun verify(page: String,anchor: SemanticsNodeInteraction) {
            ui.runOnIdle {model.message("")};ui.waitForIdle()
            val before=anchor.fetchSemanticsNode().boundsInRoot
            val error="Notice layout verification: $page. ".repeat(12)
            ui.runOnIdle {model.message(error)}
            ui.onNodeWithText(error).assertIsDisplayed()
            assertEquals("$page changed with notice",before,anchor.fetchSemanticsNode().boundsInRoot)
            if(page.startsWith("camera") || page.startsWith("editor")) {
                val workspace=ui.onNodeWithContentDescription(tr(if(page.startsWith("camera")) Texts.string.live_viewfinder else Texts.string.photo_viewer)).fetchSemanticsNode().boundsInRoot
                val notice=ui.onNodeWithText(error).fetchSemanticsNode().boundsInRoot
                assertTrue("$page notice overlaps the controls",notice.left>=workspace.left && notice.right<=workspace.right && notice.bottom<=workspace.bottom)
            }
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let {bitmap->
                File(app.cacheDir,"notice-$page.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
            }
            ui.onNodeWithText(tr(Texts.string.close)).performClick()
            ui.onNodeWithText(error).assertDoesNotExist()
            assertEquals("$page changed after dismissal",before,anchor.fetchSemanticsNode().boundsInRoot)
            val success="Notice success: $page"
            ui.runOnIdle {model.message(success,persistent=false)}
            ui.onNodeWithText(success).assertIsDisplayed()
            assertEquals(before,anchor.fetchSemanticsNode().boundsInRoot)
            ui.waitUntil(15000) {model.state.value.message.isEmpty()}
            assertEquals(before,anchor.fetchSemanticsNode().boundsInRoot)
        }
        try {
            for(wide in listOf(false,true)) {
                ui.activityRule.scenario.onActivity {it.requestedOrientation=if(wide) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
                ui.waitUntil(15000) {ui.activity.resources.configuration.orientation==if(wide) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT}
                val suffix=if(wide) "landscape" else "portrait"
                verify("camera-$suffix",ui.onNodeWithContentDescription(tr(Texts.string.capture)))
                // Errors must survive the normal success timeout.
                ui.runOnIdle {model.message("Persistent error")}
                Thread.sleep(3500)
                ui.onNodeWithText("Persistent error").assertIsDisplayed()
                val oldId=model.state.value.messageId
                ui.runOnIdle {model.message("Replacement error")}
                ui.runOnIdle {model.dismissMessage(oldId)}
                ui.onNodeWithText("Replacement error").assertIsDisplayed()
                ui.runOnIdle {model.message("")}
                ui.onNodeWithContentDescription(tr(Texts.string.library)).performClick()
                val entry=ui.onNodeWithContentDescription(tr(Texts.string.open_photo_value,photo.id))
                verify("library-$suffix",entry)
                entry.performClick()
                verify("photo-$suffix",ui.onNodeWithContentDescription(tr(Texts.string.photo_viewer)))
                ui.onNodeWithText(tr(Texts.string.edit_and_develop)).performClick()
                ui.waitUntil(120000) {!model.editor.state.value.busy && model.editor.state.value.file!=null}
                verify("editor-$suffix",ui.onNodeWithText(tr(Texts.string.save_copy)))
                back();back();back()
            }
        } finally {
            ui.runOnIdle {model.message("")}
            ui.activityRule.scenario.onActivity {it.requestedOrientation=orientation}
        }
    }
}
