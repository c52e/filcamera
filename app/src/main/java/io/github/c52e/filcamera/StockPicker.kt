package io.github.c52e.filcamera

import io.github.c52e.filcamera.strings.tr
import io.github.c52e.filcamera.strings.R as Texts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** A local browsing position is not a recipe change until the pager has settled. */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun StockPicker(title: String,value: String,values: Map<String,String>,compact: Boolean=false,onCatalog: (() -> Unit)?=null,change: (String)->Unit) {
    val ids=values.keys.toList()
    val pager=rememberPagerState(initialPage=ids.indexOf(value).coerceAtLeast(0),pageCount={ids.size})
    val scope=rememberCoroutineScope()
    val selected by rememberUpdatedState(value)
    val commit by rememberUpdatedState(change)
    var syncing by remember {mutableStateOf(false)}
    var open by rememberSaveable {mutableStateOf(false)}
    // External restore/reset and full-list selection must reposition without emitting an old page.
    LaunchedEffect(value,ids) {
        val index=ids.indexOf(value)
        if(index>=0 && index!=pager.settledPage) {
            syncing=true
            try {pager.scrollToPage(index)} finally {syncing=false}
        }
    }
    LaunchedEffect(pager,ids) {
        snapshotFlow {Triple(pager.settledPage,pager.isScrollInProgress,syncing)}.collectLatest {(page,moving,sync)->
            if(!moving && !sync) {
                delay(200)
                ids.getOrNull(page)?.let {if(it!=selected) commit(it)}
            }
        }
    }
    fun browse(index: Int) {scope.launch {pager.animateScrollToPage(index.coerceIn(ids.indices))}}
    @Composable fun pages(modifier: Modifier) {
        HorizontalPager(pager,contentPadding=PaddingValues(horizontal=20.dp),pageSpacing=8.dp,key={ids[it]},
            modifier=modifier.semantics {contentDescription=tr(Texts.string.value_carousel, title)}) {page->
            val id=ids[page];val name=values.getValue(id);val centered=page==pager.currentPage
            Surface(shape=RoundedCornerShape(12.dp),color=if(centered) Accent.copy(alpha=.12f) else MaterialTheme.colorScheme.surfaceVariant,
                border=BorderStroke(if(centered) 2.dp else 1.dp,if(centered) Accent else Muted.copy(alpha=.35f)),
                modifier=Modifier.fillMaxWidth().selectable(id==value,role=Role.RadioButton,onClick={browse(page)})
                    .semantics {contentDescription=tr(Texts.string.select_value_value, title, name)}) {
                Column(Modifier.heightIn(min=(if(compact) 48f else 76*maxOf(1f,LocalDensity.current.fontScale)).dp).padding(if(compact) 4.dp else 12.dp),
                    verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                    Text(if(id==FilmStocks.AUTO_PAPER) tr(Texts.string.auto) else name.substringBefore(' '),fontSize=12.sp,color=Muted)
                    Text(if(id==FilmStocks.AUTO_PAPER) name.substringAfter(" · ") else name.substringAfter(' '),
                        fontSize=if(compact) 12.sp else 15.sp,maxLines=if(compact) 2 else 3,overflow=TextOverflow.Ellipsis,color=if(centered) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if(compact) {
        Row(Modifier.fillMaxWidth().heightIn(min=72.dp),verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={browse(pager.currentPage-1)},enabled=pager.currentPage>0,contentPadding=PaddingValues(0.dp),modifier=Modifier.size(48.dp).semantics {contentDescription=tr(Texts.string.previous_value, title)}) {Text("‹",fontSize=26.sp)}
            pages(Modifier.weight(1f))
            TextButton(onClick={browse(pager.currentPage+1)},enabled=pager.currentPage<ids.lastIndex,contentPadding=PaddingValues(0.dp),modifier=Modifier.size(48.dp).semantics {contentDescription=tr(Texts.string.next_value, title)}) {Text("›",fontSize=26.sp)}
            TextButton(onClick={onCatalog?.invoke()},contentPadding=PaddingValues(0.dp),modifier=Modifier.size(48.dp).semantics {contentDescription=tr(Texts.string.all_value, title)}) {Text(tr(Texts.string.all),fontSize=12.sp)}
        }
    } else {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(title,Modifier.weight(1f),fontSize=14.sp)
            TextButton(onClick={browse(pager.currentPage-1)},enabled=pager.currentPage>0,
                contentPadding=PaddingValues(0.dp),modifier=Modifier.size(48.dp).semantics {contentDescription=tr(Texts.string.previous_value, title)}) {Text("‹",fontSize=26.sp)}
            TextButton(onClick={browse(pager.currentPage+1)},enabled=pager.currentPage<ids.lastIndex,
                contentPadding=PaddingValues(0.dp),modifier=Modifier.size(48.dp).semantics {contentDescription=tr(Texts.string.next_value, title)}) {Text("›",fontSize=26.sp)}
            TextButton(onClick={if(onCatalog!=null) onCatalog() else open=!open},contentPadding=PaddingValues(horizontal=4.dp),
                modifier=Modifier.heightIn(min=48.dp).semantics {contentDescription=tr(Texts.string.all_value, title)}) {Text(if(open) tr(Texts.string.collapse) else tr(Texts.string.all),fontSize=12.sp)}
        }
        pages(Modifier.fillMaxWidth())
        if(!compact) Spacer(Modifier.height(8.dp))
        if(open) {
            // Bounded, independently scrollable list: no modal scrim over the live image.
            Column(Modifier.fillMaxWidth().heightIn(max=280.dp).verticalScroll(rememberScrollState())) {
                StockOptions(value,values) {commit(it);open=false}
            }
        }
    }
}

}
