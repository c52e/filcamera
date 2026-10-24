package io.github.c52e.filcamera

import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Same-binary A/B: compare cached edits to complete renders before JPEG encoding. */
class GpuFilmCacheTest {
    @Test fun retainedDngCacheParityAndPerformance() {
        val args=InstrumentationRegistry.getArguments()
        val relative=args.getString("retainedDng")
        assumeNotNull(relative)
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val task=FutureTask {
            val source=File(app.filesDir,checkNotNull(relative))
            val sourceHash=PairCaptureStore.hash(source)
            val input=DngEditInput(app)
            val rows=JSONArray()
            val report=JSONObject().put("model",Build.MODEL).put("hardware",Build.HARDWARE)
                .put("sourceSha256",sourceHash).put("runs",rows)
            val reportFile=File(app.cacheDir,"gpu-film-cache.json")
            val assets=PreviewLutStore(app).prepareAssets()
            val base=FilmRecipe() // All spatial effects, including fixed-seed grain.
            var prepared=input.prepare(source,sourceHash,CaptureTiming(),false,{false})
            var output=FilmOutput.buffer(prepared.width,prepared.height)
            fun render(label: String,recipe: FilmRecipe=base,seed: Long=123,enabled: Boolean=true): JSONObject {
                val result=JSONObject(FilmEngine.render(assets.path,"",prepared.width,prepared.height,recipe,
                    SystemClock.elapsedRealtimeNanos(),"",seed,output,prepared.pixels,prepared.owner.gpuImage,cacheFilm=enabled))
                check(!result.getBoolean("cancelled"))
                val digest=MessageDigest.getInstance("SHA-256")
                digest.update(output.duplicate().apply {clear()})
                val hash=digest.digest().joinToString("") {"%02x".format(it.toInt() and 255)}
                result.put("rgbaSha256",hash).put("case",label).put("width",prepared.width).put("height",prepared.height)
                    .put("cacheRequested",enabled)
                val transfers=result.getJSONObject("gpuTransfers")
                check(transfers.getLong("imageUploadBytes")==0L && transfers.getLong("intermediateReadbackBytes")==0L)
                if(result.getJSONObject("filmCache").getString("state")=="hit") {
                    val stages=result.getJSONObject("stageTimings").getJSONObject("stages_ms")
                    for(stage in listOf("filming_expose","develop","halation","grain","dir_couplers"))
                        check(stages.optDouble(stage,0.0)==0.0) {"Film stage ran on hit: $stage"}
                }
                rows.put(result);reportFile.writeText(report.toString(2))
                android.util.Log.i("GpuFilmCache","$label ${prepared.width}x${prepared.height} ${result.getDouble("renderMs")}ms ${result.getJSONObject("filmCache")}")
                return result
            }
            fun state(result: JSONObject,expected: String) {
                check(result.getJSONObject("filmCache").getString("state")==expected) {"${result.getString("case")}: expected $expected"}
            }
            fun equal(a: JSONObject,b: JSONObject) {
                check(a.getString("rgbaSha256")==b.getString("rgbaSha256")) {"Cache changed pixels: ${a.getString("case")} vs ${b.getString("case")}"}
            }
            try {
                // Alternate complete/cached paths in one binary. Each enable follows
                // a forced render, and every hit has its own pixel-identical control.
                for(index in 0..3) {
                    val recipe=base.copy(print=index,magenta=index)
                    val control=render("preview-control-$index",recipe,enabled=false);state(control,"disabled")
                    val miss=render("preview-prime-$index",recipe);state(miss,"miss");equal(control,miss)
                    val hit=render("preview-hit-$index",recipe);state(hit,"hit");equal(control,hit)
                }
                // Changes confined to printing must preserve the same cached film.
                val printEdits=listOf(
                    "print-exposure" to base.copy(print=2), "print-compensation" to base.copy(compensate=true),
                    "paper-contrast" to base.copy(contrast=25), "magenta" to base.copy(magenta=4),
                    "yellow" to base.copy(yellow=4), "paper-profile" to base.copy(paperProfile="kodak_supra_endura"),
                    "print-diffusion" to base.copy(printDiffusion=false))
                for((label,recipe) in printEdits) {
                    render("$label-prime")
                    val cached=render("$label-cached",recipe);state(cached,"hit")
                    val control=render("$label-control",recipe,enabled=false);equal(control,cached)
                }
                // Every exposed film-side control and the grain seed invalidate.
                val filmEdits=listOf(
                    "film-exposure" to base.copy(film=2), "development" to base.copy(development=22),
                    "film-profile" to base.copy(filmProfile="kodak_portra_160"),
                    "film-diffusion" to base.copy(filmDiffusion=false), "grain" to base.copy(grain=false),
                    "halation" to base.copy(halation=false), "legacy-spatial" to FilmRecipe.legacy())
                for((label,recipe) in filmEdits) {
                    render("$label-prime")
                    val changed=render("$label-invalidated",recipe);state(changed,"miss")
                    val hit=render("$label-hit",recipe);state(hit,"hit");equal(changed,hit)
                    val control=render("$label-control",recipe,enabled=false);equal(control,changed)
                }
                render("seed-prime")
                val seedChanged=render("seed-invalidated",seed=124);state(seedChanged,"miss")
                equal(seedChanged,render("seed-control",seed=124,enabled=false))

                // A failed dispatch must discard the cache rather than publish a
                // partly rendered negative. Restore the original mode afterwards.
                render("failure-prime")
                FilmEngine.testGpuFailure(true)
                var failed=false
                try {render("injected-failure",base.copy(print=1))} catch(_: IllegalStateException) {failed=true}
                finally {FilmEngine.testGpuFailure(false)}
                check(failed)
                state(render("failure-recovered"),"miss")
                state(render("failure-recovered-hit"),"hit")
                FilmEngine.close()
                state(render("close-recovered"),"miss")

                // Reopening the same DNG produces a new immutable source ID even
                // when dimensions match. Neither it nor resolution may alias.
                input.close()
                prepared=input.prepare(source,sourceHash,CaptureTiming(),false,{false})
                output=FilmOutput.buffer(prepared.width,prepared.height)
                state(render("source-invalidated"),"miss")
                for(full in listOf(true,false)) {
                    prepared=input.prepare(source,sourceHash,CaptureTiming(),full,{false})
                    output=FilmOutput.buffer(prepared.width,prepared.height)
                    val miss=render("resolution-$full-prime");state(miss,"miss")
                    val hit=render("resolution-$full-hit");state(hit,"hit");equal(miss,hit)
                    val edited=base.copy(print=2,magenta=3)
                    val cached=render("resolution-$full-edited",edited);state(cached,"hit")
                    val control=render("resolution-$full-control",edited,enabled=false)
                    state(control,"disabled");equal(cached,control)
                    check(control.getJSONObject("filmCache").getLong("bytes")==0L)
                }
                check(sourceHash==PairCaptureStore.hash(source))
                report.put("passed",true).put("sourceUnchanged",true)
            } finally {
                FilmEngine.testGpuFailure(false);input.close();FilmEngine.close()
                check(FilmEngine.liveGpuImages()==0L)
                report.put("liveGpuImagesAfterClose",FilmEngine.liveGpuImages())
                reportFile.writeText(report.toString(2))
            }
        }
        FilmWorker.execute {task.run()};task.get(600,TimeUnit.SECONDS)
    }
}
