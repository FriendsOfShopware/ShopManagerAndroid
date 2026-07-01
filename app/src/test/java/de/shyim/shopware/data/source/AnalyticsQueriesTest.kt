package de.shyim.shopware.data.source

import de.shyim.shopware.api.ApiContext
import de.shyim.shopware.api.PlainAuth
import de.shyim.shopware.api.ShopApi
import de.shyim.shopware.api.ShopwareClient
import de.shyim.shopware.api.ShopwareHttp
import de.shyim.shopware.data.model.AnalyticsFilters
import de.shyim.shopware.data.model.DateRange
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Tests the analytics aggregation parsing — especially the multi-currency normalization (divide
// each currencyFactor bucket's sum by the factor key) — against canned API responses, with no
// server. Built on the ShopApi.withClient test seam + ktor's MockEngine.
class AnalyticsQueriesTest {

    // Capture the last non-auth request body so tests can assert the criteria we send.
    private class Captured { var lastBody: String? = null }

    // A ShopApi that auto-grants a token, then answers every search with `responder()`.
    private fun api(captured: Captured = Captured(), responder: () -> String): ShopApi {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            if (path.endsWith("/oauth/token")) {
                respond(
                    """{"access_token":"tok","token_type":"Bearer","expires_in":600,"refresh_token":"r1"}""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                captured.lastBody = (request.body as? io.ktor.http.content.TextContent)?.text ?: ""
                respond(responder(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(ShopwareHttp.json) }
            expectSuccess = false
        }
        val client = ShopwareClient("https://shop.test", PlainAuth.RefreshToken("r0"), ApiContext(), http)
        return ShopApi.withClient(client)
    }

    private fun last7(): AnalyticsFilters {
        val to = 7L * 86_400_000
        return AnalyticsFilters(dateRange = DateRange(DateRange.Preset.Last7, fromMs = 0, toMs = to))
    }

    private fun rangeFilters(preset: DateRange.Preset, fromMs: Long, toMs: Long) =
        AnalyticsFilters(dateRange = DateRange(preset, fromMs = fromMs, toMs = toMs))

    @Test
    fun totalSales_normalizesMultipleCurrencies() = runBlocking {
        // factor=1 has €100 + €50 on two days; factor=2 (a 2x-rate currency) has 240 → 120 normalized.
        // Expected total = 100 + 50 + 120 = 270.
        val body = """
        {"total":0,"data":[],"aggregations":{"byFactor":{"buckets":[
          {"key":"1","daily":{"buckets":[
            {"key":"2026-06-10 00:00:00","rev":{"sum":100.0}},
            {"key":"2026-06-11 00:00:00","rev":{"sum":50.0}}
          ]}},
          {"key":"2","daily":{"buckets":[
            {"key":"2026-06-10 00:00:00","rev":{"sum":240.0}}
          ]}}
        ]}}}
        """.trimIndent()
        val kpi = api { body }.analyticsTotalSales(last7())
        assertEquals(270.0, kpi.total, 0.001)
        assertTrue(kpi.isMoney)
        // two distinct days → two merged points (the factor-2 day coincides with day 1)
        assertEquals(2, kpi.points.size)
        assertEquals(220.0, kpi.points.first().value, 0.001) // 100 + 240/2
    }

    @Test
    fun orderCount_sumsHistogramBuckets() = runBlocking {
        // histogram buckets carry a native `count` (the document count per bucket)
        val body = """
        {"total":0,"data":[],"aggregations":{"daily":{"buckets":[
          {"key":"2026-06-10 00:00:00","count":3},
          {"key":"2026-06-11 00:00:00","count":14}
        ]}}}
        """.trimIndent()
        val kpi = api { body }.analyticsOrderCount(last7())
        assertEquals(17.0, kpi.total, 0.001)
        assertEquals(2, kpi.points.size)
        assertTrue(!kpi.isMoney)
    }

    @Test
    fun salesChannel_resolvesNamesAndNormalizes() = runBlocking {
        // first call: the order aggregation; second call: the sales-channel name lookup
        var call = 0
        val agg = """
        {"total":0,"data":[],"aggregations":{"sc":{"buckets":[
          {"key":"scA","byFactor":{"buckets":[{"key":"1","rev":{"sum":900.0}}]}},
          {"key":"scB","byFactor":{"buckets":[{"key":"2","rev":{"sum":200.0}}]}}
        ]}}}
        """.trimIndent()
        // /api/search with Accept: application/json returns flat entities (no JSON:API attributes)
        val names = """{"total":2,"data":[
          {"id":"scA","name":"Storefront","translated":{"name":"Storefront"}},
          {"id":"scB","name":"Mobile","translated":{"name":"Mobile"}}
        ],"aggregations":{}}"""
        val kpi = api { if (call++ == 0) agg else names }.analyticsSalesChannel(last7())
        assertTrue(kpi.valueIsMoney)
        // scA = 900, scB = 200/2 = 100 → sorted desc
        assertEquals(2, kpi.rows.size)
        assertEquals("Storefront", kpi.rows[0].label)
        assertEquals(900.0, kpi.rows[0].value, 0.001)
        assertEquals("Mobile", kpi.rows[1].label)
        assertEquals(100.0, kpi.rows[1].value, 0.001)
    }

    @Test
    fun totalSales_sendsCurrencyFactorAggregationAndDateRange() = runBlocking {
        val captured = Captured()
        api(captured) { """{"total":0,"data":[],"aggregations":{}}""" }.analyticsTotalSales(last7())
        val sent = captured.lastBody ?: ""
        // the criteria must carry the byFactor→histogram→sum aggregation and an orderDate range
        assertTrue("byFactor in body", sent.contains("currencyFactor"))
        assertTrue("histogram in body", sent.contains("\"type\":\"histogram\""))
        assertTrue("amountTotal sum", sent.contains("amountTotal"))
        assertTrue("orderDate range", sent.contains("orderDate"))
    }

    @Test
    fun revenueTotal_normalizesMultipleCurrencies() = runBlocking {
        // factor=1 → €900, factor=2 → 200/2 = 100. Expected total = 1000 (cross-shop comparison).
        val body = """
        {"total":0,"data":[],"aggregations":{"byFactor":{"buckets":[
          {"key":"1","rev":{"sum":900.0}},
          {"key":"2","rev":{"sum":200.0}}
        ]}}}
        """.trimIndent()
        val total = api { body }.analyticsRevenueTotal(last7())
        assertEquals(1000.0, total, 0.001)
    }

    @Test
    fun revenueTotal_sendsSelectedDateRange() = runBlocking {
        // The cross-shop comparison bug was that it ignored the period. Prove the query carries
        // the selected range: two different ranges must send two different orderDate filters.
        val cap30 = Captured()
        api(cap30) { """{"total":0,"data":[],"aggregations":{}}""" }
            .analyticsRevenueTotal(rangeFilters(DateRange.Preset.Last30, fromMs = 1_000_000, toMs = 31_000_000))
        val cap90 = Captured()
        api(cap90) { """{"total":0,"data":[],"aggregations":{}}""" }
            .analyticsRevenueTotal(rangeFilters(DateRange.Preset.Last90, fromMs = 1_000_000, toMs = 91_000_000))

        val b30 = cap30.lastBody ?: ""
        val b90 = cap90.lastBody ?: ""
        assertTrue("range filter present", b30.contains("orderDate"))
        assertTrue("currencyFactor sum aggregation", b30.contains("currencyFactor") && b30.contains("amountTotal"))
        // different upper bounds → different request bodies (it tracks the period, not a fixed week)
        assertTrue("two ranges differ", b30 != b90)
    }

    @Test
    fun customerCount_usesExactTotalCount() = runBlocking {
        // both calls (current + previous window) return the same total shape
        val body = """{"total":42,"data":[],"aggregations":{}}"""
        val kpi = api { body }.analyticsCustomerCount(last7())
        assertEquals(42.0, kpi.value, 0.001)
        assertEquals(42.0, kpi.previousValue) // previous window also returns 42 here
    }
}
