package de.shyim.shopware.widget

import android.content.Context
import de.shyim.shopware.data.delta
import de.shyim.shopware.data.fmt
import de.shyim.shopware.data.model.AppData
import de.shyim.shopware.data.model.ConnectedShop
import de.shyim.shopware.data.model.ShopSnapshot
import kotlinx.serialization.json.Json
import java.io.File

// Today widget state, computed off the cached files only.
data class WidgetState(
    val shopName: String,
    val revenueText: String,
    val deltaLabel: String,
    val deltaUp: Boolean,
    val targetPct: Float?, // null when no daily target
    val targetText: String?,
    val lastSyncMs: Long,
    val tintIndex: Int,
)

// Weekly widget state: the 7-day total + per-day bars (today highlighted).
data class WeeklyWidgetState(
    val shopName: String,
    val weekTotalText: String,
    val deltaLabel: String, // second half of the week vs first half
    val deltaUp: Boolean,
    val bars: List<Float>, // each value normalized 0..1 against the week max
    val todayIndex: Int,
    val lastSyncMs: Long,
)

// The widget runs outside the app process, so it reads the cache directly instead of
// going through AppRepository/DataStore: app-data.json is plain JSON (written by
// AppDataSerializer) and snapshots are per-shop files. All synchronous, no Compose.
object WidgetData {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun readSelectedShopSnapshot(context: Context): WidgetState? =
        readSelectedShopSnapshot(context.filesDir)

    fun readWeeklySnapshot(context: Context): WeeklyWidgetState? =
        readWeeklySnapshot(context.filesDir)

    // filesDir overloads so the mapping is unit-testable without an Android Context
    private fun load(filesDir: File): Pair<ConnectedShop, ShopSnapshot>? {
        val appDataFile = File(filesDir, "datastore/app-data.json")
        if (!appDataFile.exists()) return null
        val data = json.decodeFromString(AppData.serializer(), appDataFile.readText())
        val shop = data.shops.firstOrNull { it.id == data.selectedShopId }
            ?: data.shops.firstOrNull()
            ?: return null
        val snapFile = File(filesDir, "snapshots/${shop.id}.json")
        if (!snapFile.exists()) return null
        return shop to json.decodeFromString(ShopSnapshot.serializer(), snapFile.readText())
    }

    fun readSelectedShopSnapshot(filesDir: File): WidgetState? = runCatching {
        val (shop, snap) = load(filesDir) ?: return null
        val d = delta(snap.todayRevenue, snap.yesterdayRevenue)
        val targetPct = shop.dailyTarget?.takeIf { it > 0 }
            ?.let { (snap.todayRevenue / it).toFloat().coerceIn(0f, 1f) }
        WidgetState(
            shopName = shop.name,
            revenueText = shop.fmt(snap.todayRevenue),
            deltaLabel = d.label,
            deltaUp = d.up,
            targetPct = targetPct,
            targetText = shop.dailyTarget?.takeIf { it > 0 }?.let { shop.fmt(it) },
            lastSyncMs = snap.lastSyncEpochMs,
            tintIndex = shop.tintIndex,
        )
    }.getOrNull()

    fun readWeeklySnapshot(filesDir: File): WeeklyWidgetState? = runCatching {
        val (shop, snap) = load(filesDir) ?: return null
        val week = snap.weekRevenue
        if (week.isEmpty()) return null
        val total = week.sum()
        val max = week.maxOrNull() ?: 0.0
        val bars = week.map { if (max > 0) (it / max).toFloat() else 0f }
        // momentum: 2nd half of the week vs 1st half (same as the in-app Reports delta)
        val half = week.size / 2
        val firstHalf = week.take(half).sum()
        val secondHalf = week.drop(week.size - half).sum()
        val d = delta(secondHalf, firstHalf)
        WeeklyWidgetState(
            shopName = shop.name,
            weekTotalText = shop.fmt(total),
            deltaLabel = d.label,
            deltaUp = d.up,
            bars = bars,
            todayIndex = snap.todayIndex.coerceIn(0, week.lastIndex),
            lastSyncMs = snap.lastSyncEpochMs,
        )
    }.getOrNull()
}
