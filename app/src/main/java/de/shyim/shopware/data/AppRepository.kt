package de.shyim.shopware.data

import android.content.Context
import de.shyim.shopware.api.ApiContext
import de.shyim.shopware.api.PlainAuth
import de.shyim.shopware.api.ApiError
import de.shyim.shopware.api.ShopApi
import de.shyim.shopware.api.runCatchingCancellable
import de.shyim.shopware.data.model.AppData
import de.shyim.shopware.data.model.ConnectedShop
import de.shyim.shopware.data.model.CountryOption
import de.shyim.shopware.data.model.AnalyticsFilters
import de.shyim.shopware.data.model.CustomerDetail
import de.shyim.shopware.data.model.KpiState
import de.shyim.shopware.data.model.KpiType
import de.shyim.shopware.data.model.ProductFieldConfig
import de.shyim.shopware.data.source.AnalyticsFilterOptions
import de.shyim.shopware.data.source.analyticsAvgOrderValue
import de.shyim.shopware.data.source.analyticsBestSelling
import de.shyim.shopware.data.source.analyticsCountry
import de.shyim.shopware.data.source.analyticsCustomerCount
import de.shyim.shopware.data.source.analyticsManufacturer
import de.shyim.shopware.data.source.analyticsNewCustomers
import de.shyim.shopware.data.source.analyticsOrderCount
import de.shyim.shopware.data.source.analyticsPaymentMethod
import de.shyim.shopware.data.source.analyticsPromotionCode
import de.shyim.shopware.data.source.analyticsRevenueTotal
import de.shyim.shopware.data.source.analyticsSalesChannel
import de.shyim.shopware.data.source.analyticsShippingMethod
import de.shyim.shopware.data.source.analyticsTotalSales
import de.shyim.shopware.data.source.fetchAnalyticsFilterOptions
import de.shyim.shopware.data.model.EditableAddress
import de.shyim.shopware.data.model.SalutationOption
import de.shyim.shopware.api.LanguageOption
import de.shyim.shopware.data.model.MediaFolderItem
import de.shyim.shopware.data.model.OrderDetail
import de.shyim.shopware.data.model.OrderTimelineEntry
import de.shyim.shopware.data.model.ProductDetail
import de.shyim.shopware.data.model.ProductQuickInfo
import de.shyim.shopware.data.model.ProductVariant
import de.shyim.shopware.data.model.ShopAuth
import de.shyim.shopware.data.source.ShopwareDataSource
import de.shyim.shopware.data.source.fetchCountries
import de.shyim.shopware.data.source.fetchCustomerDetail
import de.shyim.shopware.data.source.fetchOrderDetail
import de.shyim.shopware.data.source.fetchOrderTimeline
import de.shyim.shopware.data.source.fetchProductDetail
import de.shyim.shopware.data.source.FcmRegistration
import de.shyim.shopware.data.source.fetchFcmRegistration
import de.shyim.shopware.data.source.registerFcmToken
import de.shyim.shopware.data.source.unregisterFcmToken
import de.shyim.shopware.data.source.fetchProductQuickInfo
import de.shyim.shopware.data.source.fetchProductVariants
import de.shyim.shopware.data.source.fetchSalutations
import de.shyim.shopware.data.source.mediaFolderCriteria
import de.shyim.shopware.data.source.parseMediaFolder
import de.shyim.shopware.data.source.saveCustomerAddress
import de.shyim.shopware.data.source.saveCustomerContact
import de.shyim.shopware.data.source.PriceEdit
import de.shyim.shopware.data.source.saveProductDetail
import de.shyim.shopware.data.source.saveProductQuickEdit
import de.shyim.shopware.data.source.saveVariantEdit
import de.shyim.shopware.data.source.setInternalComment
import de.shyim.shopware.data.source.setReviewStatus
import de.shyim.shopware.data.source.setTrackingCodes
import de.shyim.shopware.data.model.ShopSnapshot
import de.shyim.shopware.data.store.Crypto
import de.shyim.shopware.data.store.SnapshotStore
import de.shyim.shopware.data.store.appDataStore
import de.shyim.shopware.widget.refreshSalesWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class AppRepository(context: Context) {

    companion object {
        // Entities whose read access is probed at connect and on sign-in-again;
        // when adding a feature that needs a new privilege, extend this list
        val PROBE_ENTITIES = listOf("order", "product", "customer", "promotion", "product_review", "media")
    }

    private val appContext = context.applicationContext
    private val store = appContext.appDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Snapshots live in per-shop cache files (see SnapshotStore); app-data.json stays
    // small and is only written on settings/auth changes. The flows are combined here
    // so consumers keep seeing AppData.snapshots as before.
    private val snapshotStore = SnapshotStore(File(context.applicationContext.filesDir, "snapshots"))
    private val snapshots = MutableStateFlow<Map<String, ShopSnapshot>>(emptyMap())

    init {
        scope.launch {
            var loaded = snapshotStore.loadAll()
            // one-time migration: older versions persisted snapshots inside app-data.json
            val legacy = store.data.first().snapshots
            if (legacy.isNotEmpty()) {
                legacy.forEach { (id, snap) -> snapshotStore.write(id, snap) }
                loaded = loaded + legacy
                store.updateData { it.copy(snapshots = emptyMap()) }
            }
            snapshots.value = loaded
        }
    }

    val data: Flow<AppData> = combine(store.data, snapshots) { d, snaps ->
        d.copy(snapshots = snaps)
    }

    private val apis = mutableMapOf<String, ShopApi>()

    fun apiFor(shop: ConnectedShop): ShopApi = apis.getOrPut(shop.id) {
        ShopApi(
            shop.baseUrl,
            shop.plainAuth(),
            ApiContext(languageId = shop.languageId),
            onRefreshToken = { rotated -> persistRefreshToken(shop.id, rotated) },
        )
    }

    // Called by the API client on every refresh-token rotation; also migrates legacy
    // password-auth shops to Admin auth on their first successful grant.
    private suspend fun persistRefreshToken(shopId: String, refreshToken: String, username: String? = null) {
        val enc = Crypto.encrypt(refreshToken)
        store.updateData { d ->
            d.copy(shops = d.shops.map { shop ->
                if (shop.id != shopId) shop else shop.copy(
                    auth = ShopAuth.Admin(
                        username = username ?: when (val a = shop.auth) {
                            is ShopAuth.Admin -> a.username
                            is ShopAuth.Password -> a.username
                            else -> ""
                        },
                        encRefreshToken = enc,
                    )
                )
            })
        }
    }

    // Validates the credentials, stores the new session and refreshes the snapshot.
    suspend fun reauthenticate(shop: ConnectedShop, username: String, password: String) {
        apis.remove(shop.id)
        val user = username.trim()
        val api = ShopApi(
            shop.baseUrl,
            PlainAuth.Password(user, password),
            ApiContext(languageId = shop.languageId),
            onRefreshToken = { rotated -> persistRefreshToken(shop.id, rotated, user) },
        )
        api.instance.version() // forces the grant; throws ApiError on bad credentials
        // privileges may differ for the new login — re-probe and persist
        val scopes = PROBE_ENTITIES.associateWith { api.instance.probe(it) }
        store.updateData { d ->
            d.copy(shops = d.shops.map {
                if (it.id == shop.id) it.copy(scopes = scopes) else it
            })
        }
        apis[shop.id] = api
        refresh(shop.id)
    }

    private val liveSource = ShopwareDataSource(::apiFor)

    suspend fun refresh(shopId: String): Result<Unit> = runCatchingCancellable {
        val d = store.data.first()
        val shop = d.shops.firstOrNull { it.id == shopId } ?: error("Shop not found")
        val snapshot = liveSource.fetchSnapshot(shop, shop.lowStockThreshold)
        snapshotStore.write(shopId, snapshot)
        snapshots.update { it + (shopId to snapshot) }
        reconcileCurrency(shop)
        refreshSalesWidgets(appContext) // repaint home-screen widgets from the fresh cache
    }

    // Dashboard sums are normalized server-side into the system default currency, so the
    // stored display currency must track it. Shops connected before the default-currency
    // detection was fixed (issue #2: an arbitrary factor-1.0 currency won) heal here on
    // their next refresh; also covers the admin changing the system currency later.
    private suspend fun reconcileCurrency(shop: ConnectedShop) {
        val iso = apiFor(shop).instance.defaultCurrencyIso() ?: return
        if (iso == shop.currency) return
        store.updateData { d ->
            d.copy(shops = d.shops.map {
                if (it.id == shop.id) it.copy(currency = iso) else it
            })
        }
    }

    suspend fun addShop(shop: ConnectedShop) {
        store.updateData {
            it.copy(
                shops = it.shops + shop,
                selectedShopId = shop.id,
                onboardingSeen = true,
            )
        }
    }


    suspend fun removeShop(id: String) {
        apis.remove(id)
        snapshotStore.delete(id)
        snapshots.update { it - id }
        store.updateData { d ->
            val shops = d.shops.filterNot { it.id == id }
            d.copy(
                shops = shops,
                selectedShopId = if (d.selectedShopId == id) shops.firstOrNull()?.id else d.selectedShopId,
            )
        }
    }

    suspend fun selectShop(id: String) {
        store.updateData { it.copy(selectedShopId = id) }
        refreshSalesWidgets(appContext) // widget follows the selected shop
    }

    // ---- Push (FCM token registration into each shop's ce_fcn entity) ----

    // Stable per-installation id (the ce_fcn row id); generated once and persisted.
    private suspend fun ensurePushInstallId(): String {
        val existing = store.data.first().pushInstallId
        if (existing.isNotEmpty()) return existing
        val id = java.util.UUID.randomUUID().toString().replace("-", "")
        store.updateData { if (it.pushInstallId.isEmpty()) it.copy(pushInstallId = id) else it }
        return store.data.first().pushInstallId
    }

    // Upsert the FCM token into every connected shop that can read ce_fcn. Best-effort per shop:
    // a shop without the push app (or without write access) is skipped, not fatal.
    suspend fun registerPushToken(token: String, deviceName: String) {
        if (token.isBlank()) return
        val installId = ensurePushInstallId()
        val shops = store.data.first().shops
        for (shop in shops) {
            runCatchingCancellable { apiFor(shop).registerFcmToken(installId, token, deviceName) }
        }
    }

    // Register against a single shop and report whether the push app is present. A 404 on
    // /api/ce-fcn means the FroshMobilePush app isn't installed (the entity doesn't exist).
    // Other failures (network, transient) are treated as Ok so we don't nag the user about them.
    suspend fun registerPushForShop(shop: ConnectedShop, token: String, deviceName: String): PushRegisterResult {
        if (token.isBlank()) return PushRegisterResult.Ok
        val installId = ensurePushInstallId()
        return runCatchingCancellable { apiFor(shop).registerFcmToken(installId, token, deviceName) }.fold(
            onSuccess = { PushRegisterResult.Ok },
            onFailure = { e ->
                if (e is ApiError.NotFound) PushRegisterResult.AppNotInstalled else PushRegisterResult.Ok
            },
        )
    }

    // Live per-shop push registration status, queried from the shop's ce_fcn. Shown in shop
    // settings so the user sees the real server state, not a local guess. Network/permission
    // failures map to Unavailable rather than a misleading "not registered".
    suspend fun pushStatusForShop(shop: ConnectedShop): PushStatus {
        val installId = ensurePushInstallId()
        return runCatchingCancellable { apiFor(shop).fetchFcmRegistration(installId) }.fold(
            onSuccess = { reg ->
                when (reg) {
                    is FcmRegistration.Present -> PushStatus.Registered(reg.deviceName)
                    FcmRegistration.Absent -> PushStatus.NotRegistered
                }
            },
            onFailure = { e ->
                if (e is ApiError.NotFound) PushStatus.AppNotInstalled else PushStatus.Unavailable
            },
        )
    }

    // Remove this device's ce_fcn row from one shop (the user opting out per-shop).
    suspend fun unregisterPushForShop(shop: ConnectedShop) {
        val installId = ensurePushInstallId()
        runCatchingCancellable { apiFor(shop).unregisterFcmToken(installId) }
    }

    suspend fun markOnboardingSeen() {
        store.updateData { it.copy(onboardingSeen = true) }
    }

    suspend fun updateShopSettings(
        shopId: String,
        name: String,
        tintIndex: Int,
        dailyTarget: Double?,
        lowStockThreshold: Int,
    ) {
        val old = store.data.first().shops.firstOrNull { it.id == shopId } ?: return
        store.updateData { d ->
            d.copy(shops = d.shops.map {
                if (it.id == shopId) it.copy(
                    name = name,
                    tintIndex = tintIndex,
                    dailyTarget = dailyTarget,
                    lowStockThreshold = lowStockThreshold,
                ) else it
            })
        }
        // The snapshot's low-stock count/items were computed with the old threshold
        if (old.lowStockThreshold != lowStockThreshold) refresh(shopId)
    }

    suspend fun updateProductFields(shopId: String, config: ProductFieldConfig) {
        store.updateData { d ->
            d.copy(shops = d.shops.map {
                if (it.id == shopId) it.copy(productFields = config) else it
            })
        }
    }

    suspend fun languagesFor(shop: ConnectedShop): List<LanguageOption> =
        apiFor(shop).instance.languages()

    suspend fun setShopLanguage(shopId: String, language: LanguageOption?) {
        apis.remove(shopId)
        store.updateData { d ->
            d.copy(shops = d.shops.map {
                if (it.id == shopId) it.copy(languageId = language?.id, localeCode = language?.localeCode)
                else it
            })
        }
        refresh(shopId)
    }

    suspend fun orderDetail(shop: ConnectedShop, orderId: String): OrderDetail =
        apiFor(shop).fetchOrderDetail(orderId)

    suspend fun orderTimeline(shop: ConnectedShop, referencedIds: List<String>): List<OrderTimelineEntry> =
        apiFor(shop).fetchOrderTimeline(referencedIds)

    suspend fun customerDetail(shop: ConnectedShop, customerId: String): CustomerDetail? =
        apiFor(shop).fetchCustomerDetail(customerId)

    // One analytics KPI for the Reports tab. Wrapped to the right KpiState variant; each card
    // calls this independently so one slow/failing KPI never blocks the others.
    suspend fun loadKpi(shop: ConnectedShop, type: KpiType, filters: AnalyticsFilters): KpiState {
        val api = apiFor(shop)
        return when (type) {
            KpiType.TotalSales -> KpiState.TimeSeries(api.analyticsTotalSales(filters))
            KpiType.OrderCount -> KpiState.TimeSeries(api.analyticsOrderCount(filters))
            KpiType.AvgOrderValue -> KpiState.TimeSeries(api.analyticsAvgOrderValue(filters))
            KpiType.NewCustomers -> KpiState.TimeSeries(api.analyticsNewCustomers(filters))
            KpiType.SalesChannel -> KpiState.Breakdown(api.analyticsSalesChannel(filters))
            KpiType.PaymentMethod -> KpiState.Breakdown(api.analyticsPaymentMethod(filters))
            KpiType.ShippingMethod -> KpiState.Breakdown(api.analyticsShippingMethod(filters))
            KpiType.Country -> KpiState.Breakdown(api.analyticsCountry(filters))
            KpiType.BestSellingProduct -> KpiState.Breakdown(api.analyticsBestSelling(filters))
            KpiType.Manufacturer -> KpiState.Breakdown(api.analyticsManufacturer(filters))
            KpiType.PromotionCode -> KpiState.Breakdown(api.analyticsPromotionCode(filters))
            KpiType.CustomerCount -> KpiState.Single(api.analyticsCustomerCount(filters))
        }
    }

    // Normalized revenue total for one shop over the filtered range (cross-shop comparison).
    suspend fun loadShopRevenue(shop: ConnectedShop, filters: AnalyticsFilters): Double =
        apiFor(shop).analyticsRevenueTotal(filters)

    // Option loaders for the analytics filter bar (sales channels, customer groups, countries,
    // and the order/transaction/delivery state-machine states).
    suspend fun analyticsFilterOptions(shop: ConnectedShop): AnalyticsFilterOptions =
        apiFor(shop).fetchAnalyticsFilterOptions()

    suspend fun saveCustomerContact(
        shop: ConnectedShop,
        customerId: String,
        firstName: String,
        lastName: String,
        email: String,
        salutationId: String?,
        title: String?,
        company: String?,
    ) = apiFor(shop).saveCustomerContact(customerId, firstName, lastName, email, salutationId, title, company)

    suspend fun saveCustomerAddress(shop: ConnectedShop, address: EditableAddress) =
        apiFor(shop).saveCustomerAddress(address)

    suspend fun salutations(shop: ConnectedShop): List<SalutationOption> =
        apiFor(shop).fetchSalutations()

    suspend fun countries(shop: ConnectedShop): List<CountryOption> =
        apiFor(shop).fetchCountries()

    suspend fun productQuickInfo(shop: ConnectedShop, productId: String): ProductQuickInfo? =
        apiFor(shop).fetchProductQuickInfo(productId)

    suspend fun saveProductQuickEdit(
        shop: ConnectedShop,
        info: ProductQuickInfo,
        stock: Int,
        active: Boolean,
        newGross: Double?,
    ) = apiFor(shop).saveProductQuickEdit(info, stock, active, newGross)

    suspend fun productDetail(shop: ConnectedShop, productId: String): ProductDetail? =
        apiFor(shop).fetchProductDetail(productId, shop.baseUrl)

    suspend fun productVariants(shop: ConnectedShop, parentId: String, parentTaxRate: Double?): List<ProductVariant> =
        apiFor(shop).fetchProductVariants(parentId, parentTaxRate)

    suspend fun saveProductDetail(
        shop: ConnectedShop,
        detail: ProductDetail,
        name: String,
        active: Boolean,
        stock: Int,
        ean: String?,
        manufacturerNumber: String?,
        price: PriceEdit?,
    ) = apiFor(shop).saveProductDetail(detail, name, active, stock, ean, manufacturerNumber, price)

    suspend fun saveVariantEdit(
        shop: ConnectedShop,
        variant: ProductVariant,
        stock: Int,
        price: PriceEdit?,
    ) = apiFor(shop).saveVariantEdit(variant, stock, price)

    suspend fun uploadProductPhoto(shop: ConnectedShop, productId: String, bytes: ByteArray) =
        apiFor(shop).media.uploadProductCover(productId, bytes)

    suspend fun mediaFolders(shop: ConnectedShop, parentId: String?): List<MediaFolderItem> =
        apiFor(shop).repository("media-folder").search(mediaFolderCriteria(parentId))
            .data.map(::parseMediaFolder)

    suspend fun createMediaFolder(shop: ConnectedShop, parentId: String?, name: String): String =
        apiFor(shop).media.createFolder(name, parentId)

    suspend fun uploadMedia(shop: ConnectedShop, folderId: String?, bytes: ByteArray, extension: String) =
        apiFor(shop).media.uploadImage(bytes, extension, mediaFolderId = folderId)

    suspend fun deleteMedia(shop: ConnectedShop, mediaId: String) =
        apiFor(shop).repository("media").delete(mediaId)

    suspend fun setPromotionActive(shop: ConnectedShop, promotionId: String, active: Boolean) =
        apiFor(shop).promotions.setActive(promotionId, active)

    suspend fun addPromotionCodes(shop: ConnectedShop, promotionId: String, amount: Int) =
        apiFor(shop).promotions.addIndividualCodes(promotionId, amount)

    suspend fun setTrackingCodes(shop: ConnectedShop, deliveryId: String, codes: List<String>) =
        apiFor(shop).setTrackingCodes(deliveryId, codes)

    suspend fun setInternalComment(shop: ConnectedShop, orderId: String, comment: String?) =
        apiFor(shop).setInternalComment(orderId, comment)

    suspend fun generateDocument(shop: ConnectedShop, orderId: String, type: String) =
        apiFor(shop).documents.create(orderId, type)

    suspend fun downloadDocument(shop: ConnectedShop, documentId: String, deepLinkCode: String): ByteArray =
        apiFor(shop).documents.download(documentId, deepLinkCode)

    suspend fun setReviewStatus(shop: ConnectedShop, reviewId: String, approved: Boolean) =
        apiFor(shop).setReviewStatus(reviewId, approved)

    suspend fun transition(shop: ConnectedShop, transitionUrl: String) =
        apiFor(shop).stateMachine.transition(transitionUrl)

    suspend fun transitionOrderState(
        shop: ConnectedShop,
        entity: String,
        entityId: String,
        actionName: String,
        sendMail: Boolean,
        documentIds: List<String>,
        internalComment: String?,
    ) = apiFor(shop).stateMachine.transitionWithOptions(
        entity, entityId, actionName, sendMail, documentIds, internalComment,
    )


}

