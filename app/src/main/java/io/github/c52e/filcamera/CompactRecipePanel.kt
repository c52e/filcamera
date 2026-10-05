package io.github.c52e.filcamera

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private enum class RecipeItem(private val labelId: Int) {
    Stock(Texts.string.film_stock), Film(Texts.string.film_exposure), Development(Texts.string.development_contrast), Paper(Texts.string.print_paper),
    Print(Texts.string.print_exposure), Contrast(Texts.string.paper_contrast), Magenta(Texts.string.magenta_m), Yellow(Texts.string.yellow_y), Compensation(Texts.string.auto_compensation),
    Grain(Texts.string.grain), Halation(Texts.string.halation), FilmDiffusion(Texts.string.film_diffusion), PrintDiffusion(Texts.string.print_diffusion);
    val label: String get() = if (labelId == 0) "" else tr(labelId)
}
private val recipeGroups=listOf(
    listOf(RecipeItem.Stock,RecipeItem.Film,RecipeItem.Development),
    listOf(RecipeItem.Paper,RecipeItem.Print,RecipeItem.Contrast,RecipeItem.Magenta,RecipeItem.Yellow,RecipeItem.Compensation),
    listOf(RecipeItem.Grain,RecipeItem.Halation,RecipeItem.FilmDiffusion,RecipeItem.PrintDiffusion))
private fun RecipeItem.toggle(r: FilmRecipe): Boolean?=when(this) {
    RecipeItem.Compensation->r.compensate;RecipeItem.Grain->r.grain;RecipeItem.Halation->r.halation
    RecipeItem.FilmDiffusion->r.filmDiffusion;RecipeItem.PrintDiffusion->r.printDiffusion;else->null
}
private fun RecipeItem.changeToggle(r: FilmRecipe): FilmRecipe=when(this) {
    RecipeItem.Compensation->r.copy(compensate=!r.compensate)
    RecipeItem.Grain->r.copy(spatialPolicy=FilmRecipe.SPATIAL_POLICY,grain=!r.grain)
    RecipeItem.Halation->r.copy(spatialPolicy=FilmRecipe.SPATIAL_POLICY,halation=!r.halation)
    RecipeItem.FilmDiffusion->r.copy(spatialPolicy=FilmRecipe.SPATIAL_POLICY,filmDiffusion=!r.filmDiffusion)
    RecipeItem.PrintDiffusion->r.copy(spatialPolicy=FilmRecipe.SPATIAL_POLICY,printDiffusion=!r.printDiffusion)
    else->r
}
private data class RecipeDial(val value: Int,val min: Int,val max: Int,val default: Int,val format: (Int)->String,val change: (Int)->FilmRecipe)
private fun RecipeItem.dial(r: FilmRecipe): RecipeDial?=when(this) {
    RecipeItem.Film->RecipeDial(r.film,-20,20,0,{"%+.1f EV".format(it/10f)},{r.copy(film=it)})
    RecipeItem.Development->RecipeDial(r.development,10,30,20,{"%.2f".format(it/20f)},{r.copy(development=it)})
    RecipeItem.Print->RecipeDial(r.print,-30,30,0,{"%+.1f EV".format(it/10f)},{r.copy(print=it)})
    RecipeItem.Contrast->RecipeDial(r.contrast,10,40,22,{"%.2f".format(it/20f)},{r.copy(contrast=it)})
    RecipeItem.Magenta->RecipeDial(r.magenta,-30,30,0,{"$it CC"},{r.copy(magenta=it)})
    RecipeItem.Yellow->RecipeDial(r.yellow,-30,30,0,{"$it CC"},{r.copy(yellow=it)})
    else->null
}
private fun RecipeItem.value(r: FilmRecipe): String=when(this) {
    RecipeItem.Stock->FilmStocks.films.getValue(r.filmProfile).substringAfter(' ')
    RecipeItem.Paper->if(r.paperProfile==FilmStocks.AUTO_PAPER) tr(Texts.string.auto) else FilmStocks.papers.getValue(r.paperProfile).substringAfter(' ')
    else->toggle(r)?.let {if(it) tr(Texts.string.on) else tr(Texts.string.off)} ?: dial(r)!!.let {it.format(it.value)}
}

