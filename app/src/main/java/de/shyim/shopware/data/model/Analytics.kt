package de.shyim.shopware.data.model

// Live analytics results for the Reports tab — fetched on demand, never persisted to the snapshot.
// Modeled on the official Shopware Analytics app (SwagAnalytics) KPI set; the 12 KPIs that are
// achievable through the Admin API (the 4 storefront-tracking ones need a separate gateway).

// One point in a date histogram.
data class TimePoint(val epochMs: Long, val value: Double)

// A time-series KPI (chart over date) with a headline total and the prior equal-length window total
// for a delta badge. `isMoney` drives money vs count formatting in the UI.
data class TimeSeriesKpi(
    val points: List<TimePoint>,
    val total: Double,
    val previousTotal: Double?,
    val isMoney: Boolean,
)

// One row of a terms breakdown (top-N). `value` is what the bar encodes (revenue or quantity);
// `secondary` carries a companion number (e.g. order count) when both matter.
data class BreakdownRow(
    val id: String,
    val label: String,
    val value: Double,
    val secondary: Double? = null,
)

// A terms-breakdown KPI (horizontal bars). `valueIsMoney` drives formatting of `value`.
data class BreakdownKpi(
    val rows: List<BreakdownRow>,
    val valueIsMoney: Boolean,
)

// A single headline number with an optional prior-window value for a delta.
data class SingleKpi(
    val value: Double,
    val previousValue: Double?,
    val isMoney: Boolean,
)

// The 12 Admin-API KPIs. Order roughly follows SwagAnalytics' kpi-registry positions.
enum class KpiType {
    TotalSales,        // time-series, money
    OrderCount,        // time-series, count
    AvgOrderValue,     // time-series, money
    NewCustomers,      // time-series, count
    SalesChannel,      // breakdown, money
    PaymentMethod,     // breakdown, count
    ShippingMethod,    // breakdown, count
    Country,           // breakdown, count
    BestSellingProduct,// breakdown, quantity
    Manufacturer,      // breakdown, quantity
    PromotionCode,     // breakdown, count
    CustomerCount,     // single number
}

// Per-card state so each KPI loads/fails independently.
sealed interface KpiState {
    data object Loading : KpiState
    data class Error(val message: String) : KpiState
    data class TimeSeries(val data: TimeSeriesKpi) : KpiState
    data class Breakdown(val data: BreakdownKpi) : KpiState
    data class Single(val data: SingleKpi) : KpiState
}

// ---- Filters (shared filter bar driving every order-based card) ----

enum class HistogramInterval(val apiValue: String) { Day("day"), Week("week"), Month("month") }

// A resolved date window in epoch millis [fromMs, toMs). `preset` is for the UI chip selection.
data class DateRange(
    val preset: Preset,
    val fromMs: Long,
    val toMs: Long,
) {
    enum class Preset { Today, Last7, Last30, Last90, Custom }

    // day buckets for short ranges, week beyond a month, month beyond a quarter
    val interval: HistogramInterval
        get() {
            val days = (toMs - fromMs) / 86_400_000L
            return when {
                days <= 31 -> HistogramInterval.Day
                days <= 92 -> HistogramInterval.Week
                else -> HistogramInterval.Month
            }
        }
}

data class AnalyticsFilters(
    val dateRange: DateRange,
    val salesChannelIds: List<String> = emptyList(),
    val orderStateIds: List<String> = emptyList(),       // stateMachineState.technicalName
    val paymentStateIds: List<String> = emptyList(),     // transactions.stateMachineState.technicalName
    val deliveryStateIds: List<String> = emptyList(),    // deliveries.stateMachineState.technicalName
    val customerGroupIds: List<String> = emptyList(),
    val countryIds: List<String> = emptyList(),
) {
    // Number of non-date filters active (for the "Filters (n)" chip).
    val activeCount: Int
        get() = salesChannelIds.size + orderStateIds.size + paymentStateIds.size +
            deliveryStateIds.size + customerGroupIds.size + countryIds.size
}
