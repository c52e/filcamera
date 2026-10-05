package io.github.c52e.filcamera

import android.content.res.Configuration
import android.os.LocaleList
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.c52e.filcamera.strings.R as Texts
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Synthetic tests: no real photograph, camera identity or private fixture dependency. */
@RunWith(AndroidJUnit4::class)
class PublicProjectTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun captureAndPreviewUseTheSamePackagedAssets() {
        fun manifest(path: String)=org.json.JSONObject(app.assets.open(path).bufferedReader().use {it.readText()})
        val capture=manifest("lab/manifest.json")
        val preview=manifest("preview/engine.json")
        val assets=capture.getJSONObject("assets")
        assertEquals(0,capture.getJSONArray("inputs").length())
        assertEquals(assets.length(),preview.getJSONObject("assets").length())
        for(name in assets.keys()) {
            val expected=assets.getString(name)
            assertEquals(expected,preview.getJSONObject("assets").getString(name))
            assertEquals(expected,PreviewLut.digest(app.assets.open("lab/assets/$name").use {it.readBytes()}))
        }
    }

    @Test fun englishChineseAndFallbackResources() {
        fun text(locale: String, id: Int): String {
            val configuration = Configuration(app.resources.configuration)
            configuration.setLocales(LocaleList.forLanguageTags(locale))
            return app.createConfigurationContext(configuration).getString(id)
        }
        assertEquals("FILCamera", text("en", Texts.string.app_name))
        assertEquals("Settings", text("en", Texts.string.settings))
        assertEquals("Settings", text("fr", Texts.string.settings))
        assertEquals("\u8bbe\u7f6e", text("zh-CN", Texts.string.settings))
        assertEquals("White balance", text("en", Texts.string.white_balance))
        assertEquals("\u767d\u5e73\u8861", text("zh-CN", Texts.string.white_balance))
        assertEquals("Delete 3 photos?", app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags("en"))
        }).getString(Texts.string.delete_value_photos, 3))
    }

    @Test fun recipeSerializationAndDefaultsRemainCompatible() {
        val recipe = FilmRecipe()
        assertEquals(recipe, FilmRecipe.parse(recipe.json()))
        assertEquals(recipe.hash, FilmRecipe.parse(recipe.json()).hash)
        assertTrue(recipe.filmDiffusion && recipe.printDiffusion && recipe.grain && recipe.halation)
        assertEquals(FilmStocks.AUTO_PAPER, recipe.paperProfile)
        assertEquals(recipe.colorHash, recipe.copy(grain=false, halation=false).colorHash)
    }

    @Test fun publicAssetsBakeRepeatableLutAndRejectCorruption() {
        val directory = File(app.cacheDir, "public-lut-test-${SystemClock.elapsedRealtimeNanos()}")
        val task = FutureTask {
            try {
                val store = PreviewLutStore(app, directory, FilmRecipe.legacy())
                assertNull(store.cached())
                val first = store.bake(SystemClock.elapsedRealtimeNanos()) { true }
                assertEquals(first.lut.sha256, store.cached()!!.lut.sha256)
                val second = store.bake(SystemClock.elapsedRealtimeNanos()) { true }
                assertEquals(first.lut.sha256, second.lut.sha256)
                assertEquals(PreviewLut.BYTES, first.lut.pixels().remaining())
                File(directory, "${store.key}-${first.lut.sha256}.rgba16f").writeBytes(byteArrayOf(0))
                assertNull(store.cached())
            } finally {
                FilmEngine.close()
                directory.deleteRecursively()
            }
        }
        FilmWorker.execute { task.run() }
        task.get(180, TimeUnit.SECONDS)
    }
}
