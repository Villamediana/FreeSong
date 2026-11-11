package com.villamediana.sound.ui.social

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A list whose [header] (profile, jam, the tab pills…) scrolls away with it, and whose tabs are
 * pages side by side: a sideways swipe anywhere on the list moves to the next tab with the finger,
 * the [selected] tab following the page in view (as the app's main tabs do). Choosing a tab
 * elsewhere (a pill) brings its page. Each tab keeps its own scroll; scrolling up, the header goes
 * first, then the tab's list; scrolling down, the list comes back to its top, then the header.
 * With no [tabs], only the header.
 */
@Composable
internal fun <T : Any> SwipeTabsList(
    tabs: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    header: LazyListScope.() -> Unit,
    page: LazyListScope.(T) -> Unit,
) {
    val pager = rememberPagerState(initialPage = tabs.indexOf(selected).coerceAtLeast(0)) { tabs.size }
    val select by rememberUpdatedState(onSelect)
    val tabsNow by rememberUpdatedState(tabs)
    // The page in view names the tab (already halfway through a swipe, so the pill follows the finger).
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { p -> tabsNow.getOrNull(p)?.let(select) } }
    // A tab chosen elsewhere brings its page.
    LaunchedEffect(selected, tabs) {
        val i = tabs.indexOf(selected)
        if (i >= 0 && i != pager.currentPage && !pager.isScrollInProgress) pager.animateScrollToPage(i)
    }
    val outer = rememberLazyListState()
    val headerFirst = remember(outer) { HeaderFirst(outer) }

    BoxWithConstraints(modifier) {
        // A page fills what is left under the status bar once the header is gone.
        val pageHeight = (maxHeight - top).coerceAtLeast(0.dp)
        LazyColumn(Modifier.fillMaxSize(), state = outer, contentPadding = PaddingValues(top = top)) {
            header()
            if (tabs.isNotEmpty()) {
                item(key = "swipe-tabs-pages") {
                    HorizontalPager(
                        state = pager,
                        modifier = Modifier.fillMaxWidth().height(pageHeight).nestedScroll(headerFirst).testTag("swipe-tabs-pages"),
                        beyondViewportPageCount = 1,
                        key = { i -> tabs.getOrNull(i)?.toString() ?: i },
                    ) { i ->
                        val tab = tabs.getOrNull(i) ?: return@HorizontalPager
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom)) { page(tab) }
                    }
                }
            }
        }
    }
}

/** A tab's list scrolling up moves the header away first (the outer list), then itself. */
private class HeaderFirst(private val outer: LazyListState) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (available.y >= 0f) return Offset.Zero
        return Offset(0f, -outer.dispatchRawDelta(-available.y))
    }
}
