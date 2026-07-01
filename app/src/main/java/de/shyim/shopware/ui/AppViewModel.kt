package de.shyim.shopware.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.shyim.shopware.ShopwareApp
import de.shyim.shopware.data.PushStatus
import de.shyim.shopware.data.userMessage
import de.shyim.shopware.data.model.AppData
import de.shyim.shopware.data.model.ConnectedShop
import de.shyim.shopware.data.model.ProductFieldConfig
import de.shyim.shopware.api.LanguageOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class SyncState {
    data object Idle : SyncState()
    data object Syncing : SyncState()
    data class Error(val message: String) : SyncState()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val app = app as ShopwareApp
    private val repo = this.app.repository

    // null while the store is loading on first composition
    val data: StateFlow<AppData?> = repo.data.map { it as AppData? }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _sync = MutableStateFlow<Map<String, SyncState>>(emptyMap())
    val sync: StateFlow<Map<String, SyncState>> = _sync.asStateFlow()

    private fun errorMessage(e: Throwable): String = e.userMessage(getApplication<Application>())

    fun refresh(shopId: String) {
        if (_sync.value[shopId] == SyncState.Syncing) return
        _sync.value += (shopId to SyncState.Syncing)
        viewModelScope.launch {
            repo.refresh(shopId).fold(
                onSuccess = { _sync.value += (shopId to SyncState.Idle) },
                onFailure = { e -> _sync.value += (shopId to SyncState.Error(errorMessage(e))) }
            )
        }
    }

    fun refreshIfStale(shop: ConnectedShop, maxAgeMs: Long = 5 * 60_000) {
        val snap = data.value?.snapshots?.get(shop.id)
        if (snap == null || System.currentTimeMillis() - snap.lastSyncEpochMs > maxAgeMs) {
            refresh(shop.id)
        }
    }

    suspend fun languagesFor(shop: ConnectedShop) = repo.languagesFor(shop)

    fun setShopLanguage(shopId: String, language: LanguageOption?) = viewModelScope.launch {
        _sync.value += (shopId to SyncState.Syncing)
        runCatching { repo.setShopLanguage(shopId, language) }.fold(
            onSuccess = { _sync.value += (shopId to SyncState.Idle) },
            onFailure = { e -> _sync.value += (shopId to SyncState.Error(errorMessage(e))) }
        )
    }

    // Sign-in-again from shop settings; onResult(null) on success, error text otherwise
    fun reauthenticate(
        shop: ConnectedShop,
        username: String,
        password: String,
        onResult: (String?) -> Unit,
    ) = viewModelScope.launch {
        runCatching { repo.reauthenticate(shop, username, password) }.fold(
            onSuccess = {
                _sync.value += (shop.id to SyncState.Idle)
                onResult(null)
            },
            onFailure = { e -> onResult(errorMessage(e)) }
        )
    }


    fun selectShop(id: String) = viewModelScope.launch { repo.selectShop(id) }
    fun removeShop(id: String) = viewModelScope.launch { repo.removeShop(id) }

    // ---- Per-shop order push (ce_fcn registration), driven from shop settings ----
    suspend fun pushStatus(shop: ConnectedShop): PushStatus = repo.pushStatusForShop(shop)

    // Register this device with the shop and return the resulting live status.
    suspend fun enablePush(shop: ConnectedShop): PushStatus = app.enablePushForShop(shop)

    // Remove this device's registration, then return the refreshed status.
    suspend fun disablePush(shop: ConnectedShop): PushStatus {
        repo.unregisterPushForShop(shop)
        return repo.pushStatusForShop(shop)
    }

    fun updateShopSettings(
        shopId: String,
        name: String,
        tintIndex: Int,
        dailyTarget: Double?,
        lowStockThreshold: Int,
    ) = viewModelScope.launch {
        repo.updateShopSettings(shopId, name, tintIndex, dailyTarget, lowStockThreshold)
    }

    fun updateProductFields(shopId: String, config: ProductFieldConfig) = viewModelScope.launch {
        repo.updateProductFields(shopId, config)
    }
}
