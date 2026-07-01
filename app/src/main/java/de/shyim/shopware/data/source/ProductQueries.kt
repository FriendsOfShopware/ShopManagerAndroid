package de.shyim.shopware.data.source

import de.shyim.shopware.api.Criteria
import de.shyim.shopware.api.ShopApi
import de.shyim.shopware.api.SwEntity
import de.shyim.shopware.data.model.ProductDetail
import de.shyim.shopware.data.model.ProductQuickInfo
import de.shyim.shopware.data.model.ProductRow
import de.shyim.shopware.data.model.ProductVariant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

// Parent products only (variants hidden, as the admin list does); cover + manufacturer for the row
fun productListCriteria(): Criteria = Criteria()
    .addFilter(Criteria.equals("parentId", null))
    .addSorting("name")
    .addAssociation("cover.media")
    .addAssociation("manufacturer")
    .addIncludes(
        "product",
        listOf("id", "productNumber", "name", "translated", "active", "stock", "price", "cover", "manufacturer"),
    )
    .addIncludes("product_media", listOf("media"))
    .addIncludes("media", listOf("url"))
    .addIncludes("product_manufacturer", listOf("name", "translated"))

fun parseProduct(p: SwEntity, shopBaseUrl: String): ProductRow {
    val firstPrice = (p.json["price"] as? JsonArray)?.firstOrNull() as? JsonObject
    val coverUrl = p.entity("cover")?.entity("media")?.string("url")
        ?.let { rebaseMediaUrl(it, shopBaseUrl) }
    return ProductRow(
        id = p.id ?: "",
        name = p.translated("name") ?: "—",
        productNumber = p.string("productNumber") ?: "",
        active = p.boolean("active") ?: false,
        stock = p.int("stock") ?: 0,
        grossPrice = (firstPrice?.get("gross") as? JsonPrimitive)?.doubleOrNull,
        manufacturer = p.entity("manufacturer")?.translated("name"),
        coverUrl = coverUrl,
    )
}

suspend fun ShopApi.fetchProductQuickInfo(productId: String): ProductQuickInfo? {
    val product = repository("product").get(
        productId,
        Criteria().addIncludes(
            "product",
            listOf(
                "id", "name", "translated", "stock", "active",
                "parentId", "price", "prices", "productNumber",
            ),
        ).addAssociation("prices")
    ) ?: return null

    val priceArray = product.json["price"] as? JsonArray
    val firstPrice = priceArray?.firstOrNull() as? JsonObject
    val hasAdvancedPrices = product.entities("prices").isNotEmpty()
    val isVariantChild = product.string("parentId") != null

    return ProductQuickInfo(
        id = productId,
        name = product.translated("name") ?: "—",
        productNumber = product.string("productNumber") ?: "",
        stock = product.int("stock") ?: 0,
        active = product.boolean("active") ?: false,
        grossPrice = (firstPrice?.get("gross") as? JsonPrimitive)?.doubleOrNull,
        priceEditable = firstPrice != null && !hasAdvancedPrices && !isVariantChild,
        rawPrice = priceArray,
    )
}

