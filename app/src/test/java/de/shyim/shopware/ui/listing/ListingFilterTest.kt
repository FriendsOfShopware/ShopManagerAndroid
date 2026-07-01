package de.shyim.shopware.ui.listing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

class ListingFilterTest {

    private lateinit var originalZone: TimeZone

    @Before
    fun pinZoneToUtc() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    private fun assertGolden(expected: String, filter: ListingFilter, value: FilterValue) {
        assertEquals(
            Json.parseToJsonElement(expected),
            JsonArray(filter.criteriaFor(value)),
        )
    }

    @Test
    fun optionsProducesEqualsAnyOnField() {
        assertGolden(
            """[{"type": "equalsAny", "field": "salesChannel.id", "value": ["a1", "b2"]}]""",
            ListingFilter.Options("salesChannel", "Sales channel", "salesChannel.id"),
            FilterValue.OptionsValue(linkedSetOf("a1", "b2")),
        )
    }

    @Test
    fun salesChannelFilterFieldPaths() {
        // FK column on order/customer/product_review (verified against live 6.7.8)
        assertGolden(
            """[{"type": "equalsAny", "field": "salesChannelId", "value": ["a1"]}]""",
            salesChannelFilter("Sales channel"),
            FilterValue.OptionsValue(setOf("a1")),
        )
        // promotion has no FK — m:n via the salesChannels mapping entity
        assertGolden(
            """[{"type": "equalsAny", "field": "salesChannels.salesChannelId", "value": ["a1"]}]""",
            salesChannelFilter("Sales channel", "salesChannels.salesChannelId"),
            FilterValue.OptionsValue(setOf("a1")),
        )
    }

    @Test
    fun numberRangeProducesRangeWithGteLte() {
        assertGolden(
            """[{"type": "range", "field": "amountTotal", "parameters": {"gte": 100.0, "lte": 500.0}}]""",
            ListingFilter.NumberRange("amount", "Amount", "amountTotal"),
            FilterValue.RangeValue(min = 100.0, max = 500.0),
        )
    }

    @Test
    fun numberRangeOmitsUnsetBound() {
        assertGolden(
            """[{"type": "range", "field": "amountTotal", "parameters": {"gte": 100.0}}]""",
            ListingFilter.NumberRange("amount", "Amount", "amountTotal"),
            FilterValue.RangeValue(min = 100.0),
        )
    }

    @Test
    fun dateRangeUsesDayStartAndExclusiveNextDayInstants() {
        assertGolden(
            """[{
                "type": "range",
                "field": "orderDateTime",
                "parameters": {"gte": "2026-01-01T00:00:00Z", "lt": "2026-02-01T00:00:00Z"}
            }]""",
            ListingFilter.DateRange("orderDate", "Order date", "orderDateTime"),
            FilterValue.DateRangeValue(
                from = LocalDate.of(2026, 1, 1),
                to = LocalDate.of(2026, 1, 31),
            ),
        )
    }

    @Test
    fun existenceHasProducesNotEqualsNull() {
        assertGolden(
            """[{"type": "not", "operator": "and", "queries": [
                {"type": "equals", "field": "documents.id", "value": null}
            ]}]""",
            ListingFilter.Existence("documents", "Documents", "documents.id", "Has documents", "No documents"),
            FilterValue.ExistenceValue(true),
        )
    }

    @Test
    fun existenceHasNotProducesEqualsNull() {
        assertGolden(
            """[{"type": "equals", "field": "documents.id", "value": null}]""",
            ListingFilter.Existence("documents", "Documents", "documents.id", "Has documents", "No documents"),
            FilterValue.ExistenceValue(false),
        )
    }

    @Test
    fun textModes() {
        val contains = ListingFilter.Text("name", "Name", "name")
        val equals = ListingFilter.Text("name", "Name", "name", ListingFilter.Text.Mode.Equals)
        val prefix = ListingFilter.Text("name", "Name", "name", ListingFilter.Text.Mode.Prefix)
        val value = FilterValue.TextValue("shirt")

        assertGolden("""[{"type": "contains", "field": "name", "value": "shirt"}]""", contains, value)
        assertGolden("""[{"type": "equals", "field": "name", "value": "shirt"}]""", equals, value)
        assertGolden("""[{"type": "prefix", "field": "name", "value": "shirt"}]""", prefix, value)
    }

    @Test
    fun boolProducesEqualsWithBooleanValue() {
        val filter = ListingFilter.Bool("status", "Status", "status", "Approved", "Pending")
        assertGolden(
            """[{"type": "equals", "field": "status", "value": true}]""",
            filter, FilterValue.OptionsValue(setOf("true")),
        )
        assertGolden(
            """[{"type": "equals", "field": "status", "value": false}]""",
            filter, FilterValue.OptionsValue(setOf("false")),
        )
    }

    @Test
    fun boolWithoutSingleSelectionProducesNoCriteria() {
        val filter = ListingFilter.Bool("status", "Status", "status", "Approved", "Pending")
        assertTrue(filter.criteriaFor(FilterValue.OptionsValue(emptySet())).isEmpty())
        assertTrue(filter.criteriaFor(FilterValue.OptionsValue(setOf("true", "false"))).isEmpty())
    }

    @Test
    fun emptyValuesProduceNoCriteria() {
        val options = ListingFilter.Options("state", "State", "stateMachineState.id")
        assertTrue(options.criteriaFor(FilterValue.OptionsValue(emptySet())).isEmpty())
        assertTrue(
            ListingFilter.Text("name", "Name", "name")
                .criteriaFor(FilterValue.TextValue("  ")).isEmpty()
        )
        assertTrue(
            ListingFilter.NumberRange("amount", "Amount", "amountTotal")
                .criteriaFor(FilterValue.RangeValue()).isEmpty()
        )
        assertTrue(
            ListingFilter.DateRange("date", "Date", "orderDateTime")
                .criteriaFor(FilterValue.DateRangeValue()).isEmpty()
        )
        assertTrue(
            ListingFilter.Existence("docs", "Docs", "documents.id", "Has", "Has not")
                .criteriaFor(FilterValue.ExistenceValue(null)).isEmpty()
        )
    }

    @Test
    fun mismatchedValueTypeProducesNoCriteria() {
        val options = ListingFilter.Options("state", "State", "stateMachineState.id")
        assertTrue(options.criteriaFor(FilterValue.TextValue("open")).isEmpty())
    }
}
