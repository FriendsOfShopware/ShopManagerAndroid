package de.shyim.shopware.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.material3.Icon
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.shyim.shopware.R
import de.shyim.shopware.api.Criteria
import de.shyim.shopware.api.ShopApi
import de.shyim.shopware.data.model.ConnectedShop
import de.shyim.shopware.data.model.RecentOrder
import de.shyim.shopware.data.model.ShopSnapshot
import de.shyim.shopware.data.source.orderListCriteria
import de.shyim.shopware.data.source.parseOrder
import de.shyim.shopware.ui.components.OrderRow
import de.shyim.shopware.ui.listing.FilterOption
import de.shyim.shopware.ui.listing.FilterValue
import de.shyim.shopware.ui.listing.ListingFilter
import de.shyim.shopware.ui.listing.ListingScaffold
import de.shyim.shopware.ui.listing.ListingState
import de.shyim.shopware.ui.listing.ListingViewModel
import de.shyim.shopware.ui.listing.QuickFilter
import de.shyim.shopware.ui.listing.salesChannelFilter
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

// NOTE: the admin's primaryOrderTransaction/primaryOrderDelivery paths parse on 6.7
// but the denormalized references are not reliably populated, so transactions./deliveries.
// paths are used instead (verified against a live 6.7.8 instance).
private fun stateOptions(machine: String): suspend (ShopApi) -> List<FilterOption> = { api ->
    api.repository("state-machine-state").search(
        Criteria()
            .setLimit(50)
            .addFilter(Criteria.equals("stateMachine.technicalName", machine))
            .addSorting("name")
            .addIncludes("state_machine_state", listOf("id", "name", "translated"))
    ).data.mapNotNull { s -> s.id?.let { FilterOption(it, s.translated("name") ?: "—") } }
}

private fun orderFilters(
    context: android.content.Context,
    orderStateOptions: suspend (ShopApi) -> List<FilterOption>,
): List<ListingFilter> = listOf(
    ListingFilter.Options(
        "orderStatus", context.getString(R.string.orders_filter_order_status), "stateMachineState.id",
        loadOptions = orderStateOptions,
    ),
    ListingFilter.Options(
        "paymentStatus", context.getString(R.string.orders_filter_payment_status), "transactions.stateMachineState.id",
        loadOptions = stateOptions("order_transaction.state"),
    ),
    ListingFilter.Options(
        "deliveryStatus", context.getString(R.string.orders_filter_delivery_status), "deliveries.stateMachineState.id",
        loadOptions = stateOptions("order_delivery.state"),
    ),
    ListingFilter.DateRange("orderDate", context.getString(R.string.orders_filter_order_date), "orderDateTime"),
    ListingFilter.NumberRange("orderValue", context.getString(R.string.orders_filter_order_value), "amountTotal"),
    ListingFilter.Options(
        "paymentMethod", context.getString(R.string.orders_filter_payment_method), "transactions.paymentMethod.id",
        loadOptions = { api ->
            api.repository("payment-method").search(
                Criteria()
                    .setLimit(100)
                    .addFilter(Criteria.equals("active", true))
                    .addSorting("name")
                    .addIncludes("payment_method", listOf("id", "name", "translated"))
            ).data.mapNotNull { m -> m.id?.let { FilterOption(it, m.translated("name") ?: "—") } }
        },
    ),
    salesChannelFilter(context.getString(R.string.common_sales_channel)),
    ListingFilter.Existence(
        "documents", context.getString(R.string.common_documents), "documents.id",
        context.getString(R.string.orders_filter_has_documents),
        context.getString(R.string.orders_filter_no_documents),
    ),
)

// Quick-chip labels in display order, mapped to order-state technical names
private fun quickStates(context: android.content.Context) = listOf(
    context.getString(R.string.orders_quick_open) to "open",
    context.getString(R.string.orders_quick_in_progress) to "in_progress",
    context.getString(R.string.orders_quick_done) to "completed",
    context.getString(R.string.orders_quick_cancelled) to "cancelled",
)

private data class OrderState(val id: String, val name: String, val technical: String)

