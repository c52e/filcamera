package io.github.c52e.filcamera

import android.app.LocaleManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.LocaleList
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.c52e.filcamera.strings.R as Texts
import io.github.c52e.filcamera.strings.tr
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** On-device acceptance. Adds a photo/copy but never deletes existing user media. */
class CameraWorkflowTest {
    @get:Rule(order=0) val foreground=object: org.junit.rules.ExternalResource() {
        override fun before() {
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n ${instrumentation.targetContext.packageName}/io.github.c52e.filcamera.CameraActivity")).use {it.readBytes()}
        }
    }
    @get:Rule(order=1) val ui=createAndroidComposeRule<CameraActivity>()
    private fun <T> owned(body: ()->T): T {
        val task=FutureTask(Callable {body()});FilmWorker.execute {task.run()};return task.get(120,TimeUnit.SECONDS)
    }

    @Test fun localesCaptureAndDarkroom() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val report=JSONObject()
        lateinit var model: CameraViewModel
        ui.activityRule.scenario.onActivity {model=ViewModelProvider(it)[CameraViewModel::class.java]}
        val locales=if(android.os.Build.VERSION.SDK_INT>=33) app.getSystemService(LocaleManager::class.java) else null
        val previousLocales=locales?.applicationLocales
        val previousOrientation=ui.activity.requestedOrientation
        fun stage(name: String) {File(app.cacheDir,"workflow-stage.txt").writeText(name)}
        fun ready()=ui.waitUntil(90000) {model.camera.state.value.let {it.ready && !it.busy && !it.updating && !it.framingPending && !it.switching}}
        fun screenshot(name: String) {
            ui.waitForIdle()
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let {bitmap->
                File(app.cacheDir,"workflow-$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
            }
        }
        try {
            stage("startup");ready()
            val existing=owned {model.repository.scan()}
            val original=existing.firstOrNull()?.source
            val originalHash=original?.let {PairCaptureStore.hash(it)}
            val recipe=model.state.value.recipe.hash
            report.put("existingPhotos",existing.size)
            for(language in if(locales!=null) listOf("en","zh-CN") else listOf("system")) {
                stage("locale-$language")
                if(locales!=null) {
                    ui.runOnIdle {locales.applicationLocales=LocaleList.forLanguageTags(language)}
                    ui.waitUntil(15000) {ui.activity.resources.configuration.locales[0].language==language.substringBefore('-')}
                    ready()
                }
                for(orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                    ui.activityRule.scenario.onActivity {it.requestedOrientation=orientation}
                    val expected=if(orientation==ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
                    ui.waitUntil(15000) {ui.activity.resources.configuration.orientation==expected};ready()
                    ui.onNodeWithContentDescription(tr(Texts.string.capture)).assertIsDisplayed().assertIsEnabled()
                    ui.onNodeWithContentDescription(tr(Texts.string.settings)).performClick()
                    assertTrue(ui.onAllNodesWithText("FILCamera").fetchSemanticsNodes().isNotEmpty())
                    ui.onNodeWithText(tr(Texts.string.open_source_licenses_and_attribution)).assertIsDisplayed()
                    screenshot("$language-$orientation-settings")
                    ui.activityRule.scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
                    ui.onNodeWithContentDescription(tr(Texts.string.color)).performClick()
                    ui.onNodeWithText(tr(Texts.string.film),useUnmergedTree=true).assertExists()
                    screenshot("$language-$orientation-controls")
                    ui.onNodeWithContentDescription(tr(Texts.string.close)).performClick()
                }
            }
            assertEquals(recipe,model.state.value.recipe.hash)
            ui.activityRule.scenario.onActivity {it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT};ready()
            stage("capture")
            val previous=model.camera.state.value.completed
            ui.onNodeWithContentDescription(tr(Texts.string.capture)).performClick()
            ui.waitUntil(120000) {
                val state=model.camera.state.value
                File(app.cacheDir,"workflow-capture-state.txt").writeText(state.toString())
                state.completed!=previous && !state.busy
            }
            val id=model.camera.state.value.completed
            ui.waitUntil(15000) {model.state.value.photos.any {it.id==id}}
            val photo=model.state.value.photos.first {it.id==id}
            val capture=JSONObject(photo.record.readText())
            assertEquals("complete",capture.getString("status"))
            assertEquals(0L,capture.getJSONObject("render").getJSONObject("gpuTransfers").getLong("intermediateReadbackBytes"))
            val version=checkNotNull(photo.latest)
            val uri=owned {model.repository.publish(version)}
            assertEquals(uri,owned {model.repository.publish(version)})
            assertEquals(PairCaptureStore.hash(version.file),PreviewLut.digest(checkNotNull(app.contentResolver.openInputStream(uri)).use {it.readBytes()}))
            report.put("captureMs",capture.getJSONObject("timing").optDouble("totalMs"))
            stage("darkroom")
            val dngHash=PairCaptureStore.hash(photo.source)
            ui.onNodeWithContentDescription(tr(Texts.string.library)).performClick()
            ui.onNodeWithContentDescription(tr(Texts.string.open_photo_value,photo.id)).performClick()
            ui.onNodeWithText(tr(Texts.string.edit_and_develop)).performClick()
            ui.waitUntil(120000) {!model.editor.state.value.busy && model.editor.state.value.file!=null}
            screenshot("darkroom")
            ui.runOnIdle {model.editor.change(model.editor.state.value.recipe.copy(print=2))}
            ui.waitUntil(120000) {!model.editor.state.value.busy && model.editor.state.value.resultHash==model.editor.state.value.recipe.hash}
            ui.onNodeWithText(tr(Texts.string.save_copy)).performClick()
            ui.waitUntil(120000) {model.state.value.photos.first {it.id==id}.versions.size==2}
            assertEquals(dngHash,PairCaptureStore.hash(photo.source))
            assertEquals(recipe,model.state.value.recipe.hash)
            if(original!=null) assertEquals(originalHash,PairCaptureStore.hash(original))
            report.put("savedCopy",true).put("originalPreserved",true).put("recipePreserved",true)
            stage("passed")
        } finally {
            report.put("lastStage",File(app.cacheDir,"workflow-stage.txt").readText())
            File(app.cacheDir,"workflow-report.json").writeText(report.toString(2))
            ui.activityRule.scenario.onActivity {it.requestedOrientation=previousOrientation}
            if(locales!=null && previousLocales!=null) ui.runOnIdle {locales.applicationLocales=previousLocales}
        }
    }
}

