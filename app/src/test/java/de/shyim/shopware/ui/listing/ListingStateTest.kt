package de.shyim.shopware.ui.listing

import de.shyim.shopware.api.Criteria
import de.shyim.shopware.api.SearchResult
import de.shyim.shopware.api.SwEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

// Unconfined scope + synchronous fakes make every launch complete inline
class ListingStateTest {

    private val received = mutableListOf<JsonObject>()

    private fun result(total: Int, vararg names: String) = SearchResult(
        total = total,
        data = names.map { SwEntity(buildJsonObject { put("name", it) }) },
        aggregations = JsonObject(emptyMap()),
    )

    private fun state(
        filters: List<ListingFilter> = emptyList(),
        source: suspend (Criteria) -> SearchResult,
    ) = ListingState(
        scope = CoroutineScope(Dispatchers.Unconfined),
        pageSize = 2,
        filters = filters,
        source = { criteria ->
            received += criteria.toJson()
            source(criteria)
        },
        baseCriteria = { Criteria().addSorting("orderDateTime", "DESC") },
        mapper = { it.string("name") ?: "" },
    )

    private fun requestedPage(request: JsonObject): Int? =
        (request["page"] as? JsonPrimitive)?.intOrNull

    @Test
    fun firstLoadPopulatesItemsAndTotal() {
        val state = state { result(5, "a", "b") }
        state.reload()

        assertEquals(listOf("a", "b"), state.items)
        assertEquals(5, state.total)
        assertNull(state.error)
        assertEquals(
            Json.parseToJsonElement(
                """
                {
                  "page": 1,
                  "limit": 2,
                  "total-count-mode": 1,
                  "sort": [{"field": "orderDateTime", "order": "DESC"}]
                }
                """
            ),
            received.single(),
        )
    }

    @Test
    fun loadMoreAppendsAndStopsAtTotal() {
        val state = state { criteria ->
            when (requestedPage(criteria.toJson())) {
                1 -> result(3, "a", "b")
                else -> result(3, "c")
            }
        }
        state.reload()
        state.loadMore()

        assertEquals(listOf("a", "b", "c"), state.items)
        assertEquals(2, received.size)
        assertEquals(2, requestedPage(received.last()))

        state.loadMore()
        assertEquals(2, received.size)
    }

    @Test
    fun failingLoadMoreRestoresPage() {
        var failNext = false
        val state = state { criteria ->
            if (failNext) {
                failNext = false
                throw RuntimeException("boom")
            }
            when (requestedPage(criteria.toJson())) {
                1 -> result(10, "a", "b")
                else -> result(10, "c", "d")
            }
        }
        state.reload()

        failNext = true
        state.loadMore()
        assertEquals("boom", state.error)
        assertEquals(listOf("a", "b"), state.items)

        state.loadMore()
        assertEquals(2, requestedPage(received.last()))
        assertEquals(listOf("a", "b", "c", "d"), state.items)
        assertNull(state.error)
    }

    @Test
    fun setFilterValueReloadsWithMergedCriteria() {
        val filters = listOf(
            ListingFilter.Options("state", "Status", "stateMachineState.id"),
            ListingFilter.NumberRange("amount", "Amount", "amountTotal"),
        )
        val state = state(filters) { result(1, "a") }
        state.setTerm("hoodie")
        state.setFilterValue("state", FilterValue.OptionsValue(setOf("s1")))
        state.setFilterValue("amount", FilterValue.RangeValue(min = 100.0))

        val request = received.last()
        assertEquals("hoodie", (request["term"] as JsonPrimitive).content)
        assertEquals(
            Json.parseToJsonElement(
                """
                [
                  {"type": "equalsAny", "field": "stateMachineState.id", "value": ["s1"]},
                  {"type": "range", "field": "amountTotal", "parameters": {"gte": 100.0}}
                ]
                """
            ),
            request["filter"],
        )

        state.setFilterValue("state", null)
        assertEquals(1, (received.last()["filter"] as JsonArray).size)
        assertEquals(1, state.activeFilterCount)

        state.applyFilterValues(emptyMap())
        assertNull(received.last()["filter"])
        assertEquals(0, state.activeFilterCount)
    }

    @Test
    fun applyFilterValuesReplacesAllValuesWithSingleReload() {
        val filters = listOf(
            ListingFilter.Options("state", "Status", "stateMachineState.id"),
            ListingFilter.NumberRange("amount", "Amount", "amountTotal"),
        )
        val state = state(filters) { result(1, "a") }
        state.setFilterValue("state", FilterValue.OptionsValue(setOf("s1")))
        received.clear()

        state.applyFilterValues(
            mapOf(
                "amount" to FilterValue.RangeValue(min = 50.0),
                "state" to FilterValue.OptionsValue(emptySet()),
            )
        )

        assertEquals(1, received.size)
        assertEquals(
            Json.parseToJsonElement(
                """[{"type": "range", "field": "amountTotal", "parameters": {"gte": 50.0}}]"""
            ),
            received.single()["filter"],
        )
        assertEquals(1, state.activeFilterCount)
    }

    @Test
    fun searchSendsTermAndResetsToFirstPage() {
        val state = state { result(10, "a", "b") }
        state.reload()
        state.loadMore()

        state.setTerm("shirt")
        state.search()

        val request = received.last()
        assertEquals(1, requestedPage(request))
        assertEquals("shirt", (request["term"] as JsonPrimitive).content)
    }

    @Test
    fun mutateItemTransformsMatchingItem() {
        val state = state { result(2, "a", "b") }
        state.reload()

        state.mutateItem({ it == "b" }, { it.uppercase() })
        assertEquals(listOf("a", "B"), state.items)
    }

    @Test
    fun removeItemDropsMatchAndDecrementsTotal() {
        val state = state { result(5, "a", "b") }
        state.reload()

        state.removeItem { it == "a" }
        assertEquals(listOf("b"), state.items)
        assertEquals(4, state.total)
    }

    @Test
    fun reloadCancelsInFlightLoadMoreSoStalePageIsDiscarded() = runBlocking {
        val gate = CompletableDeferred<SearchResult>()
        val state = state { criteria ->
            when (requestedPage(criteria.toJson())) {
                2 -> gate.await()
                else -> result(4, "x", "y")
            }
        }
        state.reload()
        state.loadMore() // page-2 request suspends on the gate

        state.reload() // new criteria; in-flight page must not append later
        gate.complete(result(99, "stale1", "stale2"))

        assertEquals(listOf("x", "y"), state.items)
        assertEquals(4, state.total)
        assertEquals(false, state.loading)

        state.loadMore() // pagination still works after the cancelled fetch
        assertEquals(2, requestedPage(received.last()))
    }

    @Test
    fun failedReloadSurfacesErrorMessage() {
        val state = state { throw IllegalStateException("offline") }
        state.reload()

        assertNotNull(state.error)
        assertEquals("offline", state.error)
        assertEquals(false, state.loading)
    }
}