private suspend fun loadOrderStates(api: ShopApi): List<OrderState> =
    api.repository("state-machine-state").search(
        Criteria()
            .setLimit(50)
            .addFilter(Criteria.equals("stateMachine.technicalName", "order.state"))
            .addSorting("name")
            .addIncludes("state_machine_state", listOf("id", "name", "technicalName", "translated"))
    ).data.mapNotNull { s ->
        OrderState(
            id = s.id ?: return@mapNotNull null,
            name = s.translated("name") ?: "—",
            technical = s.string("technicalName") ?: "",
        )
    }

class OrdersViewModel(app: Application) : ListingViewModel<RecentOrder>(app) {
    var quickFilters by mutableStateOf<List<QuickFilter>>(emptyList())
        private set

    override fun createListing(shop: ConnectedShop, api: ShopApi): ListingState<RecentOrder> {
        // One order-state fetch feeds both the quick chips and the filter sheet
        val orderStates = viewModelScope.async { loadOrderStates(api) }
        quickFilters = emptyList()
        viewModelScope.launch {
            runCatching { orderStates.await() }.onSuccess { states ->
                quickFilters = quickStates(getApplication()).mapNotNull { (label, technical) ->
                    val state = states.firstOrNull { it.technical == technical } ?: return@mapNotNull null
                    QuickFilter("orderStatus", label, FilterValue.OptionsValue(setOf(state.id)))
                }
            }
        }
        return ListingState(
            scope = viewModelScope,
            filters = orderFilters(getApplication()) { freshApi ->
                runCatching { orderStates.await() }.getOrElse { loadOrderStates(freshApi) }
                    .map { FilterOption(it.id, it.name) }
            },
            source = { api.repository("order").search(it) },
            baseCriteria = ::orderListCriteria,
            mapper = { parseOrder(it, System.currentTimeMillis()) },
            errorFallback = getApplication<Application>().getString(R.string.listing_request_failed),
        )
    }
}

@Composable
fun OrdersScreen(
    shop: ConnectedShop,
    snapshot: ShopSnapshot?,
    onOpenOrder: (String) -> Unit,
    vm: OrdersViewModel = viewModel(),
) {
    LaunchedEffect(shop.id, shop.languageId) { vm.init(shop) }
    val listing = vm.listing ?: return

    ListingScaffold(
        title = stringResource(R.string.nav_orders),
        state = listing,
        api = vm.api,
        searchPlaceholder = stringResource(R.string.orders_search_placeholder),
        emptyText = stringResource(R.string.orders_empty),
        emptyIcon = Icons.Outlined.ReceiptLong,
        quickFilters = vm.quickFilters,
    ) { order -> OrderRow(shop, order, onClick = onOpenOrder) }
}


// Compact width: order rows push the order-detail route. Expanded width:
// list-detail panes side by side, detail driven by the pane navigator.
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun OrdersTab(
    shop: ConnectedShop,
    snapshot: ShopSnapshot?,
    openOrderRoute: (String) -> Unit,
) {
    if (!de.shyim.shopware.ui.isExpandedWidth()) {
        OrdersScreen(shop, snapshot, onOpenOrder = openOrderRoute)
        return
    }

    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()
    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = {
            AnimatedPane {
                OrdersScreen(shop, snapshot, onOpenOrder = { orderId ->
                    scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, orderId) }
                })
            }
        },
        detailPane = {
            AnimatedPane {
                val orderId = navigator.currentDestination?.contentKey
                if (orderId == null) {
                    EmptyDetailHint()
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 32.dp)
                    ) {
                        OrderDetailScreen(
                            shopId = shop.id,
                            orderId = orderId,
                            onBack = { scope.launch { navigator.navigateBack() } },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun EmptyDetailHint() {
    val cs = MaterialTheme.colorScheme
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.ViewSidebar, contentDescription = null,
                tint = cs.outline, modifier = Modifier.padding(bottom = 12.dp)
            )
            Text(
                stringResource(R.string.orders_select_order),
                color = cs.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.Medium
            )
        }
    }
}
