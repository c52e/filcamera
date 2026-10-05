package io.github.c52e.filcamera

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

/** GPL film-engine bridge. Call render/close on one owner worker; cancel may run anywhere. */
object FilmEngine {
    init { System.loadLibrary("filcamera_film") }
    private external fun renderNative(assets: String, input: String, width: Int, height: Int, ev: Float, request: Long, output: String, compensatePrint: Boolean, gamma: Float, printEv: Float, contrast: Float, magenta: Float, yellow: Float, filmProfile: String, paperProfile: String,dirSpatial: Boolean,filmDiffusion: Boolean,printDiffusion: Boolean,grain: Boolean,halation: Boolean,seed: Long, rgba: java.nio.ByteBuffer?, pixels: java.nio.ByteBuffer?,gpuImage: Long): String
    external fun cancel(request: Long)
    external fun close()
    external fun renderStage(request: Long): String
    external fun memorySnapshot(): String
    external fun testGpuFailure(enabled: Boolean)
    external fun testResidentEngine(enabled: Boolean)
    external fun testOptimizations(mask: Int)
    external fun testStorage(mode: Int)
    external fun testStorageCapabilities(): Int
    external fun testDensityGuards(): Int
    external fun testRelaxed(enabled: Boolean)
    external fun testWorkgroup(size: Int,rawX: Int,rawY: Int)
    external fun liveGpuImages(): Long
    private external fun bakeNative(assets: String, requestId: Long, outputPath: String, ev: Float, compensatePrint: Boolean, gamma: Float, printEv: Float, contrast: Float, magenta: Float, yellow: Float, filmProfile: String, paperProfile: String): String
    fun render(assets: String,input: String,width: Int,height: Int,ev: Float,request: Long,output: String,compensatePrint: Boolean=true): String {
        require(ev.isFinite() && ev in -2f..2f)
        return render(assets,input,width,height,FilmRecipe.legacy().copy(film=kotlin.math.round(ev*10).toInt(),compensate=compensatePrint),request,output)
    }
    fun render(assets: String,input: String,width: Int,height: Int,recipe: FilmRecipe,request: Long,output: String,seed: Long=seedFor(input), rgba: java.nio.ByteBuffer?=null, pixels: java.nio.ByteBuffer?=null,gpuImage: Long=0): String =
        annotate(renderNative(assets,input,width,height,recipe.filmEv,request,output,recipe.compensate,recipe.gamma,recipe.printEv,recipe.paperContrast,recipe.magenta.toFloat(),recipe.yellow.toFloat(),recipe.filmProfile,recipe.resolvedPaperProfile,recipe.dirSpatial,recipe.filmDiffusion,recipe.printDiffusion,recipe.grain,recipe.halation,seed,rgba,pixels,gpuImage),recipe,seed)
    fun bakePreviewLut(assets: String,requestId: Long,outputPath: String,recipe: FilmRecipe=FilmRecipe()): String =
        annotate(bakeNative(assets,requestId,outputPath,recipe.filmEv,recipe.compensate,recipe.gamma,recipe.printEv,recipe.paperContrast,recipe.magenta.toFloat(),recipe.yellow.toFloat(),recipe.filmProfile,recipe.resolvedPaperProfile),recipe.withoutSpatial(),scanner=false)
    private fun annotate(result: String,recipe: FilmRecipe,seed: Long=0,scanner: Boolean=true): String {
        val parser=org.json.JSONTokener(result);val record=parser.nextValue() as org.json.JSONObject
        check(parser.nextClean()=='\u0000') {"Native render returned trailing diagnostic data"}
        val sampling=record.optJSONObject("gpuTransfers")?.let {
            if(it.optInt("optimizationMask") and 512 != 0) "broad-kernel-multirate-v1" else "full-resolution"
        } ?: "not-applicable"
        record.put("diffusionPsf",if(record.optJSONObject("gpuTransfers")?.optInt("optimizationMask")?.and(1024)==1024)
            "radial-table-1over16px-exact-core4-v1" else "direct-exponential")
        record.put("diffusionConvolution",if(record.optJSONObject("gpuTransfers")?.optInt("optimizationMask")?.and(2048)==2048)
            "adaptive-half-fft-core4-v1" else "full-resolution-fft")
        return record.put("recipe",recipe.json()).put("recipeHash",recipe.hash).put("colorRecipeHash",recipe.colorHash).put("grainSeed",seed).put("spatialPolicy",recipe.spatialPolicy).put("resolvedSpatial",recipe.resolvedSpatial(scanner).put("sampling",sampling)).put("processPssKb",android.os.Debug.getPss()).put("nativeMemory",org.json.JSONObject(memorySnapshot())).toString()
    }
    fun seedFor(input: String)=PairCaptureStore.hash(java.io.File(input)).take(8).toLong(16)
    fun recordedSeed(record: org.json.JSONObject): Long? {
        if(record.has("grainSeed")) {
            val value=record.getDouble("grainSeed")
            require(value.isFinite() && value in 0.0..4294967295.0 && value==value.toLong().toDouble()) { tr(Texts.string.invalid_photo_grain_seed) }
            return value.toLong()
        }
        for(key in listOf("render","captureRecipe","provenance","capture")) record.optJSONObject(key)?.let { nested -> recordedSeed(nested)?.let { return it } }
        return null
    }
    external fun cancelBake(requestId: Long)
    external fun activeBakeRequest(): Long
}
