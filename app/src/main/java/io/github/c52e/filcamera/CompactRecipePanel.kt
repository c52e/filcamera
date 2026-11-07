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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DecimalFormat
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
private fun RecipeItem.toggle(r: FilmRecipe): Boolean?=if(this==RecipeItem.Compensation) r.compensate else null

private data class RecipeDial(val value: Int,val min: Int,val max: Int,val default: Int,
    val format: (Int)->String,val change: (Int)->FilmRecipe,val continuous: Boolean=false)

private fun FilmRecipe.effectDial(value: Int, default: Int, suffix: String="",change: FilmRecipe.(Int)->FilmRecipe)=
    RecipeDial(value,0,FilmRecipe.MAX_STRENGTH,default,{ticks->
        if(ticks==0) tr(Texts.string.off)
        else DecimalFormat("0.###").format(ticks.toDouble()/FilmRecipe.STRENGTH_UNIT)+suffix
    },{ticks->
        val base=if(ticks>0) copy(spatialPolicy=FilmRecipe.SPATIAL_POLICY) else this
        base.change(ticks)
    },continuous=true)

private fun RecipeItem.dial(r: FilmRecipe): RecipeDial?=when(this) {
    RecipeItem.Film->RecipeDial(r.film,-20,20,0,{"%+.1f EV".format(it/10f)},{r.copy(film=it)})
    RecipeItem.Development->RecipeDial(r.development,10,30,20,{"%.2f".format(it/20f)},{r.copy(development=it)})
    RecipeItem.Print->RecipeDial(r.print,-30,30,0,{"%+.1f EV".format(it/10f)},{r.copy(print=it)})
    RecipeItem.Contrast->RecipeDial(r.contrast,10,40,22,{"%.2f".format(it/20f)},{r.copy(contrast=it)})
    RecipeItem.Magenta->RecipeDial(r.magenta,-30,30,0,{"$it CC"},{r.copy(magenta=it)})
    RecipeItem.Yellow->RecipeDial(r.yellow,-30,30,0,{"$it CC"},{r.copy(yellow=it)})
    RecipeItem.Grain->r.effectDial(r.grainStrength,FilmRecipe.DEFAULT_EFFECT_STRENGTH,"×") {copy(grainStrength=it)}
    RecipeItem.Halation->r.effectDial(r.halationStrength,FilmRecipe.DEFAULT_EFFECT_STRENGTH,"×") {copy(halationStrength=it)}
    RecipeItem.FilmDiffusion->r.effectDial(r.filmDiffusionStrength,FilmRecipe.DEFAULT_DIFFUSION_STRENGTH) {copy(filmDiffusionStrength=it)}
    RecipeItem.PrintDiffusion->r.effectDial(r.printDiffusionStrength,FilmRecipe.DEFAULT_DIFFUSION_STRENGTH) {copy(printDiffusionStrength=it)}
    else->null
}
private fun RecipeItem.value(r: FilmRecipe): String=when(this) {
    RecipeItem.Stock->FilmStocks.films.getValue(r.filmProfile).substringAfter(' ')
    RecipeItem.Paper->if(r.paperProfile==FilmStocks.AUTO_PAPER) tr(Texts.string.auto) else FilmStocks.papers.getValue(r.paperProfile).substringAfter(' ')
    else->toggle(r)?.let {if(it) tr(Texts.string.on) else tr(Texts.string.off)} ?: dial(r)!!.let {it.format(it.value)}
}

