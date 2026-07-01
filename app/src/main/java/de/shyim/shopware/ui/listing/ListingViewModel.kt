package de.shyim.shopware.ui.listing

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import de.shyim.shopware.ShopwareApp
import de.shyim.shopware.api.ShopApi
import de.shyim.shopware.data.model.ConnectedShop

// Shared api/listing wiring for the listing tabs; re-inits when the shop or its language changes
abstract class ListingViewModel<T>(app: Application) : AndroidViewModel(app) {
    protected val repo = (app as ShopwareApp).repository

    var api by mutableStateOf<ShopApi?>(null)
        private set
    var listing by mutableStateOf<ListingState<T>?>(null)
        private set

    private var key: Pair<String, String?>? = null

    fun init(shop: ConnectedShop) {
        if (key == shop.id to shop.languageId) return
        key = shop.id to shop.languageId
        val shopApi = repo.apiFor(shop)
        api = shopApi
        listing = createListing(shop, shopApi).also { it.reload() }
    }

    protected abstract fun createListing(shop: ConnectedShop, api: ShopApi): ListingState<T>
}
