package de.shyim.shopware.ui.screens

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.shyim.shopware.ShopwareApp
import de.shyim.shopware.api.runCatchingCancellable
import de.shyim.shopware.data.userMessage
import de.shyim.shopware.data.model.AnalyticsFilters
import de.shyim.shopware.data.model.ConnectedShop
import de.shyim.shopware.data.model.DateRange
import de.shyim.shopware.data.model.KpiState
import de.shyim.shopware.data.model.KpiType
import de.shyim.shopware.data.source.AnalyticsFilterOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime

class ReportsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as ShopwareApp).repository

    private var key: Pair<String, String?>? = null
    var shop by mutableStateOf<ConnectedShop?>(null)
        private set

    var filters by mutableStateOf(defaultFilters())
        private set
    val kpiStates = mutableStateMapOf<KpiType, KpiState>()
    var options by mutableStateOf<AnalyticsFilterOptions?>(null)
        private set

    // Per-shop revenue for the selected range, keyed by shop id — drives the cross-shop
    // comparison so it tracks the period (null entry = still loading / failed).
    val shopRevenues = mutableStateMapOf<String, Double?>()
    private var comparableShops: List<ConnectedShop> = emptyList()

    private var generation = 0
    private var debounceJob: Job? = null

    fun init(shop: ConnectedShop, allShops: List<ConnectedShop> = listOf(shop)) {
        comparableShops = allShops
        if (key == shop.id to shop.languageId) return
        key = shop.id to shop.languageId
        this.shop = shop
        filters = defaultFilters()
        viewModelScope.launch {
            options = runCatchingCancellable { repo.analyticsFilterOptions(shop) }.getOrNull()
        }
        reload()
    }

    fun applyFilters(new: AnalyticsFilters) {
        filters = new
        // debounce so toggling several filters in the sheet doesn't fire 12×N requests
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(300)
            reload()
        }
    }

    fun reload() {
        val s = shop ?: return
        val gen = ++generation
        val f = filters
        for (type in KpiType.entries) {
            kpiStates[type] = KpiState.Loading
            viewModelScope.launch {
                val result = runCatchingCancellable { repo.loadKpi(s, type, f) }.getOrElse {
                    KpiState.Error(it.userMessage(getApplication<Application>()))
                }
                if (gen == generation) kpiStates[type] = result // drop stale results
            }
        }
        // Cross-shop comparison: re-fetch each shop's revenue for the selected range.
        if (comparableShops.size > 1) {
            for (cs in comparableShops) {
                shopRevenues[cs.id] = null // loading
                viewModelScope.launch {
                    val rev = runCatchingCancellable { repo.loadShopRevenue(cs, f) }.getOrNull()
                    if (gen == generation) shopRevenues[cs.id] = rev
                }
            }
        }
    }

    fun retry(type: KpiType) {
        val s = shop ?: return
        val gen = generation
        val f = filters
        kpiStates[type] = KpiState.Loading
        viewModelScope.launch {
            val result = runCatchingCancellable { repo.loadKpi(s, type, f) }.getOrElse {
                KpiState.Error(it.userMessage(getApplication<Application>()))
            }
            if (gen == generation) kpiStates[type] = result
        }
    }

    private fun defaultFilters(): AnalyticsFilters = AnalyticsFilters(dateRange = rangeFor(DateRange.Preset.Last7))

    companion object {
        // Resolve a preset to a [fromMs, toMs) window at local midnight boundaries.
        fun rangeFor(preset: DateRange.Preset, zone: ZoneId = ZoneId.systemDefault()): DateRange {
            val nowMs = System.currentTimeMillis()
            val startOfTomorrow = ZonedDateTime.now(zone).toLocalDate().plusDays(1).atStartOfDay(zone)
            val toMs = startOfTomorrow.toInstant().toEpochMilli() // exclusive upper bound = end of today
            val days = when (preset) {
                DateRange.Preset.Today -> 1L
                DateRange.Preset.Last7 -> 7L
                DateRange.Preset.Last30 -> 30L
                DateRange.Preset.Last90 -> 90L
                DateRange.Preset.Custom -> 7L
            }
            val fromMs = startOfTomorrow.minusDays(days).toInstant().toEpochMilli()
            return DateRange(preset, fromMs, toMs).let { if (preset == DateRange.Preset.Custom) it.copy(toMs = nowMs) else it }
        }
    }
}
