package de.shyim.shopware.ui.screens

import android.app.Application
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import de.shyim.shopware.R
import de.shyim.shopware.api.Criteria
import de.shyim.shopware.api.ShopApi
import de.shyim.shopware.api.SwEntity
import de.shyim.shopware.data.fmt
import de.shyim.shopware.data.model.ConnectedShop
import de.shyim.shopware.data.model.ShopSnapshot
import de.shyim.shopware.data.model.TopCustomer
import de.shyim.shopware.data.source.customerListCriteria
import de.shyim.shopware.ui.components.InitialsAvatar
import de.shyim.shopware.ui.components.StatTile
import de.shyim.shopware.ui.listing.FilterOption
import de.shyim.shopware.ui.listing.ListingFilter
import de.shyim.shopware.ui.listing.ListingScaffold
import de.shyim.shopware.ui.listing.ListingState
import de.shyim.shopware.ui.listing.ListingViewModel
import de.shyim.shopware.ui.listing.salesChannelFilter

data class CustomerRow(
    val id: String,
    val name: String,
    val orderCount: Int,
    val totalSpend: Double,
)

private fun customerFilters(context: android.content.Context) = listOf(
    ListingFilter.Options(
        "group", context.getString(R.string.customers_filter_group), "group.id",
        loadOptions = { api ->
            api.repository("customer-group").search(
                Criteria()
                    .setLimit(100)
                    .addSorting("name")
                    .addIncludes("customer_group", listOf("id", "name", "translated"))
            ).data.mapNotNull { g -> g.id?.let { FilterOption(it, g.translated("name") ?: "—") } }
        },
    ),
    ListingFilter.Bool(
        "accountStatus", context.getString(R.string.customers_filter_account_status), "active",
        context.getString(R.string.common_active), context.getString(R.string.customers_filter_disabled),
    ),
    ListingFilter.NumberRange("orderCount", context.getString(R.string.customers_filter_order_count), "orderCount"),
    // Filters on the registration channel (customer.salesChannelId), like the admin
    salesChannelFilter(context.getString(R.string.common_sales_channel)),
)

private fun mapCustomer(c: SwEntity): CustomerRow = CustomerRow(
    id = c.id ?: "",
    name = listOfNotNull(c.string("firstName"), c.string("lastName"))
        .joinToString(" ").ifBlank { "—" },
    orderCount = c.int("orderCount") ?: 0,
    totalSpend = c.double("orderTotalAmount") ?: 0.0,
)

class CustomersViewModel(app: Application) : ListingViewModel<CustomerRow>(app) {
    override fun createListing(shop: ConnectedShop, api: ShopApi): ListingState<CustomerRow> = ListingState(
        scope = viewModelScope,
        filters = customerFilters(getApplication()),
        source = { api.repository("customer").search(it) },
        baseCriteria = ::customerListCriteria,
        mapper = ::mapCustomer,
        errorFallback = getApplication<Application>().getString(R.string.listing_request_failed),
    )
}

// On expanded width (tablets) the customer list and detail share a list-detail pane,
// like OrdersTab; compact stays single-pane and routes through navigation.
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun CustomersTab(
    shop: ConnectedShop,
    snapshot: ShopSnapshot?,
    openCustomerRoute: (String) -> Unit,
    onOpenOrder: (String) -> Unit,
) {
    if (!de.shyim.shopware.ui.isExpandedWidth()) {
        CustomersScreen(shop, snapshot, onOpenCustomer = openCustomerRoute)
        return
    }

    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()
    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = {
            AnimatedPane {
                CustomersScreen(shop, snapshot, onOpenCustomer = { customerId ->
                    scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, customerId) }
                })
            }
        },
        detailPane = {
            AnimatedPane {
                val customerId = navigator.currentDestination?.contentKey
                if (customerId == null) {
                    CustomerEmptyDetailHint()
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 32.dp)
                    ) {
                        CustomerDetailScreen(
                            shopId = shop.id,
                            customerId = customerId,
                            onBack = { scope.launch { navigator.navigateBack() } },
                            onOpenOrder = onOpenOrder,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun CustomerEmptyDetailHint() {
    val cs = MaterialTheme.colorScheme
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.Group, contentDescription = null,
                tint = cs.outline,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Text(
                stringResource(R.string.customers_select_customer),
                color = cs.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun CustomersScreen(
    shop: ConnectedShop,
    snapshot: ShopSnapshot?,
    onOpenCustomer: (String) -> Unit = {},
    vm: CustomersViewModel = viewModel(),
) {
    LaunchedEffect(shop.id, shop.languageId) { vm.init(shop) }
    val listing = vm.listing ?: return

    ListingScaffold(
        title = stringResource(R.string.nav_customers),
        state = listing,
        api = vm.api,
        searchPlaceholder = stringResource(R.string.customers_search_placeholder),
        emptyText = stringResource(R.string.customers_empty),
        emptyIcon = Icons.Outlined.Group,
        header = if (snapshot != null && snapshot.topCustomers.isNotEmpty()) {
            { CustomerSummaryTiles(shop, snapshot, listing.total) }
        } else null,
    ) { customer ->
        CustomerCard(
            shop, customer.name, customer.orderCount, customer.totalSpend,
            onClick = customer.id.takeIf { it.isNotBlank() }?.let { id -> { onOpenCustomer(id) } },
        )
    }
}

@Composable
private fun CustomerSummaryTiles(shop: ConnectedShop, snapshot: ShopSnapshot, customerCount: Int) {
    val cs = MaterialTheme.colorScheme
    val topSpend = snapshot.topCustomers.maxOf { it.totalSpend }
    val repeatBuyers = snapshot.topCustomers.count { it.orderCount >= 2 }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatTile(
            Icons.Outlined.Group, "$customerCount", stringResource(R.string.nav_customers),
            modifier = Modifier.weight(1f),
            container = cs.primaryContainer, content = cs.onPrimaryContainer, contentVariant = cs.onPrimaryContainer
        )
        StatTile(Icons.Outlined.Payments, shop.fmt(topSpend), stringResource(R.string.customers_top_spender), modifier = Modifier.weight(1f))
        StatTile(Icons.Outlined.Repeat, "$repeatBuyers", stringResource(R.string.customers_repeat_buyers), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CustomerCard(
    shop: ConnectedShop,
    name: String,
    orderCount: Int,
    totalSpend: Double,
    onClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    Surface(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        shape = RoundedCornerShape(18.dp),
        color = cs.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            InitialsAvatar(name, size = 44.dp, bg = shop.tint.bg(dark), fg = shop.tint.fg(dark))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, color = cs.onSurface, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    pluralStringResource(R.plurals.customers_order_count, orderCount, orderCount),
                    color = cs.onSurfaceVariant, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    shop.fmt(totalSpend),
                    color = cs.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.customers_spent), color = cs.onSurfaceVariant, fontSize = 10.5.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

