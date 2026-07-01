package de.shyim.shopware.data.model

import androidx.compose.ui.graphics.Color
import de.shyim.shopware.data.PromoStatus
import de.shyim.shopware.data.ShopTint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The five pastel tints from the design, assignable to connected shops
val TintPalette = listOf(
    ShopTint(Color(0xFFC3F3CE), Color(0xFF034721), Color(0xFF1E3A26), Color(0xFFABE9BB)),
    ShopTint(Color(0xFFDCEDB9), Color(0xFF334100), Color(0xFF2E3719), Color(0xFFCDE19F)),
    ShopTint(Color(0xFFF5E4B0), Color(0xFF4B3800), Color(0xFF3C3212), Color(0xFFEDD692)),
    ShopTint(Color(0xFFB0F5E8), Color(0xFF00493E), Color(0xFF0D3B35), Color(0xFF91EBDC)),
    ShopTint(Color(0xFFFFDBB5), Color(0xFF5B2D00), Color(0xFF452D16), Color(0xFFFFCB9A)),
)

@Serializable
sealed class ShopAuth {
    // Admin session: rotating OAuth refresh token (AES-GCM encrypted), never the password
    @Serializable
    @SerialName("admin")
    data class Admin(val username: String, val encRefreshToken: String) : ShopAuth()

    // Legacy decode-only variants. "password" migrates itself on the next successful
    // grant (the rotation callback rewrites it to Admin); "integration" support was
    // removed — those shops keep their data but require sign-in-again in shop settings.
    @Serializable
    @SerialName("password")
    data class Password(val username: String, val encPassword: String) : ShopAuth()

    @Serializable
    @SerialName("integration")
    data class Integration(val clientId: String, val encSecret: String) : ShopAuth()
}

@Serializable
data class ConnectedShop(
    val id: String,
    val name: String,
    val baseUrl: String, // normalized, no trailing slash
    val auth: ShopAuth? = null,
    val tintIndex: Int = 0,
    val currency: String = "EUR",
    val dailyTarget: Double? = null,
    val demo: Boolean = false, // legacy demo-mode flag; demo shops are purged at load
    val languageId: String? = null, // null = instance system language
    val localeCode: String? = null, // e.g. de-DE; drives number formatting
    val lowStockThreshold: Int = 5,
    // entity → read access, from the wizard ACL probes (re-probed on sign-in-again);
    // empty = never probed (legacy shops) and treated as all-granted
    val scopes: Map<String, Boolean> = emptyMap(),
    // Per-shop product-detail layout: which optional fields are visible / editable.
    val productFields: ProductFieldConfig = ProductFieldConfig(),
) {
    val tint: ShopTint get() = TintPalette[tintIndex.mod(TintPalette.size)]

    fun canRead(entity: String): Boolean = scopes[entity] ?: true
}

/**
 * Per-shop configuration of the product detail page. Each optional field can be hidden from the
 * detail view (`show*`) and/or removed from the edit sheet (`edit*`). The core fields
 * (name, active, stock) are always shown and editable and so are not configurable here.
 * Defaults are "everything on", so the all-default instance (and any old persisted JSON missing
 * this object) reproduces the current behaviour — this is an additive, migration-free change.
 */
@Serializable
data class ProductFieldConfig(
    val showEan: Boolean = true,
    val editEan: Boolean = true,
    val showManufacturerNumber: Boolean = true,
    val editManufacturerNumber: Boolean = true,
    val showPrice: Boolean = true,
    val editPrice: Boolean = true,
    val showDescription: Boolean = true,
    val showManufacturer: Boolean = true,
    val showCategories: Boolean = true,
    val showSalesChannels: Boolean = true,
)

@Serializable
data class RecentOrder(
    val id: String = "",
    val orderNumber: String,
    val customer: String,
    val state: String,
    val stateTechnical: String,
    val amount: Double, // in the order's currency
    val currencyIso: String? = null, // null = assume the shop default (legacy snapshots)
    val placedMs: Long = 0, // 0 = unknown (legacy snapshots); rendered as "just now"
)

@Serializable
data class TopCustomer(
    val name: String,
    val orderCount: Int,
    val totalSpend: Double,
)

@Serializable
data class ShopPromo(
    val name: String,
    val detail: String,
    val status: PromoStatus,
    val window: String,
    val redemptions: Int,
    val id: String = "",
    val active: Boolean = false,
    val useIndividualCodes: Boolean = false,
)

@Serializable
data class TopProduct(
    val name: String,
    val quantity: Int,
    val revenue: Double,
)

@Serializable
data class LowStockItem(val name: String, val stock: Int, val id: String = "")

@Serializable
data class ShopSnapshot(
    val todayRevenue: Double,
    val yesterdayRevenue: Double,
    val ordersToday: Int,
    val openOrders: Int,
    val unpaidOrders: Int,
    val lowStockCount: Int,
    val lowStockItems: List<LowStockItem> = emptyList(),
    val weekRevenue: List<Double>,
    val todayIndex: Int = 6,
    val recentOrders: List<RecentOrder> = emptyList(),
    val topCustomers: List<TopCustomer> = emptyList(),
    val promos: List<ShopPromo> = emptyList(),
    val topProducts: List<TopProduct> = emptyList(),
    val pendingReviews: Int = 0,
    val shopwareVersion: String? = null,
    val lastSyncEpochMs: Long = 0,
)

// Live review item — fetched on demand for the inbox, not persisted
data class ReviewItem(
    val id: String,
    val title: String,
    val content: String,
    val points: Int,
    val approved: Boolean,
    val reviewer: String,
    val productName: String,
    val createdMs: Long,
)

@Serializable
data class AppData(
    val shops: List<ConnectedShop> = emptyList(),
    // populated at runtime by AppRepository from per-shop SnapshotStore files; persisted
    // only by legacy versions (migrated out of app-data.json at first load)
    val snapshots: Map<String, ShopSnapshot> = emptyMap(),
    val selectedShopId: String? = null,
    val onboardingSeen: Boolean = false,
    val syncEnabled: Boolean = false,
    val notifyLowStock: Boolean = false,
    val notifyUnpaid: Boolean = false,
    // Stable per-installation id, used as the ce_fcn row id so re-registration upserts in place.
    // Empty until first generated.
    val pushInstallId: String = "",
)
