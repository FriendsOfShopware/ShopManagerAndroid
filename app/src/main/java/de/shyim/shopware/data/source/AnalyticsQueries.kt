package de.shyim.shopware.data.source

import de.shyim.shopware.api.Bucket
import de.shyim.shopware.api.Criteria
import de.shyim.shopware.api.ShopApi
import de.shyim.shopware.api.TotalCountMode
import de.shyim.shopware.api.runCatchingCancellable
import de.shyim.shopware.data.model.AnalyticsFilters
import de.shyim.shopware.data.model.BreakdownKpi
import de.shyim.shopware.data.model.BreakdownRow
import de.shyim.shopware.data.model.SingleKpi
import de.shyim.shopware.data.model.TimePoint
import de.shyim.shopware.data.model.TimeSeriesKpi
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.format.DateTimeFormatter

// Analytics aggregations for the Reports tab — modeled on SwagAnalytics' KPI queries.
// Kept separate from ShopwareDataSource (the Home snapshot), per the "snapshot = Home only" rule.
// Money KPIs normalize multi-currency by nesting sums under terms(currencyFactor) and dividing by
// the bucket key — the same idiom the dashboard already uses.

private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
private fun ms(epochMs: Long): String = ISO.format(Instant.ofEpochMilli(epochMs))

// The shared order filter list (date range + every active filter), the single point that lets the
// filter bar drive all order-based cards. Mirrors SwagAnalytics kpi/common/order.criteria.ts.
private fun AnalyticsFilters.orderFilters(): List<JsonObject> = buildList {
    add(Criteria.range("orderDate", gte = ms(dateRange.fromMs), lt = ms(dateRange.toMs)))
    if (salesChannelIds.isNotEmpty()) add(Criteria.equalsAny("salesChannelId", salesChannelIds))
    if (orderStateIds.isNotEmpty()) add(Criteria.equalsAny("stateMachineState.technicalName", orderStateIds))
    if (paymentStateIds.isNotEmpty()) add(Criteria.equalsAny("transactions.stateMachineState.technicalName", paymentStateIds))
    if (deliveryStateIds.isNotEmpty()) add(Criteria.equalsAny("deliveries.stateMachineState.technicalName", deliveryStateIds))
    if (customerGroupIds.isNotEmpty()) add(Criteria.equalsAny("orderCustomer.customer.groupId", customerGroupIds))
    if (countryIds.isNotEmpty()) add(Criteria.equalsAny("billingAddress.countryId", countryIds))
}

// The same window length, shifted back one period — for the delta badge.
private fun AnalyticsFilters.previous(): AnalyticsFilters {
    val len = dateRange.toMs - dateRange.fromMs
    return copy(dateRange = dateRange.copy(fromMs = dateRange.fromMs - len, toMs = dateRange.fromMs))
}

private fun Criteria.withOrderFilters(f: AnalyticsFilters) = apply { f.orderFilters().forEach { addFilter(it) } }

// terms(currencyFactor) → <metric>; divide each bucket's value by the factor (its key) and sum.
private fun List<Bucket>.normalizedSum(): Double = sumOf { factorBucket ->
    val factor = factorBucket.key.toDoubleOrNull()?.takeIf { it != 0.0 } ?: 1.0
    factorBucket.sum() / factor
}

// ---- Time-series KPIs ----

suspend fun ShopApi.analyticsTotalSales(f: AnalyticsFilters): TimeSeriesKpi {
    val points = salesPoints(f)
    val prev = salesPoints(f.previous()).sumOf { it.value }
    return TimeSeriesKpi(points, points.sumOf { it.value }, prev, isMoney = true)
}

private suspend fun ShopApi.salesPoints(f: AnalyticsFilters): List<TimePoint> {
    val res = repository("order").search(
        Criteria().withOrderFilters(f).setLimit(1).setTotalCountMode(TotalCountMode.None)
            .addAggregation(
                Criteria.terms(
                    "byFactor", "currencyFactor",
                    aggregation = Criteria.histogram(
                        "daily", "orderDateTime", f.dateRange.interval.apiValue,
                        Criteria.sum("rev", "amountTotal"),
                    ),
                )
            )
    )
    // date → summed-across-currencies revenue
    val byDate = sortedMapOf<Long, Double>()
    res.buckets("byFactor").forEach { factorBucket ->
        val factor = factorBucket.key.toDoubleOrNull()?.takeIf { it != 0.0 } ?: 1.0
        factorBucket.buckets("daily").forEach { day ->
            val t = parseBucketDate(day.key)
            byDate[t] = (byDate[t] ?: 0.0) + day.sum("rev") / factor
        }
    }
    return byDate.map { TimePoint(it.key, it.value) }
}

