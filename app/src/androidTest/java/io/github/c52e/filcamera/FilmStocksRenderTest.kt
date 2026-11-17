package io.github.c52e.filcamera

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.github.c52e.filcamera.rawvulkan.VulkanRawDeveloper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class FilmStocksRenderTest {
    @Test fun newColorStocksBakeAndRender() {
        val base = FilmRecipe()
        val cine = listOf(
            "kodak_vision3_50d", "kodak_vision3_200t", "kodak_vision3_250d",
            "kodak_vision3_500t", "kodak_verita_200d"
        ).map { base.copy(filmProfile = it) }
        assertTrue(cine.all { it.resolvedPaperProfile == "kodak_2383" })
        val slides = listOf(
            "fujifilm_provia_100f", "fujifilm_velvia_100", "kodak_ektachrome_100", "kodak_kodachrome_64"
        ).map { base.copy(filmProfile = it) }
        assertTrue(slides.all { it.directScan })
        verifyRenders(cine + slides + listOf(
            cine.first().copy(paperProfile = "kodak_2393"),
            cine.first().copy(paperProfile = "kodak_portra_endura"),
            base.copy(paperProfile = "kodak_2383")
        ))
    }

    @Test fun newMonochromeStocksBakeAndRender() {
        val base = FilmRecipe()
        val negatives = listOf("kodak_doublex", "kodak_2302").map { base.copy(filmProfile = it) }
        assertTrue(negatives.all { it.resolvedPaperProfile == "kodak_2302" })
        val reversal = base.copy(filmProfile = "kodak_trix")
        assertTrue(reversal.directScan)
        val color2302 = negatives.last().copy(paperProfile = "kodak_portra_endura")
        verifyRenders(negatives + reversal + listOf(
            negatives.first().copy(paperProfile = "kodak_portra_endura"),
            color2302,
            base.copy(paperProfile = "kodak_2302")
        ))
    }

    private fun verifyRenders(combinations: List<FilmRecipe>) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val task = FutureTask {
            val width = 192
            val height = 128
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val ramp = IntArray(width * height) { p ->
                val col = p % width
                if (p / width < height / 4) {
                    when (col * 3 / width) {
                        0 -> Color.rgb(200, 60, 60)
                        1 -> Color.rgb(60, 200, 60)
                        else -> Color.rgb(60, 60, 200)
                    }
                } else {
                    val value = 8 + 225 * col / (width - 1)
                    Color.rgb(value, value, value)
                }
            }
            bitmap.setPixels(ramp, 0, width, 0, 0, width, height)
            val assets = prepareFilmAssets(app)
            val output = FilmOutput.buffer(width, height)
            try {
              VulkanRawDeveloper.convertSrgbBitmap(bitmap, BitmapEditInput.srgbToProPhoto).use { input ->
                for (recipe in combinations) {
                    assertEquals(recipe, FilmRecipe.parse(recipe.json()))
                    val name = "${recipe.filmProfile} / ${recipe.resolvedPaperProfile}"
                    val checkPrintCache = recipe.filmProfile == "kodak_vision3_50d" &&
                        recipe.paperProfile == FilmStocks.AUTO_PAPER
                    val lut = File(app.cacheDir, "color-stock-${SystemClock.elapsedRealtimeNanos()}.lut")
                    try {
                        val bake = JSONObject(FilmEngine.bakePreviewLut(
                            assets.path, SystemClock.elapsedRealtimeNanos(), lut.path, recipe
                        ))
                        assertFalse(name, bake.getBoolean("cancelled"))
                        assertEquals(name, 65L * 65 * 65 * 8, lut.length())
                        assertFalse(bake.getJSONObject("resolvedSpatial").getJSONObject("scanner").getBoolean("active"))
                        verify2302Filters(recipe, bake)
                    } finally {
                        lut.delete()
                    }
                    val render = JSONObject(FilmEngine.render(
                        assets.path, width, height, recipe, SystemClock.elapsedRealtimeNanos(), 123L, output,
                        gpuImage = input.gpuImage, cacheFilm = checkPrintCache || recipe.directScan
                    ))
                    assertFalse(name, render.getBoolean("cancelled"))
                    val spatial = render.getJSONObject("resolvedSpatial")
                    val filters = render.getJSONObject("neutralPrintFilters")
                    if (recipe.filmProfile == "kodak_vision3_200t" || recipe.filmProfile == "kodak_vision3_500t") {
                        assertEquals("T", render.getString("filmReferenceIlluminant"))
                        for (primary in 0..2) {
                            val patch = (width * (height / 8) + width * (2 * primary + 1) / 6) * 4
                            val rgb = (0..2).map { output.get(patch + it).toInt() and 255 }
                            assertEquals("$name patch $primary rendered as $rgb", primary, rgb.indices.maxBy { rgb[it] })
                        }
                    }
                    if (recipe.directScan) {
                        assertEquals("disabled", render.getJSONObject("filmCache").getString("state"))
                        assertFalse(spatial.getJSONObject("printDiffusion").getBoolean("active"))
                        assertEquals("not-applicable", filters.getString("source"))
                    }
                    if (checkPrintCache) {
                        assertEquals("miss", render.getJSONObject("filmCache").getString("state"))
                        val saved = ByteArray(output.capacity()).also { output.duplicate().apply { clear(); get(it) } }
                        val repeat = JSONObject(FilmEngine.render(
                            assets.path, width, height, recipe, SystemClock.elapsedRealtimeNanos(), 123L, output,
                            gpuImage = input.gpuImage, cacheFilm = true
                        ))
                        assertEquals("hit", repeat.getJSONObject("filmCache").getString("state"))
                        val cached = ByteArray(output.capacity()).also { output.duplicate().apply { clear(); get(it) } }
                        assertTrue(saved.contentEquals(cached))
                    }
                    if (recipe.filmProfile == "kodak_2302") {
                        assertFalse(spatial.getBoolean("dirActive"))
                        verify2302Filters(recipe, render)
                    }
                    if (recipe.filmProfile in listOf("kodak_doublex", "kodak_trix", "kodak_2302")) {
                        val grain = spatial.getJSONObject("grain")
                        assertTrue(grain.getBoolean("monochrome"))
                        assertEquals(if (recipe.directScan) .003 else .01, grain.getJSONArray("densityMin").getDouble(0), 1e-6)
                    }
                    val transfers = render.getJSONObject("gpuTransfers")
                    assertEquals(name, 0L, transfers.getLong("imageUploadBytes"))
                    assertEquals(name, 0L, transfers.getLong("intermediateReadbackBytes"))
                    val row = width * (height / 2) * 4
                    val dark = output.get(row + 1).toInt() and 255
                    val bright = output.get(row + (width - 1) * 4 + 1).toInt() and 255
                    assertTrue("$name produced a flat or inverted ramp ($dark, $bright)", bright > dark + 20)
                    if (recipe.filmProfile == "kodak_2302") {
                        // The neutral reference uses the default 5-minute curves.
                        for (col in listOf(width / 3, width / 2, width * 2 / 3)) {
                            val rgb = (0..2).map { output.get(row + col * 4 + it).toInt() and 255 }
                            assertTrue("$name colored a neutral patch: $rgb", rgb.max() - rgb.min() <= 12)
                        }
                    }
                }
              }
            } finally {
                FilmEngine.close()
                bitmap.recycle()
            }
        }
        FilmWorker.execute { task.run() }
        task.get(180, TimeUnit.SECONDS)
    }

    private fun verify2302Filters(recipe: FilmRecipe, result: JSONObject) {
        if (recipe.filmProfile != "kodak_2302") return
        val filters = result.getJSONObject("neutralPrintFilters")
        val cc = filters.getJSONArray("cmyCc")
        val blackAndWhite = recipe.resolvedPaperProfile == "kodak_2302"
        val expected = when {
            blackAndWhite -> doubleArrayOf(0.0, 0.0, 0.0)
            else -> doubleArrayOf(0.0, 79.667, 98.142)
        }
        assertEquals("database", filters.getString("source"))
        expected.forEachIndexed { channel, value -> assertEquals(value, cc.getDouble(channel), 1e-4) }
    }
}
