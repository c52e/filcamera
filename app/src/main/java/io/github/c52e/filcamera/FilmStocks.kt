package io.github.c52e.filcamera

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

/** Qualified D55 color negatives and D50-viewed RA-4 papers only. */
object FilmStocks {
    const val AUTO_PAPER="auto"
    // Exact info.target_print values from the pinned, shipped profile data.
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
        "fujifilm_xtra_400" to "fujifilm_crystal_archive_typeii"
    )
    fun paperChoices(film: String)=linkedMapOf(AUTO_PAPER to tr(Texts.string.auto_value, papers.getValue(targetPapers.getValue(film)))).apply {putAll(papers)}
    val films = linkedMapOf(
        "kodak_portra_400" to "Kodak Portra 400",
        "kodak_portra_160" to "Kodak Portra 160",
        "kodak_portra_800" to "Kodak Portra 800",
        "kodak_portra_800_push1" to "Kodak Portra 800 (Push 1)",
        "kodak_portra_800_push2" to "Kodak Portra 800 (Push 2)",
        "kodak_ektar_100" to "Kodak Ektar 100",
        "kodak_gold_200" to "Kodak Gold 200",
        "kodak_ultramax_400" to "Kodak UltraMax 400",
        "fujifilm_pro_400h" to "Fujifilm Pro 400H",
        "fujifilm_c200" to "Fujifilm C200",
        "fujifilm_xtra_400" to "Fujifilm Superia X-TRA 400"
    )
    val papers = linkedMapOf(
        "kodak_portra_endura" to "Kodak Portra Endura",
        "kodak_supra_endura" to "Kodak Supra Endura",
        "kodak_ultra_endura" to "Kodak Ultra Endura",
        "kodak_endura_premier" to "Kodak Endura Premier",
        "kodak_ektacolor_edge" to "Kodak Ektacolor Edge",
        "fujifilm_crystal_archive_typeii" to "Fujifilm Crystal Archive Type II"
    )
}