suspend fun ShopApi.saveProductQuickEdit(
    info: ProductQuickInfo,
    stock: Int,
    active: Boolean,
    newGross: Double?,
) {
    val payload = buildJsonObject {
        put("stock", stock)
        put("active", active)
        if (newGross != null && info.priceEditable && info.rawPrice != null) {
            val oldGross = info.grossPrice ?: 0.0
            // Scale every currency entry's gross/net by the same factor to keep ratios intact
            val factor = if (oldGross > 0) newGross / oldGross else 1.0
            put("price", buildJsonArray {
                info.rawPrice.forEach { entry ->
                    val o = entry as? JsonObject ?: return@forEach
                    add(buildJsonObject {
                        o.forEach { (k, v) -> put(k, v) }
                        val gross = (o["gross"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                        val net = (o["net"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                        put("gross", gross * factor)
                        put("net", net * factor)
                    })
                }
            })
        }
    }
    repository("product").patch(info.id, payload)
}

// ---- Product detail (read) ----

suspend fun ShopApi.fetchProductDetail(productId: String, shopBaseUrl: String): ProductDetail? {
    val p = repository("product").get(
        productId,
        Criteria()
            .addAssociation("manufacturer")
            .addAssociation("tax")
            .addAssociation("cover.media")
            .addAssociation("media.media")
            .addAssociation("categories")
            .addAssociation("prices")
            .addAssociation("visibilities.salesChannel")
            .addIncludes(
                "product",
                listOf(
                    "id", "name", "translated", "productNumber", "description", "active",
                    "stock", "availableStock", "price", "prices", "tax", "manufacturer",
                    "ean", "manufacturerNumber",
                    "cover", "media", "categories", "visibilities", "ratingAverage",
                    "childCount", "releaseDate",
                ),
            )
            .addIncludes("product_manufacturer", listOf("name", "translated"))
            .addIncludes("tax", listOf("taxRate", "name"))
            .addIncludes("category", listOf("name", "translated"))
            .addIncludes("product_media", listOf("media", "position"))
            .addIncludes("media", listOf("url"))
            .addIncludes("sales_channel", listOf("name", "translated"))
            .addIncludes("product_visibility", listOf("salesChannel"))
    ) ?: return null

    val priceArray = p.json["price"] as? JsonArray
    val firstPrice = priceArray?.firstOrNull() as? JsonObject
    val hasAdvancedPrices = p.entities("prices").isNotEmpty()
    val gallery = p.entities("media")
        .mapNotNull { it.entity("media")?.string("url") }
        .map { rebaseMediaUrl(it, shopBaseUrl) }

    return ProductDetail(
        id = productId,
        name = p.translated("name") ?: "—",
        productNumber = p.string("productNumber") ?: "",
        description = p.translated("description")?.takeIf { it.isNotBlank() },
        active = p.boolean("active") ?: false,
        stock = p.int("stock") ?: 0,
        availableStock = p.int("availableStock") ?: 0,
        grossPrice = (firstPrice?.get("gross") as? JsonPrimitive)?.doubleOrNull,
        netPrice = (firstPrice?.get("net") as? JsonPrimitive)?.doubleOrNull,
        priceLinked = (firstPrice?.get("linked") as? JsonPrimitive)?.booleanOrNull ?: true,
        priceEditable = firstPrice != null && !hasAdvancedPrices,
        rawPrice = priceArray,
        taxRate = p.entity("tax")?.double("taxRate"),
        manufacturer = p.entity("manufacturer")?.translated("name"),
        ean = p.string("ean")?.takeIf { it.isNotBlank() },
        manufacturerNumber = p.string("manufacturerNumber")?.takeIf { it.isNotBlank() },
        categories = p.entities("categories").mapNotNull { it.translated("name") },
        salesChannels = p.entities("visibilities")
            .mapNotNull { it.entity("salesChannel")?.translated("name") }
            .distinct(),
        coverUrl = p.entity("cover")?.entity("media")?.string("url")
            ?.let { rebaseMediaUrl(it, shopBaseUrl) },
        galleryUrls = gallery,
        ratingAverage = p.double("ratingAverage"),
        childCount = p.int("childCount") ?: 0,
        releaseDateMs = p.instant("releaseDate")?.toEpochMilli(),
    )
}

// parentTaxRate is used for variants that inherit the parent's tax (no own tax association).
suspend fun ShopApi.fetchProductVariants(parentId: String, parentTaxRate: Double?): List<ProductVariant> {
    val children = repository("product").search(
        Criteria()
            .setLimit(100)
            .addFilter(Criteria.equals("parentId", parentId))
            .addSorting("productNumber")
            .addAssociation("options.group")
            .addAssociation("prices")
            .addAssociation("tax")
            .addIncludes(
                "product",
                listOf("id", "productNumber", "stock", "active", "price", "prices", "options", "tax"),
            )
            .addIncludes("property_group_option", listOf("name", "translated", "groupId"))
            .addIncludes("tax", listOf("taxRate"))
    ).data

    return children.map { c ->
        val priceArray = c.json["price"] as? JsonArray
        val firstPrice = priceArray?.firstOrNull() as? JsonObject
        val hasAdvancedPrices = c.entities("prices").isNotEmpty()
        ProductVariant(
            id = c.id ?: "",
            productNumber = c.string("productNumber") ?: "",
            optionLabels = c.entities("options").mapNotNull { it.translated("name") },
            active = c.boolean("active") ?: false,
            stock = c.int("stock") ?: 0,
            grossPrice = (firstPrice?.get("gross") as? JsonPrimitive)?.doubleOrNull,
            netPrice = (firstPrice?.get("net") as? JsonPrimitive)?.doubleOrNull,
            priceLinked = (firstPrice?.get("linked") as? JsonPrimitive)?.booleanOrNull ?: true,
            taxRate = c.entity("tax")?.double("taxRate") ?: parentTaxRate,
            priceEditable = firstPrice != null && !hasAdvancedPrices,
            rawPrice = priceArray,
        )
    }
}

// ---- Product detail (write) ----

// An explicit gross/net/linked edit of the default-currency price (or null to leave it unchanged).
data class PriceEdit(val gross: Double, val net: Double, val linked: Boolean)

// Patch the base data the detail edit sheet exposes (description is intentionally not editable —
// it is rich HTML).
suspend fun ShopApi.saveProductDetail(
    detail: ProductDetail,
    name: String,
    active: Boolean,
    stock: Int,
    ean: String?,
    manufacturerNumber: String?,
    price: PriceEdit?,
) {
    val payload = buildJsonObject {
        put("name", name)
        put("active", active)
        put("stock", stock)
        // Send null to clear a previously-set value; the server stores null for an empty string.
        put("ean", ean?.ifBlank { null })
        put("manufacturerNumber", manufacturerNumber?.ifBlank { null })
        priceField(detail.priceEditable, detail.rawPrice, price)
    }
    repository("product").patch(detail.id, payload)
}

// Patch a single variant's stock and (optionally) gross/net/linked price.
suspend fun ShopApi.saveVariantEdit(variant: ProductVariant, stock: Int, price: PriceEdit?) {
    val payload = buildJsonObject {
        put("stock", stock)
        priceField(variant.priceEditable, variant.rawPrice, price)
    }
    repository("product").patch(variant.id, payload)
}

// Emit a "price" key: set gross/net/linked on the first (default-currency) entry, preserving the
// rest of that entry's fields and any other currency entries verbatim.
private fun kotlinx.serialization.json.JsonObjectBuilder.priceField(
    editable: Boolean,
    rawPrice: JsonArray?,
    price: PriceEdit?,
) {
    if (price == null || !editable || rawPrice == null || rawPrice.isEmpty()) return
    put("price", buildJsonArray {
        rawPrice.forEachIndexed { i, entry ->
            val o = entry as? JsonObject ?: return@forEachIndexed
            add(buildJsonObject {
                o.forEach { (k, v) -> put(k, v) }
                if (i == 0) {
                    put("gross", price.gross)
                    put("net", price.net)
                    put("linked", price.linked)
                }
            })
        }
    })
}