// Single normalized revenue total for the filtered range — no histogram, used by the
// cross-shop comparison so it tracks the selected period (not the fixed Home snapshot week).
suspend fun ShopApi.analyticsRevenueTotal(f: AnalyticsFilters): Double {
    val res = repository("order").search(
        Criteria().withOrderFilters(f).setLimit(1).setTotalCountMode(TotalCountMode.None)
            .addAggregation(Criteria.terms("byFactor", "currencyFactor", aggregation = Criteria.sum("rev", "amountTotal")))
    )
    return res.buckets("byFactor").normalizedSum()
}

suspend fun ShopApi.analyticsOrderCount(f: AnalyticsFilters): TimeSeriesKpi {
    val points = orderCountPoints(f)
    val prev = orderCountPoints(f.previous()).sumOf { it.value }
    return TimeSeriesKpi(points, points.sumOf { it.value }, prev, isMoney = false)
}

private suspend fun ShopApi.orderCountPoints(f: AnalyticsFilters): List<TimePoint> {
    // The histogram bucket carries a native `count` (document count per bucket), so no nested
    // count aggregation is needed.
    val res = repository("order").search(
        Criteria().withOrderFilters(f).setLimit(1).setTotalCountMode(TotalCountMode.None)
            .addAggregation(Criteria.histogram("daily", "orderDateTime", f.dateRange.interval.apiValue))
    )
    return res.buckets("daily").map { TimePoint(parseBucketDate(it.key), it.count.toDouble()) }
}

suspend fun ShopApi.analyticsAvgOrderValue(f: AnalyticsFilters): TimeSeriesKpi {
    val points = avgPoints(f)
    val prevPts = avgPoints(f.previous())
    val avgOf = { pts: List<TimePoint> -> pts.map { it.value }.filter { it > 0 }.average().takeIf { !it.isNaN() } ?: 0.0 }
    return TimeSeriesKpi(points, avgOf(points), avgOf(prevPts), isMoney = true)
}

// Per-bucket AOV = revenue / order-count for that bucket.
private suspend fun ShopApi.avgPoints(f: AnalyticsFilters): List<TimePoint> {
    val res = repository("order").search(
        Criteria().withOrderFilters(f).setLimit(1).setTotalCountMode(TotalCountMode.None)
            .addAggregation(
                Criteria.terms(
                    "byFactor", "currencyFactor",
                    aggregation = Criteria.histogram(
                        "daily", "orderDateTime", f.dateRange.interval.apiValue, Criteria.sum("rev", "amountTotal"),
                    ),
                )
            )
            .addAggregation(
                Criteria.histogram("counts", "orderDateTime", f.dateRange.interval.apiValue)
            )
    )
    val revByDate = sortedMapOf<Long, Double>()
    res.buckets("byFactor").forEach { fb ->
        val factor = fb.key.toDoubleOrNull()?.takeIf { it != 0.0 } ?: 1.0
        fb.buckets("daily").forEach { d -> revByDate[parseBucketDate(d.key)] = (revByDate[parseBucketDate(d.key)] ?: 0.0) + d.sum("rev") / factor }
    }
    val countByDate = res.buckets("counts").associate { parseBucketDate(it.key) to it.count }
    return revByDate.map { (t, rev) ->
        val c = countByDate[t] ?: 0
        TimePoint(t, if (c > 0) rev / c else 0.0)
    }
}

suspend fun ShopApi.analyticsNewCustomers(f: AnalyticsFilters): TimeSeriesKpi {
    val points = newCustomerPoints(f)
    val prev = newCustomerPoints(f.previous()).sumOf { it.value }
    return TimeSeriesKpi(points, points.sumOf { it.value }, prev, isMoney = false)
}

private suspend fun ShopApi.newCustomerPoints(f: AnalyticsFilters): List<TimePoint> {
    val res = repository("customer").search(
        Criteria().setLimit(1).setTotalCountMode(TotalCountMode.None)
            .addFilter(Criteria.range("createdAt", gte = ms(f.dateRange.fromMs), lt = ms(f.dateRange.toMs)))
            .apply { if (f.salesChannelIds.isNotEmpty()) addFilter(Criteria.equalsAny("salesChannelId", f.salesChannelIds)) }
            .addAggregation(Criteria.histogram("daily", "createdAt", f.dateRange.interval.apiValue))
    )
    return res.buckets("daily").map { TimePoint(parseBucketDate(it.key), it.count.toDouble()) }
}

