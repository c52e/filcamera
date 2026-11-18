package io.github.c52e.filcamera

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

import android.content.Context
import org.json.JSONObject
import kotlin.math.roundToInt

/** Integer ticks are the canonical persisted/cache representation. */
data class FilmRecipe(
    val film: Int=0, val development: Int=20, val print: Int=0,
    val compensate: Boolean=false, val contrast: Int=DEFAULT_PRINT_CONTRAST, val magenta: Int=0, val yellow: Int=0,
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
    val resolvedPaperProfile get()=FilmStocks.resolvedPaper(filmProfile,paperProfile)
    val directScan get()=resolvedPaperProfile==FilmStocks.DIRECT_SCAN
    val colorCanonical get()=if(directScan) "color-v1|$filmProfile|$resolvedPaperProfile|$film|$development"
        else "color-v1|$filmProfile|$resolvedPaperProfile|$film|$development|$print|${if(compensate) 1 else 0}|$contrast|$magenta|$yellow"
    val colorHash get()=PreviewLut.digest(colorCanonical.toByteArray(Charsets.UTF_8))
    val canonical get()="v4|$colorCanonical|$spatialPolicy|$filmDiffusionStrength|$printDiffusionStrength|$grainStrength|$halationStrength"+
        when {
            directScan -> "|$paperProfile|$print|${if(compensate) 1 else 0}|$contrast|$magenta|$yellow"
            paperProfile==FilmStocks.AUTO_PAPER -> "|paper-auto-v1"
            else -> ""
        }
    val hash get()=PreviewLut.digest(canonical.toByteArray(Charsets.UTF_8))
    fun json()=JSONObject().put("version",4).put("filmProfile",filmProfile)
        .put("resolvedPaperProfile",resolvedPaperProfile)
        .put("paperProfile",paperProfile).put("film",film).put("development",development)
        .put("print",print).put("compensate",compensate).put("contrast",contrast)
        .put("magenta",magenta).put("yellow",yellow)
        .put("spatialPolicy",spatialPolicy).put("filmDiffusionStrength",filmDiffusionStrength)
        .put("printDiffusionStrength",printDiffusionStrength).put("grainStrength",grainStrength).put("halationStrength",halationStrength)
    fun withoutSpatial()=copy(filmDiffusionStrength=0,printDiffusionStrength=0,grainStrength=0,halationStrength=0,spatialPolicy=LEGACY_POLICY)

    fun save(context: Context) { context.getSharedPreferences("film-recipe",0).edit().putString("recipe",json().toString()).apply() }
    companion object {
        const val SPATIAL_POLICY="darktable-c510c403-spatial-v1"
        const val LEGACY_POLICY="legacy-spatial-off"
        const val STRENGTH_UNIT=1000
        const val MAX_STRENGTH=2*STRENGTH_UNIT
        const val DEFAULT_EFFECT_STRENGTH=STRENGTH_UNIT
        const val DEFAULT_DIFFUSION_STRENGTH=STRENGTH_UNIT/2
        const val DEFAULT_PRINT_CONTRAST=20
        fun legacy()=FilmRecipe(paperProfile="kodak_portra_endura",compensate=true,contrast=20).withoutSpatial()
        fun parse(j: JSONObject): FilmRecipe {
            val version=j.getInt("version")
            require(version in 1..4) { tr(Texts.string.unknown_recipe_version_cannot_restore_accurately) }
            val storedFilm=j.getString("filmProfile")
            val filmProfile=if(storedFilm=="kodak_2302") "kodak_doublex" else storedFilm
            val paperProfile=j.getString("paperProfile")
            if(paperProfile==FilmStocks.AUTO_PAPER || filmProfile in FilmStocks.reversalFilms) {
                require(version>=3 && j.getString("resolvedPaperProfile")==FilmStocks.resolvedPaper(filmProfile,paperProfile)) { tr(Texts.string.auto_paper_mapping_changed_cannot_restore_accurately) }
            }
            fun tick(key: String): Int { val n=j.getDouble(key);require(n.isFinite() && n==n.toInt().toDouble());return n.toInt() }
            fun strength(effect: String, legacyOn: Int): Int=when {
                version==4->tick(effect+"Strength")
                version>=2 && j.getBoolean(effect)->legacyOn
                else->0
            }
            return FilmRecipe(tick("film"),tick("development"),tick("print"),j.getBoolean("compensate"),tick("contrast"),tick("magenta"),tick("yellow"),filmProfile,paperProfile,
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
            val stored=prefs.getString("recipe",null)?.let(::JSONObject)
            val saved=stored?.let(::parse)
            val filmReplaced=stored?.getString("filmProfile")!=saved?.filmProfile
            if(filmReplaced || !prefs.getBoolean("defaults-v2-applied",false)) {
                val upgraded=if(filmReplaced) checkNotNull(saved) else
                    FilmRecipe(filmProfile=saved?.filmProfile ?: "kodak_portra_400",paperProfile=saved?.paperProfile ?: FilmStocks.AUTO_PAPER)
                check(prefs.edit().putString("recipe",upgraded.json().toString()).putBoolean("defaults-v2-applied",true).commit()) { tr(Texts.string.could_not_save_recipe_upgrade) }
                return upgraded
            }
            return saved ?: FilmRecipe()
        }
    }
}
