package de.shyim.shopware.data.model

import de.shyim.shopware.api.StateTransition

// Live order detail — fetched on demand, not persisted

data class OrderLineItem(
    val label: String,
    val quantity: Int,
    val totalPrice: Double,
    val unitPrice: Double = 0.0,
    val productNumber: String? = null,
)

data class TaxLine(val rate: Double, val amount: Double)

data class OrderStateInfo(
    val entity: String, // order | order_transaction | order_delivery
    val entityId: String,
    val label: String, // Order / Payment / Delivery
    val stateName: String,
    val stateTechnical: String,
    val transitions: List<StateTransition>,
)

data class OrderDocument(
    val id: String,
    val deepLinkCode: String,
    val typeName: String,
    val typeTechnical: String,
    val number: String,
)

// One state_machine_history row — a transition on the order, its payment, or its delivery
data class OrderTimelineEntry(
    val entity: String, // order | order_transaction | order_delivery
    val toStateName: String, // translated target state ("Paid", "In Progress", …)
    val toStateTechnical: String,
    val userLabel: String?, // admin who triggered it; null = system/automatic
    val createdAtMs: Long,
)

data class OrderDetail(
    val id: String,
    val orderNumber: String,
    val orderDateTime: String,
    val customerName: String,
    val customerEmail: String,
    val currencyIso: String?, // order currency; null = shop default
    val amountTotal: Double,
    val shippingTotal: Double,
    val netTotal: Double,
    val taxes: List<TaxLine>,
    val paymentMethod: String?,
    val shippingMethod: String?,
    val deliveryId: String?,
    val trackingCodes: List<String>,
    val billingAddress: String?,
    val shippingAddress: String?,
    val phone: String?,
    val customerComment: String?,
    val internalComment: String?,
    val lineItems: List<OrderLineItem>,
    val states: List<OrderStateInfo>,
    val documents: List<OrderDocument> = emptyList(),
)