// ---- Breakdown KPIs ----

suspend fun ShopApi.analyticsSalesChannel(f: AnalyticsFilters): BreakdownKpi {
    val res = repository("order").search(
        Criteria().withOrderFilters(f).setLimit(1).setTotalCountMode(TotalCountMode.None)
            .addAggregation(
                Criteria.terms(
                    "sc", "salesChannelId", limit = 20,
                    aggregation = Criteria.terms("byFactor", "currencyFactor", aggregation = Criteria.sum("rev", "amountTotal")),
                )
            )
    )
    val names = resolveNames("sales-channel", res.buckets("sc").map { it.key })
    val rows = res.buckets("sc").map { b ->
        BreakdownRow(b.key, names[b.key] ?: "—", b.buckets("byFactor").normalizedSum())
    }.sortedByDescending { it.value }
    return BreakdownKpi(rows, valueIsMoney = true)
}

suspend fun ShopApi.analyticsPaymentMethod(f: AnalyticsFilters): BreakdownKpi =
    orderTermsBreakdown(f, "transactions.paymentMethodId", "payment-method")

suspend fun ShopApi.analyticsShippingMethod(f: AnalyticsFilters): BreakdownKpi =
    orderTermsBreakdown(f, "deliveries.shippingMethodId", "shipping-method")

suspend fun ShopApi.analyticsCountry(f: AnalyticsFilters): BreakdownKpi =
    orderTermsBreakdown(f, "billingAddress.countryId", "country")

// Count of orders grouped by a dimension id, with names resolved from `entity`.
private suspend fun ShopApi.orderTermsBreakdown(f: AnalyticsFilters, field: String, entity: String): BreakdownKpi {
    val res = repository("order").search(
        Criteria().withOrderFilters(f).setLimit(1).setTotalCountMode(TotalCountMode.None)
            .addAggregation(Criteria.terms("t", field, limit = 20))
    )
    val names = resolveNames(entity, res.buckets("t").map { it.key })
    val rows = res.buckets("t").map { BreakdownRow(it.key, names[it.key] ?: "—", it.count.toDouble()) }
        .sortedByDescending { it.value }
    return BreakdownKpi(rows, valueIsMoney = false)
}

suspend fun ShopApi.analyticsBestSelling(f: AnalyticsFilters): BreakdownKpi =
    lineItemQuantityBreakdown(f, "productId", "product")

suspend fun ShopApi.analyticsManufacturer(f: AnalyticsFilters): BreakdownKpi =
    lineItemQuantityBreakdown(f, "product.manufacturerId", "product-manufacturer")

// Sum of sold quantity grouped by a product dimension, joined through order filters.
private suspend fun ShopApi.lineItemQuantityBreakdown(f: AnalyticsFilters, field: String, entity: String): BreakdownKpi {
    val crit = Criteria().setLimit(1).setTotalCountMode(TotalCountMode.None)
        .addFilter(Criteria.equals("type", "product"))
        .addAggregation(Criteria.terms("t", field, limit = 10, aggregation = Criteria.sum("qty", "quantity")))
    // line-item join to the order filters (date + filters live on the parent order)
    f.orderFilters().forEach { crit.addFilter(prefixOrder(it)) }
    val res = repository("order-line-item").search(crit)
    val names = resolveNames(entity, res.buckets("t").map { it.key })
    val rows = res.buckets("t").map { BreakdownRow(it.key, names[it.key] ?: "—", it.sum("qty")) }
        .sortedByDescending { it.value }
    return BreakdownKpi(rows, valueIsMoney = false)
}

suspend fun ShopApi.analyticsPromotionCode(f: AnalyticsFilters): BreakdownKpi {
    // promotion line items carry the human-readable label; group by label (referencedId is null)
    val crit = Criteria().setLimit(1).setTotalCountMode(TotalCountMode.None)
        .addFilter(Criteria.equals("type", "promotion"))
        .addAggregation(Criteria.terms("t", "label", limit = 15))
    f.orderFilters().forEach { crit.addFilter(prefixOrder(it)) }
    val res = repository("order-line-item").search(crit)
    val rows = res.buckets("t").map { BreakdownRow(it.key, it.key.ifBlank { "—" }, it.count.toDouble()) }
        .sortedByDescending { it.value }
    return BreakdownKpi(rows, valueIsMoney = false)
}

