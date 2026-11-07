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

    @Test fun supportedLanguagesAndFallbackResources() {
        fun text(locale: String, id: Int, vararg args: Any): String {
            val configuration = Configuration(app.resources.configuration)
            configuration.setLocales(LocaleList.forLanguageTags(locale))
            return app.createConfigurationContext(configuration).getString(id, *args)
        }
        assertEquals("FILCamera", text("en", Texts.string.app_name))
        assertEquals("Settings", text("en", Texts.string.settings))
        assertEquals("Settings", text("fr", Texts.string.settings))
        assertEquals("\u8bbe\u7f6e", text("zh-CN", Texts.string.settings))
        assertEquals("White balance", text("en", Texts.string.white_balance))
        assertEquals("\u767d\u5e73\u8861", text("zh-CN", Texts.string.white_balance))
        for (locale in listOf("zh", "zh-CN", "zh-SG", "zh-Hans")) {
            assertEquals(locale, "\u8bbe\u7f6e", text(locale, Texts.string.settings))
        }
        for (locale in listOf("zh-TW", "zh-HK", "zh-MO", "zh-Hant")) {
            assertEquals(locale, "\u8a2d\u5b9a", text(locale, Texts.string.settings))
            assertEquals("\u554f\u984c\u8a3a\u65b7", text(locale, Texts.string.diagnostic_details))
            assertEquals("\u522a\u9664 3 \u5f35\u7167\u7247\uff1f", text(locale, Texts.string.delete_value_photos, 3))
        }
        for (locale in listOf("ja", "ja-JP")) {
            assertEquals("\u8a2d\u5b9a", text(locale, Texts.string.settings))
            assertEquals("\u30db\u30ef\u30a4\u30c8\u30d0\u30e9\u30f3\u30b9", text(locale, Texts.string.white_balance))
            assertEquals("\u554f\u984c\u306e\u8a3a\u65ad", text(locale, Texts.string.diagnostic_details))
            assertEquals("3 \u679a\u306e\u5199\u771f\u3092\u524a\u9664\u3057\u307e\u3059\u304b\uff1f", text(locale, Texts.string.delete_value_photos, 3))
            assertEquals("\u30ba\u30fc\u30e0 1.25 \u500d", text(locale, Texts.string.zoom_2f, 1.25))
        }
        assertEquals("Delete 3 photos?", text("en", Texts.string.delete_value_photos, 3))
    }

    @Test fun recipeSerializationAndDefaultsRemainCompatible() {
        val recipe = FilmRecipe()
        assertEquals(recipe, FilmRecipe.parse(recipe.json()))
        assertEquals(recipe.hash, FilmRecipe.parse(recipe.json()).hash)
        assertEquals(500,recipe.filmDiffusionStrength)
        assertEquals(500,recipe.printDiffusionStrength)
        assertEquals(1000,recipe.grainStrength)
        assertEquals(1000,recipe.halationStrength)
        assertEquals(FilmStocks.AUTO_PAPER, recipe.paperProfile)
        assertEquals(recipe.colorHash, recipe.copy(grainStrength=0, halationStrength=0).colorHash)
        val adjusted=recipe.copy(filmDiffusionStrength=125,printDiffusionStrength=780,grainStrength=600,halationStrength=1800)
        assertEquals(adjusted,FilmRecipe.parse(adjusted.json()))
        assertEquals(recipe.colorHash,adjusted.colorHash)
        assertNotEquals(recipe.hash,adjusted.hash)

        for(version in listOf(2,3)) {
            val base=if(version==2) recipe.copy(paperProfile="kodak_portra_endura") else recipe
            val old=base.json().put("version",version)
            val effects=listOf("filmDiffusion","printDiffusion","grain","halation")
            for(effect in effects) old.remove(effect+"Strength")
            for(mask in 0..15) {
                val enabled=effects.mapIndexed {index,effect->
                    (mask and (1 shl index)!=0).also {old.put(effect,it)}
                }
                val migrated=FilmRecipe.parse(old)
                assertEquals(base.copy(
                    filmDiffusionStrength=if(enabled[0]) 500 else 0,
                    printDiffusionStrength=if(enabled[1]) 500 else 0,
                    grainStrength=if(enabled[2]) 1000 else 0,
                    halationStrength=if(enabled[3]) 1000 else 0
                ),migrated)
                assertTrue(migrated.dirSpatial)
                assertEquals(base.colorHash,migrated.colorHash)
            }
        }
        val legacy=FilmRecipe.legacy()
        val old=legacy.json().put("version",1)
        assertEquals(legacy,FilmRecipe.parse(old))
        assertFalse(legacy.dirSpatial)
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