/** Fixed viewport. Large type / small windows switch to a parameter page, never a nested scroll form. */
@Composable internal fun CompactRecipePanel(recipe: FilmRecipe,changed: (FilmRecipe)->Unit,
    group: Int,select: (Int)->Unit,modifier: Modifier=Modifier,editing: Boolean=false,actions: @Composable ()->Unit={}) {
    var catalog by rememberSaveable {mutableStateOf(false)}
    var activeName by rememberSaveable(group) {mutableStateOf(if(group==1) RecipeItem.Print.name else recipeGroups[group].first().name)}
    var detail by rememberSaveable(group) {mutableStateOf(false)}
    var page by rememberSaveable(group) {mutableIntStateOf(0)}
    val active=RecipeItem.valueOf(activeName)
    BackHandler(catalog || detail) {if(catalog) catalog=false else detail=false}
    Column(modifier.semantics {contentDescription=tr(Texts.string.compact_color_controls)}) {
        if(catalog) {
            val paper=active==RecipeItem.Paper
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                CameraIconButton(CameraIcons.Back,tr(Texts.string.back_to_controls)) {catalog=false}
                Text(if(paper) tr(Texts.string.all_papers) else tr(Texts.string.all_films),Modifier.weight(1f))
                actions()
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                StockOptions(if(paper) recipe.paperProfile else recipe.filmProfile,if(paper) FilmStocks.paperChoices(recipe.filmProfile) else FilmStocks.films) {
                    changed(if(paper) recipe.copy(paperProfile=it) else recipe.copy(filmProfile=it));catalog=false
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                if(detail) {
                    CameraIconButton(CameraIcons.Back,tr(Texts.string.back_to_controls)) {detail=false}
                    Text(active.label,Modifier.weight(1f),fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                } else Box(Modifier.weight(1f)) {RecipeTabs(group) {detail=false;select(it)}}
                CameraIconButton(CameraIcons.Reset,tr(Texts.string.reset_film_print_and_effects)) {changed(FilmRecipe())}
                actions()
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal=8.dp)) {
                val scale=LocalDensity.current.fontScale
                val columns=if(group==2 || scale>1.2f || maxWidth<270.dp) 2 else 3
                val tileHeight=maxOf(50f,36*scale).dp
                val naturalRows=(recipeGroups[group].size+columns-1)/columns
                val requiredHeight=(tileHeight+4.dp)*naturalRows+(if(group==2) 24.dp else 72.dp)
                val separate=maxHeight<requiredHeight || scale>1.2f || maxWidth<200.dp
                val rows=if(separate) ((maxHeight-48.dp)/(tileHeight+4.dp)).toInt().coerceAtLeast(1) else naturalRows
                val pages=recipeGroups[group].chunked(rows*columns)
                val currentPage=page.coerceIn(pages.indices)
                if(detail) {
                    key(active) {RecipeAdjustment(active,recipe,changed,{catalog=true},Modifier.fillMaxWidth(),showTitle=false)}
                } else Column(Modifier.fillMaxSize()) {
                    pages[currentPage].chunked(columns).forEach {row->
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                            row.forEach {item->
                                val enabled=item.toggle(recipe)
                                TextButton(onClick={
                                    if(enabled!=null) changed(item.changeToggle(recipe))
                                    else {activeName=item.name;detail=separate}
                                },contentPadding=PaddingValues(horizontal=4.dp,vertical=2.dp),
                                    colors=ButtonDefaults.textButtonColors(containerColor=if(enabled==true || (enabled==null && item==active)) Accent.copy(alpha=.15f) else MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor=if(enabled==true || (enabled==null && item==active)) Accent else MaterialTheme.colorScheme.onSurface),
                                    modifier=Modifier.weight(1f).height(tileHeight).semantics {
                                        contentDescription=tr(Texts.string.control_value, item.label);stateDescription=item.value(recipe)
                                        if(enabled!=null) toggleableState=if(enabled) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off
                                        else selected=item==active
                                    }) {
                                    Column(horizontalAlignment=Alignment.CenterHorizontally) {
                                        Text(item.label,fontSize=12.sp,lineHeight=15.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                                        Text(item.value(recipe),fontSize=13.sp,lineHeight=16.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            repeat(columns-row.size) {Spacer(Modifier.weight(1f))}
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    if(pages.size>1) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        TextButton(onClick={page=currentPage-1},enabled=currentPage>0) {Text(tr(Texts.string.previous_page))}
                        TextButton(onClick={page=currentPage+1},enabled=currentPage<pages.lastIndex) {Text(tr(Texts.string.next_page))}
                    }
                    if(group==2) Text(if(editing) tr(Texts.string.effects_appear_in_the_developed_preview) else tr(Texts.string.final_photo_only),fontSize=12.sp,color=Muted)
                    else if(!separate) {
                        Spacer(Modifier.weight(1f))
                        key(active) {RecipeAdjustment(active,recipe,changed,{catalog=true},Modifier.fillMaxWidth())}
                    }
                }
            }
        }
    }
}

@Composable private fun RecipeAdjustment(item: RecipeItem,recipe: FilmRecipe,changed: (FilmRecipe)->Unit,
    catalog: ()->Unit,modifier: Modifier=Modifier,showTitle: Boolean=true) {
    if(item==RecipeItem.Stock || item==RecipeItem.Paper) {
        val paper=item==RecipeItem.Paper
        Box(modifier) {StockPicker(if(paper) tr(Texts.string.paper) else tr(Texts.string.film),if(paper) recipe.paperProfile else recipe.filmProfile,
            if(paper) FilmStocks.paperChoices(recipe.filmProfile) else FilmStocks.films,compact=true,onCatalog=catalog) {
            changed(if(paper) recipe.copy(paperProfile=it) else recipe.copy(filmProfile=it))
        }}
    } else item.dial(recipe)?.let {dial->
        var value by remember(item,dial.value) {mutableFloatStateOf(dial.value.toFloat())}
        Column(modifier) {
            Row(Modifier.fillMaxWidth().heightIn(min=24.dp),verticalAlignment=Alignment.CenterVertically) {
                if(showTitle) Text(item.label,Modifier.weight(1f),fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                else Spacer(Modifier.weight(1f))
                Text(dial.format(value.roundToInt()),color=Accent,fontSize=13.sp)
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Slider(value,{value=it},valueRange=dial.min.toFloat()..dial.max.toFloat(),steps=dial.max-dial.min-1,
                    onValueChangeFinished={changed(dial.change(value.roundToInt()))},modifier=Modifier.weight(1f).height(48.dp).padding(horizontal=12.dp).semantics {contentDescription=item.label})
                CameraIconButton(CameraIcons.Reset,tr(Texts.string.reset_value, item.label)) {changed(dial.change(dial.default))}
            }
        }
    }
}
