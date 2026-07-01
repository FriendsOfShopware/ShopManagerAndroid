package de.shyim.shopware.ui.screens.reports

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.shyim.shopware.R
import de.shyim.shopware.data.model.AnalyticsFilters
import de.shyim.shopware.data.model.DateRange
import de.shyim.shopware.data.source.AnalyticsFilterOptions
import de.shyim.shopware.data.source.FilterOptionItem
import de.shyim.shopware.ui.screens.ReportsViewModel

// Sticky filter row: date-range preset chips + a "Filters (n)" chip opening the multi-select sheet.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsFilterBar(
    filters: AnalyticsFilters,
    options: AnalyticsFilterOptions?,
    onChange: (AnalyticsFilters) -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }

    val presets = listOf(
        DateRange.Preset.Today to R.string.reports_range_today,
        DateRange.Preset.Last7 to R.string.reports_range_7,
        DateRange.Preset.Last30 to R.string.reports_range_30,
        DateRange.Preset.Last90 to R.string.reports_range_90,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { (preset, label) ->
            FilterChip(
                selected = filters.dateRange.preset == preset,
                onClick = { onChange(filters.copy(dateRange = ReportsViewModel.rangeFor(preset))) },
                label = { Text(stringResource(label)) },
            )
        }
        val active = filters.activeCount
        FilterChip(
            selected = active > 0,
            onClick = { showSheet = true },
            leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(18.dp)) },
            label = {
                Text(
                    if (active > 0) stringResource(R.string.reports_filters_count, active)
                    else stringResource(R.string.reports_filters)
                )
            },
        )
    }

    if (showSheet) {
        AnalyticsFilterSheet(
            filters = filters,
            options = options,
            onApply = { onChange(it); showSheet = false },
            onDismiss = { showSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalyticsFilterSheet(
    filters: AnalyticsFilters,
    options: AnalyticsFilterOptions?,
    onApply: (AnalyticsFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var draft by remember { mutableStateOf(filters) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .imePadding()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 36.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.reports_filters), color = cs.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { draft = filters.copy(salesChannelIds = emptyList(), orderStateIds = emptyList(), paymentStateIds = emptyList(), deliveryStateIds = emptyList(), customerGroupIds = emptyList(), countryIds = emptyList()) }) {
                    Text(stringResource(R.string.reports_filters_clear), fontSize = 13.sp)
                }
            }

            options?.let { o ->
                FilterGroup(stringResource(R.string.reports_filter_sales_channel), o.salesChannels, draft.salesChannelIds) { draft = draft.copy(salesChannelIds = it) }
                FilterGroup(stringResource(R.string.reports_filter_order_state), o.orderStates, draft.orderStateIds) { draft = draft.copy(orderStateIds = it) }
                FilterGroup(stringResource(R.string.reports_filter_payment_state), o.paymentStates, draft.paymentStateIds) { draft = draft.copy(paymentStateIds = it) }
                FilterGroup(stringResource(R.string.reports_filter_delivery_state), o.deliveryStates, draft.deliveryStateIds) { draft = draft.copy(deliveryStateIds = it) }
                FilterGroup(stringResource(R.string.reports_filter_customer_group), o.customerGroups, draft.customerGroupIds) { draft = draft.copy(customerGroupIds = it) }
                FilterGroup(stringResource(R.string.reports_filter_country), o.countries, draft.countryIds) { draft = draft.copy(countryIds = it) }
            }

            androidx.compose.material3.Button(
                onClick = { onApply(draft) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
            ) { Text(stringResource(R.string.common_apply)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(
    title: String,
    items: List<FilterOptionItem>,
    selected: List<String>,
    onChange: (List<String>) -> Unit,
) {
    if (items.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    Column {
        Text(title, color = cs.onSurfaceVariant, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEach { item ->
                val isSel = item.id in selected
                FilterChip(
                    selected = isSel,
                    onClick = { onChange(if (isSel) selected - item.id else selected + item.id) },
                    label = { Text(item.label) },
                )
            }
        }
    }
}