// ---- Single-number KPI ----

suspend fun ShopApi.analyticsCustomerCount(f: AnalyticsFilters): SingleKpi {
    fun crit(range: AnalyticsFilters) = Criteria().setLimit(1).setTotalCountMode(TotalCountMode.Exact)
        .addFilter(Criteria.range("createdAt", lt = ms(range.dateRange.toMs)))
        .apply { if (range.salesChannelIds.isNotEmpty()) addFilter(Criteria.equalsAny("salesChannelId", range.salesChannelIds)) }
    val now = repository("customer").search(crit(f)).total.toDouble()
    val before = repository("customer").search(crit(f.previous())).total.toDouble()
    return SingleKpi(now, before, isMoney = false)
}

// ---- Helpers ----

// Re-target an order filter onto the order_line_item entity by prefixing field paths with "order.".
// salesChannelId/orderDate/etc. live on the parent order from the line-item perspective.
private fun prefixOrder(filter: JsonObject): JsonObject {
    val field = (filter["field"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return filter
    return JsonObject(filter.toMutableMap().apply {
        put("field", kotlinx.serialization.json.JsonPrimitive("order.$field"))
    })
}

// id → translated("name") for a set of ids. One follow-up fetch per breakdown. Degrades to "—".
private suspend fun ShopApi.resolveNames(entity: String, ids: List<String>): Map<String, String> {
    val real = ids.filter { it.isNotBlank() }
    if (real.isEmpty()) return emptyMap()
    return runCatchingCancellable {
        repository(entity).search(
            Criteria().setIds(real).setLimit(real.size)
                .addIncludes(entity.replace('-', '_'), listOf("id", "name", "translated"))
        ).data.mapNotNull { e -> e.id?.let { it to (e.translated("name") ?: "—") } }.toMap()
    }.getOrDefault(emptyMap())
}

private fun parseBucketDate(key: String): Long =
    // histogram bucket keys come as "2026-06-12 00:00:00" (local-ish) or ISO; parse leniently
    runCatching { Instant.parse(key.replace(' ', 'T').let { if (it.endsWith("Z") || it.contains('+')) it else it + "Z" }) }
        .getOrNull()?.toEpochMilli() ?: 0L

// ---- Filter-bar options ----

data class FilterOptionItem(val id: String, val label: String)

// states keyed by which order-relation they filter (technicalName is the filter value)
data class AnalyticsFilterOptions(
    val salesChannels: List<FilterOptionItem>,
    val customerGroups: List<FilterOptionItem>,
    val countries: List<FilterOptionItem>,
    val orderStates: List<FilterOptionItem>,       // stateMachine = order.state
    val paymentStates: List<FilterOptionItem>,     // order_transaction.state
    val deliveryStates: List<FilterOptionItem>,    // order_delivery.state
)

suspend fun ShopApi.fetchAnalyticsFilterOptions(): AnalyticsFilterOptions {
    suspend fun simple(entity: String, sortField: String): List<FilterOptionItem> = runCatchingCancellable {
        repository(entity).search(
            Criteria().setLimit(100).addSorting(sortField)
                .addIncludes(entity.replace('-', '_'), listOf("id", "name", "translated"))
        ).data.mapNotNull { e -> e.id?.let { FilterOptionItem(it, e.translated("name") ?: "—") } }
    }.getOrDefault(emptyList())

    // state-machine states use technicalName as the filter value; label is the translated name
    suspend fun states(machine: String): List<FilterOptionItem> = runCatchingCancellable {
        repository("state-machine-state").search(
            Criteria().setLimit(50)
                .addFilter(Criteria.equals("stateMachine.technicalName", machine))
                .addIncludes("state_machine_state", listOf("technicalName", "name", "translated"))
        ).data.mapNotNull { s ->
            s.string("technicalName")?.let { FilterOptionItem(it, s.translated("name") ?: it) }
        }
    }.getOrDefault(emptyList())

    return AnalyticsFilterOptions(
        salesChannels = simple("sales-channel", "name"),
        customerGroups = simple("customer-group", "name"),
        countries = simple("country", "name"),
        orderStates = states("order.state"),
        paymentStates = states("order_transaction.state"),
        deliveryStates = states("order_delivery.state"),
    )
}
