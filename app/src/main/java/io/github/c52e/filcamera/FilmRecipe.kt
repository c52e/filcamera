package io.github.c52e.filcamera

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

import android.content.Context
import org.json.JSONObject
import kotlin.math.roundToInt

/** Integer ticks are the canonical persisted/cache representation. */
data class FilmRecipe(
    val film: Int=0, val development: Int=20, val print: Int=0,
    val compensate: Boolean=false, val contrast: Int=22, val magenta: Int=0, val yellow: Int=0,
    val filmProfile: String="kodak_portra_400", val paperProfile: String=FilmStocks.AUTO_PAPER,
    val filmDiffusionStrength: Int=DEFAULT_DIFFUSION_STRENGTH, val printDiffusionStrength: Int=DEFAULT_DIFFUSION_STRENGTH,
    val grainStrength: Int=DEFAULT_EFFECT_STRENGTH, val halationStrength: Int=DEFAULT_EFFECT_STRENGTH,
    val spatialPolicy: String=SPATIAL_POLICY
) {
    init {
        require(spatialPolicy==SPATIAL_POLICY || spatialPolicy==LEGACY_POLICY) { tr(Texts.string.unknown_spatial_policy_cannot_restore_accurately) }
        require(filmDiffusionStrength in 0..MAX_STRENGTH && printDiffusionStrength in 0..MAX_STRENGTH &&
            grainStrength in 0..MAX_STRENGTH && halationStrength in 0..MAX_STRENGTH) { tr(Texts.string.recipe_parameters_are_out_of_range) }
        require(dirSpatial || (filmDiffusionStrength==0 && printDiffusionStrength==0 && !grain && !halation))
        require(filmProfile in FilmStocks.films && (paperProfile in FilmStocks.papers || paperProfile==FilmStocks.AUTO_PAPER)) { tr(Texts.string.unsupported_film_or_paper) }
        require(film in -20..20 && development in 10..30 && print in -30..30 &&
            contrast in 10..40 && magenta in -30..30 && yellow in -30..30) { tr(Texts.string.recipe_parameters_are_out_of_range) }
    }
    val filmEv get()=film/10f
    val gamma get()=development/20f
    val printEv get()=print/10f
    val paperContrast get()=contrast/20f
    private val grain get()=grainStrength>0
    private val halation get()=halationStrength>0
    val dirSpatial get()=spatialPolicy==SPATIAL_POLICY
    val resolvedPaperProfile get()=if(paperProfile==FilmStocks.AUTO_PAPER) FilmStocks.targetPapers.getValue(filmProfile) else paperProfile
    val colorCanonical get()="color-v1|$filmProfile|$resolvedPaperProfile|$film|$development|$print|${if(compensate) 1 else 0}|$contrast|$magenta|$yellow"
    val colorHash get()=PreviewLut.digest(colorCanonical.toByteArray(Charsets.UTF_8))
    val canonical get()="v4|$colorCanonical|$spatialPolicy|$filmDiffusionStrength|$printDiffusionStrength|$grainStrength|$halationStrength"+(if(paperProfile==FilmStocks.AUTO_PAPER) "|paper-auto-v1" else "")
    val hash get()=PreviewLut.digest(canonical.toByteArray(Charsets.UTF_8))
    fun json()=JSONObject().put("version",4).put("filmProfile",filmProfile)
        .put("resolvedPaperProfile",resolvedPaperProfile)
        .put("paperProfile",paperProfile).put("film",film).put("development",development)
        .put("print",print).put("compensate",compensate).put("contrast",contrast)
        .put("magenta",magenta).put("yellow",yellow)
        .put("spatialPolicy",spatialPolicy).put("filmDiffusionStrength",filmDiffusionStrength)
        .put("printDiffusionStrength",printDiffusionStrength).put("grainStrength",grainStrength).put("halationStrength",halationStrength)
    fun withoutSpatial()=copy(filmDiffusionStrength=0,printDiffusionStrength=0,grainStrength=0,halationStrength=0,spatialPolicy=LEGACY_POLICY)

    private fun diffusionSpatial(strength: Int)=JSONObject().put("active",strength>0).put("family","black_pro_mist")
        .put("strength",strength.toDouble()/STRENGTH_UNIT).put("spatialScale",1).put("warmth",0)
        .put("coreIntensity",1).put("coreSize",1).put("haloIntensity",1).put("haloSize",1).put("bloomIntensity",1).put("bloomSize",1)
    fun resolvedSpatial(scanner: Boolean=true)=JSONObject().put("policy",spatialPolicy).put("dirActive",dirSpatial)
        .put("sampling","broad-kernel-multirate-v1")
        .put("renderDefaults","spektrafilm-3bb2c2d-grain-scanner-v1")
        .put("frameLongEdgeMm",36)
        .put("dir",JSONObject().put("sizeUm",if(dirSpatial) 20 else 0).put("tailUm",200).put("tailWeight",.03)
            .put("amount",1).put("sameLayer",1).put("interLayer",1)
            .put("sameLayerGamma",org.json.JSONArray(listOf(.341,.324,.273)))
            .put("rToGB",org.json.JSONArray(listOf(.355,.305))).put("gToRB",org.json.JSONArray(listOf(.154,.358)))
            .put("bToRG",org.json.JSONArray(listOf(.171,.225))))
        .put("filmDiffusion",diffusionSpatial(filmDiffusionStrength))
        .put("printDiffusion",diffusionSpatial(printDiffusionStrength))
        .put("halation",JSONObject().put("active",halation).put("scatterAmount",minOf(halationStrength,STRENGTH_UNIT).toDouble()/STRENGTH_UNIT)
            .put("scatterScale",1).put("reflectionAmount",halationStrength.toDouble()/STRENGTH_UNIT)
            .put("reflectionScale",1).put("boostEv",0).put("bounces",3).put("bounceDecay",.5).put("renormalize",true)
            .put("scatterCoreUm",org.json.JSONArray(listOf(2.2,2.0,1.6))).put("scatterTailUm",org.json.JSONArray(listOf(9.3,9.7,9.1)))
            .put("scatterTailWeight",org.json.JSONArray(listOf(.78,.65,.67)))
            .put("reflectionStrength",org.json.JSONArray(listOf(.05,.015,0))).put("firstSigmaUm",org.json.JSONArray(listOf(65,65,65))))
        .put("grain",JSONObject().put("active",grain).put("strength",grainStrength.toDouble()/STRENGTH_UNIT)
            .put("model","profile-density-sublayers-poisson-binomial")
            .put("seedAlgorithm","pcg32-box-muller-normal-v1").put("particleAreaUm2",.2)
            .put("particleScale",org.json.JSONArray(listOf(1.6,1.6,3.2)))
            .put("sublayerScale",org.json.JSONArray(listOf(2,1,.5)))
            .put("densityMin",org.json.JSONArray(listOf(.03,.03,.03)))
            .put("uniformity",org.json.JSONArray(listOf(.97,.99,.97)))
            .put("blurPixels",.65).put("dyeCloudScale",1).put("microStructure",org.json.JSONArray(listOf(.2,30))))
        .put("scanner",JSONObject().put("active",scanner).put("unsharpSigmaPixels",if(scanner) .7 else 0.0).put("unsharpAmount",if(scanner) .7 else 0.0).put("reference","spektrafilm-3bb2c2d"))
    fun save(context: Context) { context.getSharedPreferences("film-recipe",0).edit().putString("recipe",json().toString()).apply() }
    companion object {
        const val SPATIAL_POLICY="darktable-c510c403-spatial-v1"
        const val LEGACY_POLICY="legacy-spatial-off"
        const val STRENGTH_UNIT=1000
        const val MAX_STRENGTH=2*STRENGTH_UNIT
        const val DEFAULT_EFFECT_STRENGTH=STRENGTH_UNIT
        const val DEFAULT_DIFFUSION_STRENGTH=STRENGTH_UNIT/2
        fun legacy()=FilmRecipe(paperProfile="kodak_portra_endura",compensate=true,contrast=20).withoutSpatial()
        fun parse(j: JSONObject): FilmRecipe {
            val version=j.getInt("version")
            require(version in 1..4) { tr(Texts.string.unknown_recipe_version_cannot_restore_accurately) }
            if(j.getString("paperProfile")==FilmStocks.AUTO_PAPER) {
                require(version>=3 && j.getString("resolvedPaperProfile")==FilmStocks.targetPapers[j.getString("filmProfile")]) { tr(Texts.string.auto_paper_mapping_changed_cannot_restore_accurately) }
            }
            fun tick(key: String): Int { val n=j.getDouble(key);require(n.isFinite() && n==n.toInt().toDouble());return n.toInt() }
            fun strength(effect: String, legacyOn: Int): Int=when {
                version==4->tick(effect+"Strength")
                version>=2 && j.getBoolean(effect)->legacyOn
                else->0
            }
            return FilmRecipe(tick("film"),tick("development"),tick("print"),j.getBoolean("compensate"),tick("contrast"),tick("magenta"),tick("yellow"),j.getString("filmProfile"),j.getString("paperProfile"),
                strength("filmDiffusion",DEFAULT_DIFFUSION_STRENGTH),strength("printDiffusion",DEFAULT_DIFFUSION_STRENGTH),
                strength("grain",DEFAULT_EFFECT_STRENGTH),strength("halation",DEFAULT_EFFECT_STRENGTH),
                if(version>=2) j.getString("spatialPolicy") else LEGACY_POLICY)
        }
        fun restore(j: JSONObject): FilmRecipe {
            j.optJSONObject("recipe")?.let { return parse(it) }
            j.optJSONObject("render")?.let { return restore(it) }
            j.optJSONObject("capture")?.let { return restore(it) }
            val ev=j.optDouble("filmEv",0.0);require(ev.isFinite() && ev in -2.0..2.0)
            return legacy().copy(film=(ev*10).roundToInt(),compensate=j.optBoolean("printExposureCompensation",true))
        }
        fun load(context: Context): FilmRecipe {
            val prefs=context.getSharedPreferences("film-recipe",0)
            val saved=prefs.getString("recipe",null)?.let { parse(JSONObject(it)) }
            if(!prefs.getBoolean("defaults-v2-applied",false)) {
                val upgraded=FilmRecipe(filmProfile=saved?.filmProfile ?: "kodak_portra_400",paperProfile=saved?.paperProfile ?: FilmStocks.AUTO_PAPER)
                check(prefs.edit().putString("recipe",upgraded.json().toString()).putBoolean("defaults-v2-applied",true).commit()) { tr(Texts.string.could_not_save_recipe_upgrade) }
                return upgraded
            }
            return saved ?: FilmRecipe()
        }
    }
}
