package de.shyim.shopware.data.model

import kotlinx.serialization.json.JsonArray

// Live product info for the quick-action sheet — fetched on demand, not persisted
data class ProductQuickInfo(
    val id: String,
    val name: String,
    val productNumber: String,
    val stock: Int,
    val active: Boolean,
    val grossPrice: Double?,
    val priceEditable: Boolean, // false for variant children / advanced (rule) prices
    val rawPrice: JsonArray?,
)

// A row in the products listing — fetched live through the listing pager
data class ProductRow(
    val id: String,
    val name: String,
    val productNumber: String,
    val active: Boolean,
    val stock: Int,
    val grossPrice: Double?,
    val manufacturer: String?,
    val coverUrl: String?, // rebased onto the shop's base URL
)

// Rich product detail — fetched on demand for the detail screen, not persisted
data class ProductDetail(
    val id: String,
    val name: String,
    val productNumber: String,
    val description: String?,
    val active: Boolean,
    val stock: Int,
    val availableStock: Int,
    val grossPrice: Double?,
    val netPrice: Double?,
    val priceLinked: Boolean, // gross/net kept in sync via the tax rate
    val priceEditable: Boolean, // false for advanced (rule) prices
    val rawPrice: JsonArray?,
    val taxRate: Double?,
    val manufacturer: String?,
    val ean: String?, // EAN / GTIN barcode
    val manufacturerNumber: String?, // MPN — the manufacturer's own part number
    val categories: List<String>,
    val salesChannels: List<String>, // sales channels this product is visible in
    val coverUrl: String?,
    val galleryUrls: List<String>, // all product media, rebased
    val ratingAverage: Double?,
    val childCount: Int,
    val releaseDateMs: Long?,
)

// A variant child of a configurable product — editable stock/price
data class ProductVariant(
    val id: String,
    val productNumber: String,
    val optionLabels: List<String>, // e.g. ["Blue", "M"]
    val active: Boolean,
    val stock: Int,
    val grossPrice: Double?,
    val netPrice: Double?,
    val priceLinked: Boolean, // gross/net kept in sync via the tax rate
    val taxRate: Double?,
    val priceEditable: Boolean,
    val rawPrice: JsonArray?,
)
