package de.shyim.shopware.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.shyim.shopware.R
import de.shyim.shopware.data.fmt
import de.shyim.shopware.data.model.ConnectedShop
import de.shyim.shopware.data.model.KpiState
import de.shyim.shopware.data.model.KpiType
import de.shyim.shopware.ui.components.RankBar
import de.shyim.shopware.ui.components.ScreenTitle
import de.shyim.shopware.ui.components.SectionHeader
import de.shyim.shopware.ui.isExpandedWidth
import de.shyim.shopware.ui.screens.reports.AnalyticsFilterBar
import de.shyim.shopware.ui.screens.reports.BreakdownCard
import de.shyim.shopware.ui.screens.reports.SingleNumberCard
import de.shyim.shopware.ui.screens.reports.TrendCard
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ReportsScreen(
    shop: ConnectedShop,
    allShops: List<ConnectedShop>,
    // Bumped by the host's pull-to-refresh; each new value re-fetches the KPIs.
    refreshSignal: Int = 0,
    vm: ReportsViewModel = viewModel(),
) {
    LaunchedEffect(shop.id, shop.languageId) { vm.init(shop, allShops) }
    // Skip the initial value (init() already loaded); reload only on later pulls.
    LaunchedEffect(refreshSignal) { if (refreshSignal > 0) vm.reload() }

    ScreenTitle(stringResource(R.string.nav_reports))

    AnalyticsFilterBar(filters = vm.filters, options = vm.options, onChange = vm::applyFilters)

    val expanded = isExpandedWidth()
    // Time-series cards first (full width), then the breakdown grid, then single number.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        fun st(t: KpiType): KpiState? = vm.kpiStates[t]
        // resolve the format templates once, then format off the UI/composable path
        val ordersTpl = stringResource(R.string.reports_orders_n)
        val soldTpl = stringResource(R.string.reports_sold_qty)
        val ordersLabel = { v: Double -> ordersTpl.format(v.toLong().toString()) }
        val soldLabel = { v: Double -> soldTpl.format(v.toLong().toString()) }

        TrendCard(stringResource(R.string.reports_kpi_total_sales), shop, st(KpiType.TotalSales), { vm.retry(KpiType.TotalSales) })

        Pair2(expanded,
            { m -> TrendCard(stringResource(R.string.reports_kpi_order_count), shop, st(KpiType.OrderCount), { vm.retry(KpiType.OrderCount) }, m) },
            { m -> TrendCard(stringResource(R.string.reports_kpi_avg_order_value), shop, st(KpiType.AvgOrderValue), { vm.retry(KpiType.AvgOrderValue) }, m) },
        )
        Pair2(expanded,
            { m -> TrendCard(stringResource(R.string.reports_kpi_new_customers), shop, st(KpiType.NewCustomers), { vm.retry(KpiType.NewCustomers) }, m) },
            { m -> SingleNumberCard(stringResource(R.string.reports_kpi_customer_count), shop, st(KpiType.CustomerCount), { vm.retry(KpiType.CustomerCount) }, m) },
        )
        Pair2(expanded,
            { m -> BreakdownCard(stringResource(R.string.reports_kpi_sales_channel), shop, st(KpiType.SalesChannel), ordersLabel, { vm.retry(KpiType.SalesChannel) }, m) },
            { m -> BreakdownCard(stringResource(R.string.reports_kpi_best_selling), shop, st(KpiType.BestSellingProduct), soldLabel, { vm.retry(KpiType.BestSellingProduct) }, m) },
        )
        Pair2(expanded,
            { m -> BreakdownCard(stringResource(R.string.reports_kpi_payment_method), shop, st(KpiType.PaymentMethod), ordersLabel, { vm.retry(KpiType.PaymentMethod) }, m) },
            { m -> BreakdownCard(stringResource(R.string.reports_kpi_shipping_method), shop, st(KpiType.ShippingMethod), ordersLabel, { vm.retry(KpiType.ShippingMethod) }, m) },
        )
        Pair2(expanded,
            { m -> BreakdownCard(stringResource(R.string.reports_kpi_country), shop, st(KpiType.Country), ordersLabel, { vm.retry(KpiType.Country) }, m) },
            { m -> BreakdownCard(stringResource(R.string.reports_kpi_manufacturer), shop, st(KpiType.Manufacturer), soldLabel, { vm.retry(KpiType.Manufacturer) }, m) },
        )
        BreakdownCard(stringResource(R.string.reports_kpi_promotion_code), shop, st(KpiType.PromotionCode), ordersLabel, { vm.retry(KpiType.PromotionCode) })
    }

    // Cross-shop comparison for multi-shop owners — revenue for the selected range.
    CrossShopComparison(allShops, vm.shopRevenues)
}

// Lay two cards side-by-side on expanded width (each gets weight(1f)), stacked full-width on compact.
@Composable
private fun Pair2(
    expanded: Boolean,
    left: @Composable (Modifier) -> Unit,
    right: @Composable (Modifier) -> Unit,
) {
    if (expanded) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.runtime.key(0) { Column(modifier = Modifier.weight(1f)) { left(Modifier) } }
            androidx.compose.runtime.key(1) { Column(modifier = Modifier.weight(1f)) { right(Modifier) } }
        }
    } else {
        left(Modifier)
        right(Modifier)
    }
}

@Composable
private fun CrossShopComparison(allShops: List<ConnectedShop>, revenues: Map<String, Double?>) {
    val cs = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    // Only shops the comparison is loading/loaded for (size>1 gate matches the ViewModel).
    val comparable = allShops.filter { revenues.containsKey(it.id) }
    if (comparable.size <= 1) return
    SectionHeader(stringResource(R.string.reports_compare_shops), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp))
    // Known revenues drive ranking + bar scale; still-loading shops sort last and show a dash.
    val ranked = comparable.sortedByDescending { revenues[it.id] ?: -1.0 }
    val maxRev = comparable.mapNotNull { revenues[it.id] }.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ranked.forEachIndexed { i, s ->
            val rev = revenues[s.id]
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                Text("${i + 1}", color = cs.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 3.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(modifier = Modifier.padding(bottom = 6.dp)) {
                        Text(s.name, color = cs.onSurface, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(rev?.let { s.fmt(it) } ?: "—", color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    RankBar(fraction = ((rev ?: 0.0) / maxRev).toFloat(), fill = s.tint.fg(dark))
                }
            }
        }
    }
}
