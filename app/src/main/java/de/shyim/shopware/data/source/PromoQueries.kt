package de.shyim.shopware.data.source

import de.shyim.shopware.api.Criteria
import de.shyim.shopware.api.SwEntity
import de.shyim.shopware.data.PromoStatus
import de.shyim.shopware.data.fmt
import de.shyim.shopware.data.model.ConnectedShop
import de.shyim.shopware.data.model.ShopPromo
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

// Shared by the snapshot (ShopwareDataSource) and the live promotions listing
fun promoCriteria(): Criteria = Criteria()
    .addSorting("createdAt", "DESC")
    .addAssociation("discounts")
    .addIncludes(
        "promotion",
        listOf(
            "id", "name", "active", "validFrom", "validUntil",
            "orderCount", "discounts", "translated", "useIndividualCodes",
        ),
    )
    .addIncludes("promotion_discount", listOf("type", "value"))

fun parsePromo(p: SwEntity, now: Instant, shop: ConnectedShop): ShopPromo {
    val active = p.boolean("active") == true
    val from = p.instant("validFrom")
    val until = p.instant("validUntil")
    val status = when {
        active && from != null && from.isAfter(now) -> PromoStatus.Scheduled
        active && (until == null || until.isAfter(now)) -> PromoStatus.Active
        else -> PromoStatus.Ended
    }
    val dateFmt = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH).withZone(ZoneOffset.UTC)
    val window = when (status) {
        PromoStatus.Scheduled -> "Starts ${dateFmt.format(from!!)}"
        PromoStatus.Active -> until?.let { "Ends ${dateFmt.format(it)}" } ?: "No end date"
        PromoStatus.Ended -> until?.let { "Ended ${dateFmt.format(it)}" } ?: "Inactive"
    }
    val discount = p.entities("discounts").firstOrNull()
    val detail = when (discount?.string("type")) {
        "percentage" -> "−${(discount.double("value") ?: 0.0).toInt()}%"
        "absolute" -> "−${shop.fmt(discount.double("value") ?: 0.0)}"
        else -> "Promotion"
    }
    return ShopPromo(
        name = p.translated("name") ?: "Promotion",
        detail = detail,
        status = status,
        window = window,
        redemptions = p.int("orderCount") ?: 0,
        id = p.id ?: "",
        active = active,
        useIndividualCodes = p.boolean("useIndividualCodes") == true,
    )
}