// Outcome of registering the FCM token with one shop's ce_fcn entity.
enum class PushRegisterResult { Ok, AppNotInstalled }

// Live registration state of this device against one shop's ce_fcn entity.
sealed interface PushStatus {
    // This device's row exists; deviceName is what the shop has stored (may be blank/old).
    data class Registered(val deviceName: String?) : PushStatus
    // The push app is installed but this device has no row yet.
    data object NotRegistered : PushStatus
    // The FroshMobilePush app isn't installed in the shop (ce_fcn entity absent).
    data object AppNotInstalled : PushStatus
    // Couldn't determine (network error, missing read privilege, etc.).
    data object Unavailable : PushStatus
}

fun ConnectedShop.plainAuth(): PlainAuth = when (val a = auth) {
    // A failed decrypt (e.g. data restored to a device without the AndroidKeyStore key)
    // degrades to a blank token → AuthExpired → sign-in-again, never a crash.
    is ShopAuth.Admin -> PlainAuth.RefreshToken(
        runCatching { Crypto.decrypt(a.encRefreshToken) }.getOrDefault("")
    )
    // legacy password auth still works once; the rotation callback migrates it to Admin
    is ShopAuth.Password ->
        runCatching { PlainAuth.Password(a.username, Crypto.decrypt(a.encPassword)) }
            .getOrDefault(PlainAuth.RefreshToken(""))
    // integration support was removed; a blank token makes the first request fail with
    // AuthExpired through the normal async error paths (apiFor must not throw — it is
    // called synchronously from ViewModel init)
    is ShopAuth.Integration, null -> PlainAuth.RefreshToken("")
}
