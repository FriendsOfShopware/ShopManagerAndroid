package de.shyim.shopware.data.source

import de.shyim.shopware.api.Criteria
import de.shyim.shopware.api.ShopApi
import de.shyim.shopware.api.SwEntity
import de.shyim.shopware.data.model.OrderTimelineEntry
import de.shyim.shopware.data.model.RecentOrder

// Shared by the snapshot (ShopwareDataSource) and the live orders listing
fun orderListCriteria(): Criteria = Criteria()
    .addSorting("orderDateTime", "DESC")
    .addAssociation("stateMachineState")
    .addAssociation("orderCustomer")
    .addAssociation("currency")
    .addIncludes(
        "order",
        listOf("id", "orderNumber", "amountTotal", "orderDateTime", "stateMachineState", "orderCustomer", "currency"),
    )
    .addIncludes("state_machine_state", listOf("name", "technicalName"))
    .addIncludes("order_customer", listOf("firstName", "lastName"))
    .addIncludes("currency", listOf("isoCode"))

fun parseOrder(o: SwEntity, now: Long): RecentOrder {
    val state = o.entity("stateMachineState")
    val cust = o.entity("orderCustomer")
    return RecentOrder(
        id = o.id ?: "",
        orderNumber = o.string("orderNumber") ?: "—",
        customer = listOfNotNull(cust?.string("firstName"), cust?.string("lastName"))
            .joinToString(" ").ifBlank { "Guest" },
        state = state?.let { it.translated("name") ?: "—" } ?: "Open",
        stateTechnical = state?.string("technicalName") ?: "open",
        amount = o.double("amountTotal") ?: 0.0,
        currencyIso = o.entity("currency")?.string("isoCode"),
        placedMs = o.instant("orderDateTime")?.toEpochMilli() ?: now,
    )
}

fun customerListCriteria(): Criteria = Criteria()
    .addSorting("orderTotalAmount", "DESC")
    .addIncludes("customer", listOf("id", "firstName", "lastName", "orderCount", "orderTotalAmount"))

// Chronological state history across an order and its payment/delivery records.
// `referencedIds` are the order id + transaction id + delivery id (from OrderDetail.states).
suspend fun ShopApi.fetchOrderTimeline(referencedIds: List<String>): List<OrderTimelineEntry> {
    if (referencedIds.isEmpty()) return emptyList()
    val rows = repository("state-machine-history").search(
        Criteria()
            .setLimit(100)
            .addFilter(Criteria.equalsAny("referencedId", referencedIds))
            .addSorting("createdAt", "ASC")
            .addAssociation("toStateMachineState")
            .addAssociation("user")
            .addIncludes("state_machine_history", listOf("entityName", "createdAt", "toStateMachineState", "user"))
            .addIncludes("state_machine_state", listOf("name", "translated", "technicalName"))
            .addIncludes("user", listOf("firstName", "lastName", "username"))
    ).data

    return rows.mapNotNull { h ->
        val to = h.entity("toStateMachineState") ?: return@mapNotNull null
        val created = h.instant("createdAt")?.toEpochMilli() ?: return@mapNotNull null
        val user = h.entity("user")
        val userLabel = user?.let {
            listOfNotNull(it.string("firstName"), it.string("lastName"))
                .joinToString(" ").ifBlank { it.string("username") }
        }
        OrderTimelineEntry(
            entity = h.string("entityName") ?: "order",
            toStateName = to.translated("name") ?: to.string("technicalName") ?: "—",
            toStateTechnical = to.string("technicalName") ?: "",
            userLabel = userLabel,
            createdAtMs = created,
        )
    }
}
