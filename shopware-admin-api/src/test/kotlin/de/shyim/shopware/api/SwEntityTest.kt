package de.shyim.shopware.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

// Trimmed capture of POST /api/search/order against Shopware 6.7.8
// (associations: stateMachineState, lineItems, currency; sum/count/histogram/terms aggregations).
private const val ORDER_SEARCH = """
{
    "total": 40,
    "data": [
        {
            "_uniqueIdentifier": "046d7824c16843b69a7c7becf49da54a",
            "versionId": "0fa91ce3e96a4bc2be4bd9ce752c3425",
            "translated": [],
            "createdAt": "2026-06-11T08:06:24.003+00:00",
            "updatedAt": null,
            "orderNumber": "11008",
            "currencyId": "b7d2554b0ce847cd82f3ac9bd1c0dfca",
            "currencyFactor": 1,
            "orderDateTime": "2026-06-11T18:44:23.000+00:00",
            "orderDate": "2026-06-11T00:00:00.000+00:00",
            "amountTotal": 2931.88,
            "amountNet": 2463.76,
            "shippingTotal": 0,
            "deepLinkCode": null,
            "autoIncrement": 8,
            "stateMachineState": {
                "_uniqueIdentifier": "019e7c5df046714bbc13a0bafa014b84",
                "translated": {
                    "name": "Done",
                    "customFields": []
                },
                "createdAt": "2026-05-31T04:49:51.695+00:00",
                "updatedAt": null,
                "technicalName": "completed",
                "id": "019e7c5df046714bbc13a0bafa014b84",
                "apiAlias": "state_machine_state"
            },
            "currency": {
                "translated": {
                    "name": "Euro"
                },
                "isoCode": "EUR",
                "factor": 1,
                "symbol": "€",
                "name": "Euro",
                "id": "b7d2554b0ce847cd82f3ac9bd1c0dfca",
                "apiAlias": "currency"
            },
            "lineItems": [
                {
                    "_uniqueIdentifier": "2094370ef2c54aed8aeefe01c60dfef9",
                    "translated": [],
                    "label": "Main product with advanced prices",
                    "quantity": 2,
                    "unitPrice": 950,
                    "totalPrice": 1900,
                    "position": 3,
                    "good": true,
                    "id": "2094370ef2c54aed8aeefe01c60dfef9",
                    "apiAlias": "order_line_item"
                }
            ],
            "billingAddress": null,
            "id": "046d7824c16843b69a7c7becf49da54a",
            "apiAlias": "order"
        }
    ],
    "aggregations": {
        "revenue": {
            "name": "revenue",
            "sum": 52855.91000000001,
            "apiAlias": "revenue_aggregation"
        },
        "orderCount": {
            "name": "orderCount",
            "count": 40,
            "apiAlias": "orderCount_aggregation"
        },
        "perDay": {
            "name": "perDay",
            "buckets": [
                {
                    "key": "2026-06-10 00:00:00",
                    "count": 3,
                    "dayRevenue": {
                        "extensions": [],
                        "name": "dayRevenue",
                        "sum": 179.94
                    },
                    "apiAlias": "aggregation_bucket"
                },
                {
                    "key": "2026-06-11 00:00:00",
                    "count": 8,
                    "dayRevenue": {
                        "extensions": [],
                        "name": "dayRevenue",
                        "sum": 11453.44
                    },
                    "apiAlias": "aggregation_bucket"
                }
            ],
            "apiAlias": "perDay_aggregation"
        },
        "byState": {
            "name": "byState",
            "buckets": [
                {
                    "key": "completed",
                    "count": 12,
                    "stateRevenue": {
                        "extensions": [],
                        "name": "stateRevenue",
                        "sum": 16821.209999999995
                    },
                    "apiAlias": "aggregation_bucket"
                },
                {
                    "key": "open",
                    "count": 19,
                    "stateRevenue": {
                        "extensions": [],
                        "name": "stateRevenue",
                        "sum": 25851.079999999994
                    },
                    "apiAlias": "aggregation_bucket"
                }
            ],
            "apiAlias": "byState_aggregation"
        }
    }
}
"""

class SwEntityTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun result(): SearchResult =
        SearchResult.from(json.parseToJsonElement(ORDER_SEARCH).jsonObject)

    private fun order(): SwEntity = result().data.single()

    @Test
    fun `envelope parsing exposes total, data and aggregations`() {
        val result = result()
        assertEquals(40, result.total)
        assertEquals(1, result.data.size)
        assertEquals("046d7824c16843b69a7c7becf49da54a", result.data[0].id)
        assertTrue("revenue" in result.aggregations)
    }

    @Test
    fun `scalar accessors read typed values`() {
        val order = order()
        assertEquals("11008", order.string("orderNumber"))
        assertEquals(2931.88, order.double("amountTotal")!!, 1e-9)
        assertEquals(8, order.int("autoIncrement"))
        assertEquals(8L, order.long("autoIncrement"))
        val lineItem = order.entities("lineItems").single()
        assertEquals(true, lineItem.boolean("good"))
        assertEquals(2, lineItem.int("quantity"))
    }

    @Test
    fun `JsonNull fields are absent`() {
        val order = order()
        assertNull(order.string("updatedAt"))
        assertNull(order.instant("updatedAt"))
        assertNull(order.string("deepLinkCode"))
        assertNull(order.entity("billingAddress"))
        assertNull(order.int("missingEntirely"))
    }

    @Test
    fun `translated reads resolved value when root field is absent`() {
        val state = order().entity("stateMachineState")!!
        assertNull(state.string("name"))
        assertEquals("Done", state.translated("name"))
    }

    @Test
    fun `translated falls back to root field when translated has no value`() {
        // order_line_item serializes "translated" as an empty array
        val lineItem = order().entities("lineItems").single()
        assertEquals("Main product with advanced prices", lineItem.translated("label"))
        // and both sides present agree
        assertEquals("Euro", order().entity("currency")!!.translated("name"))
    }

    @Test
    fun `instant parses offset and space-separated formats`() {
        val order = order()
        assertEquals(Instant.parse("2026-06-11T18:44:23Z"), order.instant("orderDateTime"))
        assertEquals(Instant.parse("2026-06-11T08:06:24.003Z"), order.instant("createdAt"))

        val spaceDates = SwEntity(
            json.parseToJsonElement(
                """{"bucketKey": "2026-06-10 00:00:00", "withMillis": "2026-06-11 08:21:33.000",
                    "dateOnly": "2026-06-11", "garbage": "not a date"}"""
            ).jsonObject
        )
        assertEquals(Instant.parse("2026-06-10T00:00:00Z"), spaceDates.instant("bucketKey"))
        assertEquals(Instant.parse("2026-06-11T08:21:33Z"), spaceDates.instant("withMillis"))
        assertEquals(Instant.parse("2026-06-11T00:00:00Z"), spaceDates.instant("dateOnly"))
        assertNull(spaceDates.instant("garbage"))
        assertNull(spaceDates.instant("bucketMissing"))
    }

    @Test
    fun `aggregation sum and count accessors`() {
        val result = result()
        assertEquals(52855.91000000001, result.sum("revenue"), 1e-9)
        assertEquals(40, result.count("orderCount"))
        assertEquals(0.0, result.sum("noSuchAggregation"), 0.0)
        assertEquals(0, result.count("noSuchAggregation"))
    }

    @Test
    fun `histogram buckets carry day keys and nested sums`() {
        val buckets = result().buckets("perDay")
        assertEquals(listOf("2026-06-10 00:00:00", "2026-06-11 00:00:00"), buckets.map { it.key })
        assertEquals(listOf(3, 8), buckets.map { it.count })
        assertEquals(179.94, buckets[0].sum("dayRevenue"), 1e-9)
        // unnamed lookup finds the first child object holding a "sum" key
        assertEquals(11453.44, buckets[1].sum(), 1e-9)
    }

    @Test
    fun `terms buckets carry state keys and nested sums`() {
        val buckets = result().buckets("byState")
        assertEquals(listOf("completed", "open"), buckets.map { it.key })
        assertEquals(12, buckets[0].count)
        assertEquals(16821.209999999995, buckets[0].sum("stateRevenue"), 1e-9)
        assertEquals(25851.079999999994, buckets[1].sum(), 1e-9)
        assertEquals(0.0, buckets[1].sum("noSuchNested"), 0.0)
        assertTrue(result().buckets("revenue").isEmpty())
    }

    @Test
    fun `nested buckets parse terms wrapping a histogram`() {
        // capture shape of terms(currencyFactor) → histogram(day) → sum (6.7.8) used for
        // currency-normalized revenue: per-factor buckets, divided client-side
        val envelope = """
        {
          "total": 0, "data": [],
          "aggregations": {
            "byFactor": {
              "name": "byFactor",
              "buckets": [
                {"key": "1", "count": 3, "daily": {"name": "daily", "buckets": [
                  {"key": "2026-06-12 00:00:00", "count": 3, "revenue": {"name": "revenue", "sum": 300.0}}
                ]}},
                {"key": "2", "count": 1, "daily": {"name": "daily", "buckets": [
                  {"key": "2026-06-12 00:00:00", "count": 1, "revenue": {"name": "revenue", "sum": 200.0}}
                ]}}
              ]
            }
          }
        }
        """
        val result = SearchResult.from(json.parseToJsonElement(envelope).jsonObject)
        val factors = result.buckets("byFactor")
        assertEquals(listOf("1", "2"), factors.map { it.key })

        val normalized = factors.sumOf { fb ->
            fb.buckets("daily").sumOf { it.sum("revenue") } / fb.key.toDouble()
        }
        assertEquals(400.0, normalized, 1e-9) // 300/1 + 200/2

        assertTrue(factors[0].buckets("noSuchAgg").isEmpty())
    }
}