/** Fixed viewport. Camera effects keep their adjustment on the same page as the options. */
@Composable internal fun CompactRecipePanel(recipe: FilmRecipe,changed: (FilmRecipe)->Unit,
    group: Int,select: (Int)->Unit,modifier: Modifier=Modifier,editing: Boolean=false,actions: @Composable ()->Unit={}) {
    var catalog by rememberSaveable {mutableStateOf(false)}
    var activeName by rememberSaveable(group) {mutableStateOf(if(group==1) RecipeItem.Print.name else recipeGroups[group].first().name)}
    var detail by rememberSaveable(group) {mutableStateOf(false)}
    var page by rememberSaveable(group) {mutableIntStateOf(0)}
    val active=RecipeItem.valueOf(activeName)
    val effects=group==2
    val cameraEffects=effects && !editing
    val showingDetail=detail && !cameraEffects
    BackHandler(catalog || showingDetail) {if(catalog) catalog=false else detail=false}
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
                if(showingDetail) {
                    CameraIconButton(CameraIcons.Back,tr(Texts.string.back_to_controls)) {detail=false}
                    Text(active.label,Modifier.weight(1f),fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                } else Box(Modifier.weight(1f)) {RecipeTabs(group) {detail=false;select(it)}}
                CameraIconButton(CameraIcons.Reset,tr(Texts.string.reset_film_print_and_effects)) {changed(FilmRecipe())}
                actions()
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal=8.dp)) {
                val density=LocalDensity.current
                val scale=density.fontScale
                val columns=if(effects || scale>1.2f || maxWidth<270.dp) 2 else 3
                val tileHeight=maxOf(50f,36*scale).dp
                val naturalRows=(recipeGroups[group].size+columns-1)/columns
                val adjustmentHeight=if(cameraEffects) 48.dp+maxOf(16.dp,with(density) {16.sp.toDp()}) else 72.dp
                val requiredHeight=tileHeight*naturalRows+4.dp*(naturalRows-1)+adjustmentHeight
                val separate=!cameraEffects && (maxHeight<requiredHeight || scale>1.2f || maxWidth<200.dp)
                val rows=if(separate) ((maxHeight-48.dp)/(tileHeight+4.dp)).toInt().coerceAtLeast(1) else naturalRows
                val pages=recipeGroups[group].chunked(rows*columns)
                val currentPage=page.coerceIn(pages.indices)
                val body=Modifier.fillMaxSize()
                // On a short camera panel, start at the slider and let the user scroll up to the options.
                val scrollToAdjustment=cameraEffects && maxHeight<requiredHeight
                val scrollable=scrollToAdjustment || effects && (separate || showingDetail)
                val scrollItem=if(cameraEffects) active else null
                Column(if(scrollable) body.verticalScroll(key(showingDetail,currentPage,scrollItem) {rememberScrollState()},
                    reverseScrolling=scrollToAdjustment) else body) {
                    if(showingDetail) {
                        key(active) {RecipeAdjustment(active,recipe,changed,{catalog=true},Modifier.fillMaxWidth(),showTitle=false)}
                    } else {
                        pages[currentPage].chunked(columns).forEachIndexed {index,row->
                            if(index>0) Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                                row.forEach {item->
                                    val enabled=item.toggle(recipe)
                                    val label=item.label
                                    val value=item.value(recipe)
                                    val highlighted=enabled ?: (item==active)
                                    TextButton(onClick={
                                        if(enabled!=null) changed(recipe.copy(compensate=!enabled))
                                        else {activeName=item.name;detail=separate}
                                    },contentPadding=PaddingValues(horizontal=4.dp,vertical=2.dp),
                                        colors=ButtonDefaults.textButtonColors(containerColor=if(highlighted) Accent.copy(alpha=.15f) else MaterialTheme.colorScheme.surfaceVariant,
                                            contentColor=if(highlighted) Accent else MaterialTheme.colorScheme.onSurface),
                                        modifier=Modifier.weight(1f).height(tileHeight).semantics {
                                            contentDescription=tr(Texts.string.control_value,label);stateDescription=value
                                            if(enabled!=null) toggleableState=if(enabled) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off
                                            else selected=item==active
                                        }) {
                                        Column(horizontalAlignment=Alignment.CenterHorizontally) {
                                            Text(label,fontSize=12.sp,lineHeight=15.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                                            Text(value,fontSize=13.sp,lineHeight=16.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                                repeat(columns-row.size) {Spacer(Modifier.weight(1f))}
                            }
                        }
                        if(pages.size>1) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            TextButton(onClick={page=currentPage-1},enabled=currentPage>0) {Text(tr(Texts.string.previous_page))}
                            TextButton(onClick={page=currentPage+1},enabled=currentPage<pages.lastIndex) {Text(tr(Texts.string.next_page))}
                        }
                        if(!separate) {
                            if(!scrollable) Spacer(Modifier.weight(1f))
                            key(active) {
                                RecipeAdjustment(active,recipe,changed,{catalog=true},Modifier.fillMaxWidth(),
                                    hint=if(cameraEffects) tr(Texts.string.final_photo_only) else null)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun RecipeAdjustment(item: RecipeItem,recipe: FilmRecipe,changed: (FilmRecipe)->Unit,
    catalog: ()->Unit,modifier: Modifier=Modifier,showTitle: Boolean=true,hint: String?=null) {
    if(item==RecipeItem.Stock || item==RecipeItem.Paper) {
        val paper=item==RecipeItem.Paper
        Box(modifier) {StockPicker(if(paper) tr(Texts.string.paper) else tr(Texts.string.film),if(paper) recipe.paperProfile else recipe.filmProfile,
            if(paper) FilmStocks.paperChoices(recipe.filmProfile) else FilmStocks.films,onCatalog=catalog) {
            changed(if(paper) recipe.copy(paperProfile=it) else recipe.copy(filmProfile=it))
        }}
    } else item.dial(recipe)?.let {dial->
        var value by remember(item,dial.value) {mutableFloatStateOf(dial.value.toFloat())}
        Column(modifier) {
            Row(Modifier.fillMaxWidth().heightIn(min=if(hint==null) 24.dp else 16.dp),verticalAlignment=Alignment.CenterVertically) {
                if(showTitle) Text(item.label,Modifier.weight(1f),fontSize=13.sp,lineHeight=16.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                else Spacer(Modifier.weight(1f))
                if(hint!=null) Text(hint,Modifier.weight(1f).padding(end=8.dp),
                    fontSize=11.sp,lineHeight=16.sp,color=Muted,textAlign=TextAlign.End,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(dial.format(value.roundToInt()),color=Accent,fontSize=13.sp,lineHeight=16.sp,maxLines=1)
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Slider(value,{value=it},valueRange=dial.min.toFloat()..dial.max.toFloat(),steps=if(dial.continuous) 0 else dial.max-dial.min-1,
                    onValueChangeFinished={
                        val ticks=value.roundToInt().coerceIn(dial.min,dial.max)
                        value=ticks.toFloat()
                        if(ticks!=dial.value) changed(dial.change(ticks))
                    },modifier=Modifier.weight(1f).height(48.dp).padding(horizontal=12.dp).semantics {contentDescription=item.label})
                CameraIconButton(CameraIcons.Reset,tr(Texts.string.reset_value, item.label)) {changed(dial.change(dial.default))}
            }
        }
    }
}
