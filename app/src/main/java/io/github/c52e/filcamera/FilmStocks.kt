package io.github.c52e.filcamera

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

internal data class StockOption(
    val name: String,
    val title: String = name.substringBefore(' '),
    val subtitle: String = name.substringAfter(' ')
)

internal data class StockGroup(val title: Int, val choices: Map<String, StockOption>)

private fun stockGroup(title: Int, names: Map<String, String>) =
    StockGroup(title, names.mapValues { (_, name) -> StockOption(name) })

/** Selectable film profiles and their default print materials. */
object FilmStocks {
    const val AUTO_PAPER="auto"
    const val DIRECT_SCAN="direct-scan"
    val reversalFilms = setOf("fujifilm_provia_100f","fujifilm_velvia_100","kodak_ektachrome_100","kodak_kodachrome_64","kodak_trix")
    // Defaults from the color profiles, plus 2302 for Double-X.
    val targetPapers=mapOf(
        "kodak_portra_400" to "kodak_portra_endura",
        "kodak_portra_160" to "kodak_portra_endura",
        "kodak_portra_800" to "kodak_portra_endura",
        "kodak_portra_800_push1" to "kodak_portra_endura",
        "kodak_portra_800_push2" to "kodak_portra_endura",
        "kodak_ektar_100" to "kodak_portra_endura",
        "kodak_gold_200" to "kodak_portra_endura",
        "kodak_ultramax_400" to "kodak_portra_endura",
        "fujifilm_pro_400h" to "fujifilm_crystal_archive_typeii",
        "fujifilm_c200" to "fujifilm_crystal_archive_typeii",
        "fujifilm_xtra_400" to "fujifilm_crystal_archive_typeii",
        "kodak_vision3_50d" to "kodak_2383",
        "kodak_vision3_200t" to "kodak_2383",
        "kodak_vision3_250d" to "kodak_2383",
        "kodak_vision3_500t" to "kodak_2383",
        "kodak_verita_200d" to "kodak_2383",
        "kodak_doublex" to "kodak_2302"
    )
    internal fun paperGroups(film: String): List<StockGroup> {
        val defaultPaper = papers.getValue(targetPapers.getValue(film)).name
        val noPaper = tr(Texts.string.paper_none)
        val scan = tr(Texts.string.direct_film_scan)
        return listOf(StockGroup(Texts.string.default_pairing, mapOf(
            AUTO_PAPER to StockOption(tr(Texts.string.auto_value, defaultPaper), tr(Texts.string.auto), defaultPaper),
            DIRECT_SCAN to StockOption("$noPaper · $scan", noPaper, scan)
        ))) + printGroups
    }

    internal fun paperChoices(film: String) = paperGroups(film).combinedChoices()
    fun resolvedPaper(film: String, paper: String): String = when {
        film in reversalFilms -> DIRECT_SCAN
        paper == AUTO_PAPER -> targetPapers.getValue(film)
        else -> paper
    }
    internal val filmGroups = listOf(
        stockGroup(Texts.string.still_color_negatives, linkedMapOf(
            "kodak_portra_160" to "Kodak Portra 160",
            "kodak_portra_400" to "Kodak Portra 400",
            "kodak_portra_800" to "Kodak Portra 800",
            "kodak_portra_800_push1" to "Kodak Portra 800 (Push 1)",
            "kodak_portra_800_push2" to "Kodak Portra 800 (Push 2)",
            "kodak_ektar_100" to "Kodak Ektar 100",
            "kodak_gold_200" to "Kodak Gold 200",
            "kodak_ultramax_400" to "Kodak UltraMax 400",
            "fujifilm_c200" to "Fujifilm C200",
            "fujifilm_pro_400h" to "Fujifilm Pro 400H",
            "fujifilm_xtra_400" to "Fujifilm Superia X-TRA 400"
        )),
        stockGroup(Texts.string.color_cine_negatives, linkedMapOf(
            "kodak_vision3_50d" to "Kodak Vision3 50D",
            "kodak_vision3_200t" to "Kodak Vision3 200T",
            "kodak_vision3_250d" to "Kodak Vision3 250D",
            "kodak_vision3_500t" to "Kodak Vision3 500T",
            "kodak_verita_200d" to "Kodak Verita 200D"
        )),
        stockGroup(Texts.string.color_reversal_films, linkedMapOf(
            "kodak_ektachrome_100" to "Kodak Ektachrome E100",
            "kodak_kodachrome_64" to "Kodak Kodachrome 64",
            "fujifilm_provia_100f" to "Fujifilm Provia 100F",
            "fujifilm_velvia_100" to "Fujifilm Velvia 100"
        )),
        stockGroup(Texts.string.monochrome_films, linkedMapOf(
            "kodak_doublex" to "Kodak Double-X 5222",
            "kodak_trix" to "Kodak Tri-X Reversal 7266"
        ))
    )
    private val printGroups = listOf(
        stockGroup(Texts.string.color_print_papers, linkedMapOf(
            "kodak_portra_endura" to "Kodak Portra Endura",
            "kodak_supra_endura" to "Kodak Supra Endura",
            "kodak_ultra_endura" to "Kodak Ultra Endura",
            "kodak_endura_premier" to "Kodak Endura Premier",
            "kodak_ektacolor_edge" to "Kodak Ektacolor Edge",
            "fujifilm_crystal_archive_typeii" to "Fujifilm Crystal Archive Type II"
        )),
        stockGroup(Texts.string.color_cine_prints, linkedMapOf(
            "kodak_2383" to "Kodak Vision Print Film 2383",
            "kodak_2393" to "Kodak Vision Premier Print Film 2393"
        )),
        stockGroup(Texts.string.monochrome_prints, mapOf(
            "kodak_2302" to "Kodak Print Film 2302"
        ))
    )

    internal val films = filmGroups.combinedChoices()
    internal val papers = printGroups.combinedChoices()

    private fun List<StockGroup>.combinedChoices(): Map<String, StockOption> = buildMap {
        this@combinedChoices.forEach { putAll(it.choices) }
    }
}
