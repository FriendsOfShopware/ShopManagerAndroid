package de.shyim.shopware.ui.listing

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.shyim.shopware.R
import de.shyim.shopware.api.ShopApi
import de.shyim.shopware.ui.components.ScreenTitle
import de.shyim.shopware.ui.isExpandedWidth
import de.shyim.shopware.ui.screens.SyncErrorCard

// One-tap chip that sets (or clears, when already active) a single filter value
data class QuickFilter(val key: String, val label: String, val value: FilterValue)

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun <T> ListingScaffold(
    title: String,
    state: ListingState<T>,
    api: ShopApi?,
    searchPlaceholder: String,
    emptyText: String,
    emptyIcon: ImageVector = Icons.Outlined.SearchOff,
    quickFilters: List<QuickFilter> = emptyList(),
    header: (@Composable () -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    // On expanded width (tablets), flow the rows into a 2-column grid instead of a narrow
    // centered column — for screens that have no list-detail pane (Products/Promos/Reviews).
    gridOnExpanded: Boolean = false,
    row: @Composable (T) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    var showFilterSheet by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val grid = gridOnExpanded && isExpandedWidth()

    // loading in the tuple re-evaluates after a reload whose first page has the same item count
    LaunchedEffect(listState, gridState, grid, state) {
        snapshotFlow {
            val lastVisible = if (grid) {
                gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            } else {
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            }
            Triple(
                lastVisible,
                state.items.size,
                state.loading,
            )
        }.collect { (lastVisible, size, loading) ->
            val total = if (grid) gridState.layoutInfo.totalItemsCount else listState.layoutInfo.totalItemsCount
            if (!loading && size > 0 && lastVisible >= total - 4) state.loadMore()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = if (grid) 1100.dp else 560.dp)
                .fillMaxSize()
        ) {
            if (onBack != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = cs.onSurface)
                    }
                    Text(title, color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                ScreenTitle(title)
            }

            OutlinedTextField(
                value = state.term,
                onValueChange = state::setTerm,
                placeholder = { Text(searchPlaceholder) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.term.isNotBlank()) {
                        IconButton(onClick = { state.setTerm(""); state.search() }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.common_clear))
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    state.search()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )

            if (quickFilters.isNotEmpty() || state.filters.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    quickFilters.forEach { quick ->
                        val selected = state.activeValues[quick.key] == quick.value
                        FilterChip(
                            selected = selected,
                            onClick = { state.setFilterValue(quick.key, if (selected) null else quick.value) },
                            label = { Text(quick.label) }
                        )
                    }
                    if (state.filters.isNotEmpty()) {
                        val activeCount = state.activeFilterCount
                        FilterChip(
                            selected = activeCount > 0,
                            onClick = { showFilterSheet = true },
                            leadingIcon = {
                                Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            label = {
                                Text(
                                    if (activeCount > 0) stringResource(R.string.listing_filters_count, activeCount)
                                    else stringResource(R.string.listing_filters)
                                )
                            }
                        )
                    }
                }
            }

            state.error?.let { SyncErrorCard(it, onRetry = state::reload, compact = true) }

            if (state.items.isNotEmpty()) {
                Text(
                    pluralStringResource(R.plurals.listing_results, state.total, state.total),
                    color = cs.onSurfaceVariant, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp)
                )
            }

            // Shared placeholders so the grid and column render the same loading/empty states
            val firstLoad: @Composable () -> Unit = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 80.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LoadingIndicator(modifier = Modifier.size(56.dp))
                }
            }
            val emptyState: @Composable () -> Unit = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 60.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(emptyIcon, contentDescription = null, tint = cs.outline, modifier = Modifier.size(40.dp))
                    Text(
                        emptyText,
                        color = cs.onSurfaceVariant, fontSize = 13.5.sp,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
            val loadMore: @Composable () -> Unit = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }

            // The list scrolls under the nav-suite bar; pad the last rows past it AND past the
            // system navigation bar (edge-to-edge) so nothing hides behind the gesture area.
            val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val listContentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, bottom = 96.dp + bottomInset
            )
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = state::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (grid) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = listContentPadding,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        header?.let { item(key = "header", span = { GridItemSpan(maxLineSpan) }) { it() } }
                        when {
                            state.items.isEmpty() && state.loading ->
                                item(key = "first-load", span = { GridItemSpan(maxLineSpan) }) { firstLoad() }
                            state.items.isEmpty() && state.error == null ->
                                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) { emptyState() }
                            else -> {
                                items(state.items.size) { index -> row(state.items[index]) }
                                if (state.loading && !state.refreshing) {
                                    item(key = "load-more", span = { GridItemSpan(maxLineSpan) }) { loadMore() }
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = listContentPadding,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        header?.let { item(key = "header") { it() } }
                        when {
                            state.items.isEmpty() && state.loading -> item(key = "first-load") { firstLoad() }
                            state.items.isEmpty() && state.error == null -> item(key = "empty") { emptyState() }
                            else -> {
                                items(state.items.size) { index -> row(state.items[index]) }
                                if (state.loading && !state.refreshing) {
                                    item(key = "load-more") { loadMore() }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        FilterSheet(
            filters = state.filters,
            activeValues = state.activeValues,
            api = api,
            onApply = state::applyFilterValues,
            onDismiss = { showFilterSheet = false },
        )
    }
}
