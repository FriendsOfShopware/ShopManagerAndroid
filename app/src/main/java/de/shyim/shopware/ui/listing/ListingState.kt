package de.shyim.shopware.ui.listing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.shyim.shopware.api.Criteria
import de.shyim.shopware.api.SearchResult
import de.shyim.shopware.api.SwEntity
import de.shyim.shopware.api.TotalCountMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Generic paged listing holder: term + declarative filters + load-more, shared by screen ViewModels
class ListingState<T>(
    private val scope: CoroutineScope,
    private val pageSize: Int = 25,
    val filters: List<ListingFilter> = emptyList(),
    private val source: suspend (criteria: Criteria) -> SearchResult,
    private val baseCriteria: () -> Criteria,
    private val mapper: (SwEntity) -> T,
    private val errorFallback: String = "Request failed",
) {
    var items by mutableStateOf<List<T>>(emptyList())
        private set
    var total by mutableStateOf(0)
        private set
    var loading by mutableStateOf(false)
        private set
    // True only while a user-initiated pull-to-refresh is in flight (drives the
    // PullToRefreshBox spinner) — distinct from [loading], which also covers the
    // initial page load and pagination.
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var termState by mutableStateOf("")
    val term: String get() = termState
    var activeValues by mutableStateOf<Map<String, FilterValue>>(emptyMap())
        private set

    val activeFilterCount: Int get() = activeValues.size

    private var page = 1
    private var fetchJob: Job? = null
    private var fetchId = 0

    fun reload() {
        page = 1
        fetchJob?.cancel()
        fetchJob = scope.launch { fetch(replace = true) }
    }

    // Pull-to-refresh: reload page 1 with the refreshing spinner instead of the
    // full-screen first-load placeholder, so the current rows stay visible.
    fun refresh() {
        page = 1
        fetchJob?.cancel()
        refreshing = true
        fetchJob = scope.launch {
            try {
                fetch(replace = true)
            } finally {
                refreshing = false
            }
        }
    }

    fun loadMore() {
        if (loading || items.size >= total) return
        page += 1
        fetchJob = scope.launch { fetch(replace = false) }
    }

    fun setTerm(t: String) {
        termState = t
    }

    fun search() = reload()

    fun setFilterValue(key: String, value: FilterValue?) {
        activeValues = if (value == null || value.isEmpty) activeValues - key
        else activeValues + (key to value)
        reload()
    }

    fun applyFilterValues(values: Map<String, FilterValue>) {
        activeValues = values.filterValues { !it.isEmpty }
        reload()
    }

    fun mutateItem(predicate: (T) -> Boolean, transform: (T) -> T) {
        items = items.map { if (predicate(it)) transform(it) else it }
    }

    fun removeItem(predicate: (T) -> Boolean) {
        val remaining = items.filterNot(predicate)
        total -= items.size - remaining.size
        items = remaining
    }

    // fetchId guards against out-of-order completions (reload during load-more, rapid filter taps)
    private suspend fun fetch(replace: Boolean) {
        val id = ++fetchId
        loading = true
        error = null
        try {
            val result = source(buildCriteria())
            if (id != fetchId) return
            total = result.total
            val mapped = result.data.map(mapper)
            items = if (replace) mapped else items + mapped
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (id != fetchId) return
            if (!replace) page -= 1
            error = e.message ?: errorFallback
        } finally {
            if (id == fetchId) loading = false
        }
    }

    private fun buildCriteria(): Criteria {
        val criteria = baseCriteria()
            .setPage(page)
            .setLimit(pageSize)
            .setTotalCountMode(TotalCountMode.Exact)
        if (term.isNotBlank()) criteria.setTerm(term)
        activeValues.forEach { (key, value) ->
            val filter = filters.firstOrNull { it.key == key } ?: return@forEach
            filter.criteriaFor(value).forEach(criteria::addFilter)
        }
        return criteria
    }
}
