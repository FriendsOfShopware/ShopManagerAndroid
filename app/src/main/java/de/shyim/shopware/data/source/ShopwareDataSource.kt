package de.shyim.shopware.data.source

import de.shyim.shopware.api.Criteria
import de.shyim.shopware.api.SearchResult
import de.shyim.shopware.api.ShopApi
import de.shyim.shopware.api.SwEntity
import de.shyim.shopware.api.TotalCountMode
import de.shyim.shopware.data.model.ConnectedShop
import de.shyim.shopware.data.model.LowStockItem
import de.shyim.shopware.data.model.OrderDetail
import de.shyim.shopware.data.model.OrderDocument
import de.shyim.shopware.data.model.OrderLineItem
import de.shyim.shopware.data.model.OrderStateInfo
import de.shyim.shopware.data.model.ShopSnapshot
import de.shyim.shopware.data.model.TaxLine
import de.shyim.shopware.data.model.TopCustomer
import de.shyim.shopware.data.model.TopProduct
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class ShopwareDataSource(private val apiFor: (ConnectedShop) -> ShopApi) {

    suspend fun fetchSnapshot(shop: ConnectedShop, lowStockThreshold: Int): ShopSnapshot = coroutineScope {
        val api = apiFor(shop)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val weekDates = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val weekStartIso = weekDates.first().atStartOfDay(zone).toInstant().toString()
        val threeDaysAgoIso = today.minusDays(3).atStartOfDay(zone).toInstant().toString()

        // Sections whose entity the login cannot read (wizard ACL probes) are skipped
        // instead of failing the whole snapshot
        val empty = SearchResult(0, emptyList(), buildJsonObject {})
        val canOrders = shop.canRead("order")

        // Timezone-aware day buckets via the admin dashboard endpoint (what sw-dashboard uses);
        // falls back to a DAL histogram aggregation if unavailable.
        val daily = async {
            if (canOrders) fetchDailyStats(api, weekDates.first(), zone, weekStartIso) else emptyMap()
        }

        val openOrders = async {
            if (!canOrders) return@async 0
            api.repository("order").search(
                Criteria()
                    .setLimit(1)
                    .setTotalCountMode(TotalCountMode.Exact)
                    .addFilter(Criteria.equals("stateMachineState.technicalName", "open"))
            ).total
        }

        val unpaid = async {
            if (!canOrders) return@async 0
            api.repository("order").search(
                Criteria()
                    .setLimit(1)
                    .setTotalCountMode(TotalCountMode.Exact)
                    .addFilter(Criteria.equals("transactions.stateMachineState.technicalName", "open"))
                    .addFilter(Criteria.range("orderDateTime", lte = threeDaysAgoIso))
                    .addFilter(Criteria.not("and", Criteria.equals("stateMachineState.technicalName", "cancelled")))
            ).total
        }

        val lowStock = async {
            if (!shop.canRead("product")) return@async empty
            api.repository("product").search(
                Criteria()
                    .setLimit(5)
                    .setTotalCountMode(TotalCountMode.Exact)
                    .addFilter(Criteria.range("stock", lte = lowStockThreshold))
                    .addFilter(Criteria.equals("active", true))
                    .addSorting("stock")
                    .addIncludes("product", listOf("id", "name", "stock", "translated"))
            )
        }

        val recent = async {
            if (!canOrders) return@async empty
            api.repository("order").search(orderListCriteria().setLimit(8))
        }

        val customers = async {
            if (!shop.canRead("customer")) return@async empty
            api.repository("customer").search(customerListCriteria().setLimit(8))
        }

        val promotions = async {
            if (!shop.canRead("promotion")) return@async empty
            api.repository("promotion").search(promoCriteria().setLimit(20))
        }

        val products = async {
            if (!canOrders) return@async empty
            api.repository("order-line-item").search(
                Criteria()
                    .setLimit(1)
                    .setTotalCountMode(TotalCountMode.None)
                    .addFilter(Criteria.equals("type", "product"))
                    .addFilter(Criteria.range("order.orderDateTime", gte = weekStartIso))
                    .addAggregation(termsAgg("qty", "label", "q", "quantity"))
                    // line-item prices are in the order's currency — nest by factor to normalize
                    .addAggregation(
                        Criteria.terms(
                            "rev", "label",
                            limit = 10,
                            sort = Criteria.sort("_count", "DESC"),
                            aggregation = Criteria.terms(
                                "byFactor", "order.currencyFactor",
                                aggregation = Criteria.sum("r", "totalPrice"),
                            ),
                        )
                    )
            )
        }

        // Missing product_review ACL must not fail the whole snapshot
        val pendingReviews = async {
            if (!shop.canRead("product_review")) return@async 0
            try {
                api.repository("product-review").search(
                    Criteria()
                        .setLimit(1)
                        .setTotalCountMode(TotalCountMode.Exact)
                        .addFilter(Criteria.equals("status", false))
                ).total
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                0
            }
        }

        val statsByDate = daily.await()
        val isoDate = DateTimeFormatter.ISO_LOCAL_DATE
        val weekRevenue = weekDates.map { d -> statsByDate[d.format(isoDate)]?.second ?: 0.0 }
        val ordersToday = statsByDate[today.format(isoDate)]?.first ?: 0

        val lowStockResult = lowStock.await()
        val lowStockItems = lowStockResult.data.map {
            LowStockItem(it.translated("name") ?: "—", it.int("stock") ?: 0, it.id ?: "")
        }

        val now = System.currentTimeMillis()
        val recentOrders = recent.await().data.map { parseOrder(it, now) }

        val topCustomers = customers.await().data.mapNotNull { c ->
            val name = listOfNotNull(c.string("firstName"), c.string("lastName")).joinToString(" ")
            val spend = c.double("orderTotalAmount") ?: 0.0
            if (name.isBlank() || spend <= 0.0) null
            else TopCustomer(name, c.int("orderCount") ?: 0, spend)
        }

        val nowInstant = Instant.now()
        val promos = promotions.await().data.map { p ->
            parsePromo(p, nowInstant, shop)
        }

        val productsResult = products.await()
        val qtyByLabel = productsResult.buckets("qty").associateBy({ it.key }, { it.sum("q") })
        val revByLabel = productsResult.buckets("rev").associateBy({ it.key }) { label ->
            label.buckets("byFactor").sumOf { fb ->
                fb.sum("r") / (fb.key.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0)
            }
        }
        val topProducts = qtyByLabel.entries
            .filter { it.key.isNotBlank() }
            .sortedByDescending { it.value }
            .take(5)
            .map { TopProduct(it.key, it.value.toInt(), revByLabel[it.key] ?: 0.0) }

        ShopSnapshot(
            todayRevenue = weekRevenue.last(),
            yesterdayRevenue = weekRevenue[weekRevenue.size - 2],
            ordersToday = ordersToday,
            openOrders = openOrders.await(),
            unpaidOrders = unpaid.await(),
            lowStockCount = lowStockResult.total,
            lowStockItems = lowStockItems,
            weekRevenue = weekRevenue,
            todayIndex = 6,
            recentOrders = recentOrders,
            topCustomers = topCustomers,
            promos = promos,
            topProducts = topProducts,
            pendingReviews = pendingReviews.await(),
            lastSyncEpochMs = now,
        )
    }

    // date → (orderCount, revenue). Primary: /_admin/dashboard/order-amount (timezone-aware,
    // same source as sw-dashboard; includes cancelled orders, normalizes by currency factor).
    // The fallback histogram diverges: UTC-day buckets, excludes cancelled, sums raw amountTotal.
    private suspend fun fetchDailyStats(
        api: ShopApi,
        since: LocalDate,
        zone: ZoneId,
        weekStartIso: String,
    ): Map<String, Pair<Int, Double>> {
        try {
            return api.dashboard.orderAmount(since, zone.id, paid = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        // amountTotal is in the order's currency — bucket by currencyFactor and divide,
        // so mixed-currency days sum in the shop's default currency (like the dashboard
        // endpoint, which normalizes server-side; verified on 6.7.8)
        val result = api.repository("order").search(
            Criteria()
                .setLimit(1)
                .setTotalCountMode(TotalCountMode.None)
                .addFilter(Criteria.range("orderDateTime", gte = weekStartIso))
                .addFilter(Criteria.not("and", Criteria.equals("stateMachineState.technicalName", "cancelled")))
                .addAggregation(
                    Criteria.terms(
                        "byFactor", "currencyFactor",
                        aggregation = Criteria.histogram("daily", "orderDateTime", "day", Criteria.sum("revenue", "amountTotal")),
                    )
                )
        )
        val merged = mutableMapOf<String, Pair<Int, Double>>()
        result.buckets("byFactor").forEach { factorBucket ->
            val factor = factorBucket.key.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
            factorBucket.buckets("daily").forEach { day ->
                val date = day.key.take(10)
                val prev = merged[date] ?: (0 to 0.0)
                merged[date] = (prev.first + day.count) to (prev.second + day.sum("revenue") / factor)
            }
        }
        return merged
    }

    private fun termsAgg(name: String, field: String, nestedName: String, sumField: String) =
        Criteria.terms(
            name, field,
            limit = 10,
            sort = Criteria.sort("_count", "DESC"),
            aggregation = Criteria.sum(nestedName, sumField),
        )
}

// ── Orders: detail + state transitions ───────────────────────

suspend fun ShopApi.fetchOrderDetail(orderId: String): OrderDetail = coroutineScope {
    val o = repository("order").search(
        Criteria()
            .setLimit(1)
            .addFilter(Criteria.equals("id", orderId))
            .addAssociation("lineItems")
            .addAssociation("stateMachineState")
            .addAssociation("orderCustomer")
            .addAssociation("deliveries.stateMachineState")
            .addAssociation("deliveries.shippingMethod")
            .addAssociation("deliveries.shippingOrderAddress.country")
            .addAssociation("transactions.stateMachineState")
            .addAssociation("transactions.paymentMethod")
            .addAssociation("documents.documentType")
            .addAssociation("billingAddress.country")
            .addAssociation("currency")
    ).data.firstOrNull() ?: throw IllegalStateException("Order not found")

    val delivery = o.entities("deliveries").firstOrNull()
    val transaction = o.entities("transactions").lastOrNull()
    val cust = o.entity("orderCustomer")
    val billing = o.entity("billingAddress")
    val shippingAddr = delivery?.entity("shippingOrderAddress")

    val stateSources = listOfNotNull(
        Triple("order", orderId, o.entity("stateMachineState")),
        transaction?.let { Triple("order_transaction", it.id ?: "", it.entity("stateMachineState")) },
        delivery?.let { Triple("order_delivery", it.id ?: "", it.entity("stateMachineState")) },
    ).filter { it.second.isNotEmpty() }

    val states = stateSources.map { (entity, entityId, state) ->
        async {
            val transitions = runCatching { stateMachine.transitions(entity, entityId) }
                .getOrDefault(emptyList())
            OrderStateInfo(
                entity = entity,
                entityId = entityId,
                label = when (entity) {
                    "order_transaction" -> "Payment"
                    "order_delivery" -> "Delivery"
                    else -> "Order"
                },
                stateName = state?.translated("name") ?: "—",
                stateTechnical = state?.string("technicalName") ?: "open",
                transitions = transitions,
            )
        }
    }.map { it.await() }

    OrderDetail(
        id = orderId,
        orderNumber = o.string("orderNumber") ?: "—",
        orderDateTime = o.string("orderDateTime")?.take(16)?.replace("T", " ") ?: "",
        customerName = listOfNotNull(cust?.string("firstName"), cust?.string("lastName"))
            .joinToString(" ").ifBlank { "Guest" },
        customerEmail = cust?.string("email") ?: "",
        currencyIso = o.entity("currency")?.string("isoCode"),
        amountTotal = o.double("amountTotal") ?: 0.0,
        shippingTotal = o.double("shippingTotal") ?: 0.0,
        netTotal = o.entity("price")?.double("netPrice") ?: 0.0,
        taxes = o.entity("price")?.entities("calculatedTaxes")
            ?.mapNotNull { t ->
                val rate = t.double("taxRate") ?: return@mapNotNull null
                val amount = t.double("tax") ?: return@mapNotNull null
                TaxLine(rate, amount).takeIf { amount > 0 }
            } ?: emptyList(),
        paymentMethod = transaction?.entity("paymentMethod")?.let { it.translated("name") ?: "—" },
        shippingMethod = delivery?.entity("shippingMethod")?.let { it.translated("name") ?: "—" },
        deliveryId = delivery?.id,
        trackingCodes = (delivery?.json?.get("trackingCodes") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.content }
            ?: emptyList(),
        billingAddress = billing?.let(::formatAddress),
        shippingAddress = shippingAddr?.let(::formatAddress),
        phone = shippingAddr?.string("phoneNumber") ?: billing?.string("phoneNumber"),
        customerComment = o.string("customerComment")?.takeIf { it.isNotBlank() },
        internalComment = o.string("internalComment")?.takeIf { it.isNotBlank() },
        lineItems = o.entities("lineItems")
            .sortedBy { it.int("position") ?: 0 }
            .map {
                OrderLineItem(
                    label = it.string("label") ?: "—",
                    quantity = it.int("quantity") ?: 1,
                    totalPrice = it.double("totalPrice") ?: 0.0,
                    unitPrice = it.double("unitPrice") ?: 0.0,
                    productNumber = it.entity("payload")?.string("productNumber"),
                )
            },
        states = states,
        documents = o.entities("documents").mapNotNull { d ->
            OrderDocument(
                id = d.id ?: return@mapNotNull null,
                deepLinkCode = d.string("deepLinkCode") ?: return@mapNotNull null,
                typeName = d.entity("documentType")?.let { it.translated("name") ?: it.string("name") } ?: "Document",
                typeTechnical = d.entity("documentType")?.string("technicalName") ?: "",
                number = d.string("documentNumber") ?: "",
            )
        },
    )
}

internal fun formatAddress(a: SwEntity): String? {
    val lines = listOf(
        listOfNotNull(a.string("firstName"), a.string("lastName")).joinToString(" "),
        a.string("street") ?: "",
        listOfNotNull(a.string("zipcode"), a.string("city")).joinToString(" "),
        a.entity("country")?.translated("name") ?: "",
    ).filter { it.isNotBlank() }
    return lines.joinToString("\n").takeIf { it.isNotBlank() }
}

suspend fun ShopApi.setInternalComment(orderId: String, comment: String?) {
    repository("order").patch(orderId, buildJsonObject {
        put("internalComment", comment?.takeIf { it.isNotBlank() })
    })
}

suspend fun ShopApi.setTrackingCodes(deliveryId: String, codes: List<String>) {
    repository("order-delivery").patch(deliveryId, buildJsonObject {
        put("trackingCodes", JsonArray(codes.map { JsonPrimitive(it) }))
    })
}
