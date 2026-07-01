package de.shyim.shopware.ui.screens

import android.app.Application
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.shyim.shopware.R
import de.shyim.shopware.ShopwareApp
import de.shyim.shopware.api.Criteria
import de.shyim.shopware.data.fmt
import de.shyim.shopware.data.model.ConnectedShop
import de.shyim.shopware.data.model.CustomerDetail
import de.shyim.shopware.data.model.RecentOrder
import de.shyim.shopware.data.source.orderListCriteria
import de.shyim.shopware.data.source.parseOrder
import de.shyim.shopware.ui.components.BadgeTone
import de.shyim.shopware.ui.components.InitialsAvatar
import de.shyim.shopware.ui.components.OrderRow
import de.shyim.shopware.ui.components.StatTile
import de.shyim.shopware.ui.components.StatusBadge
import de.shyim.shopware.ui.listing.ListingState
import de.shyim.shopware.ui.relativeAgoText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed class CustomerUi {
    data object Loading : CustomerUi()
    data class Error(val message: String) : CustomerUi()
    data class Ready(val detail: CustomerDetail) : CustomerUi()
}

class CustomerDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as ShopwareApp).repository

    var ui by mutableStateOf<CustomerUi>(CustomerUi.Loading)
        private set
    var shop by mutableStateOf<ConnectedShop?>(null)
        private set
    var orders by mutableStateOf<ListingState<RecentOrder>?>(null)
        private set

    fun load(shopId: String, customerId: String) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            ui = CustomerUi.Loading
            val s = repo.data.first().shops.firstOrNull { it.id == shopId }
            if (s == null) {
                ui = CustomerUi.Error(app.getString(R.string.common_shop_not_found))
                return@launch
            }
            shop = s
            val api = runCatching { repo.apiFor(s) }.getOrNull()
            if (api == null) {
                ui = CustomerUi.Error(app.getString(R.string.customer_detail_not_connected))
                return@launch
            }
            ui = runCatching { repo.customerDetail(s, customerId) }.fold(
                onSuccess = {
                    it?.let { d -> CustomerUi.Ready(d) }
                        ?: CustomerUi.Error(app.getString(R.string.customer_detail_not_found))
                },
                onFailure = { CustomerUi.Error(it.message ?: app.getString(R.string.customer_detail_load_failed)) }
            )
            if (ui is CustomerUi.Ready && orders == null) {
                orders = ListingState(
                    scope = viewModelScope,
                    pageSize = 10,
                    source = { api.repository("order").search(it) },
                    baseCriteria = {
                        orderListCriteria().addFilter(Criteria.equals("orderCustomer.customerId", customerId))
                    },
                    mapper = { parseOrder(it, System.currentTimeMillis()) },
                    errorFallback = app.getString(R.string.listing_request_failed),
                ).also { it.reload() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CustomerDetailScreen(
    shopId: String,
    customerId: String,
    onBack: () -> Unit,
    onOpenOrder: (String) -> Unit,
    vm: CustomerDetailViewModel = viewModel(),
) {
    val cs = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current

    LaunchedEffect(shopId, customerId) { vm.load(shopId, customerId) }

    var editing by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = cs.onSurface)
        }
        Text(stringResource(R.string.customer_detail_title), color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.weight(1f))
        if (vm.ui is CustomerUi.Ready) {
            IconButton(onClick = { editing = true }) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.customer_edit_title), tint = cs.primary)
            }
        }
    }

    if (editing) {
        (vm.ui as? CustomerUi.Ready)?.let { ready ->
            val shop = vm.shop
            if (shop != null) {
                CustomerEditSheet(
                    shop = shop,
                    detail = ready.detail,
                    onDismiss = { editing = false },
                    onSaved = {
                        editing = false
                        vm.load(shopId, customerId)
                    },
                )
            }
        }
    }

    when (val ui = vm.ui) {
        CustomerUi.Loading -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 100.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LoadingIndicator(modifier = Modifier.size(56.dp))
        }
        is CustomerUi.Error -> SyncErrorCard(ui.message, onRetry = { vm.load(shopId, customerId) })
        is CustomerUi.Ready -> {
            val d = ui.detail
            val shop = vm.shop ?: return

            // Identity header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                InitialsAvatar(d.name, size = 56.dp, bg = shop.tint.bg(dark), fg = shop.tint.fg(dark))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Text(
                            d.name,
                            color = cs.onSurface, fontSize = 21.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp
                        )
                        if (d.guest) StatusBadge(BadgeTone.Neutral, stringResource(R.string.customer_detail_guest))
                        if (!d.active) StatusBadge(BadgeTone.Bad, stringResource(R.string.customers_filter_disabled))
                    }
                    Text(
                        listOfNotNull(
                            d.customerNumber.takeIf { it.isNotBlank() },
                            d.group,
                            d.customerSince?.let { stringResource(R.string.customer_detail_since, it) },
                        ).joinToString(" · "),
                        color = cs.onSurfaceVariant, fontSize = 12.5.sp,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                if (d.email.isNotBlank()) {
                    IconButton(onClick = {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_SENDTO,
                                android.net.Uri.parse("mailto:${d.email}")
                            )
                        )
                    }) {
                        Icon(Icons.Outlined.MailOutline, contentDescription = stringResource(R.string.customer_detail_email_customer), tint = cs.primary)
                    }
                }
                d.phone?.let { phone ->
                    IconButton(onClick = {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_DIAL,
                                android.net.Uri.parse("tel:$phone")
                            )
                        )
                    }) {
                        Icon(Icons.Outlined.Call, contentDescription = stringResource(R.string.customer_detail_call_customer), tint = cs.primary)
                    }
                }
            }

            // Stats
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatTile(
                    Icons.Outlined.Payments, shop.fmt(d.totalSpend), stringResource(R.string.customer_detail_total_spent),
                    modifier = Modifier.weight(1f),
                    container = cs.primaryContainer, content = cs.onPrimaryContainer, contentVariant = cs.onPrimaryContainer
                )
                StatTile(Icons.Outlined.ReceiptLong, "${d.orderCount}", stringResource(R.string.nav_orders), modifier = Modifier.weight(1f))
                StatTile(Icons.Outlined.Schedule, d.lastOrderMs?.let { relativeAgoText(it) } ?: "—", stringResource(R.string.customer_detail_last_order), modifier = Modifier.weight(1f))
            }

            // Contact & addresses
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = cs.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.customer_detail_contact), color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (d.email.isNotBlank()) {
                        Text(d.email, color = cs.onSurface, fontSize = 13.sp)
                    }
                    d.phone?.let { Text(it, color = cs.onSurface, fontSize = 13.sp) }
                    d.billingAddress?.let { addr ->
                        Column {
                            Text(stringResource(R.string.order_detail_billing_address), color = cs.onSurfaceVariant, fontSize = 12.sp)
                            Text(
                                addr, color = cs.onSurface, fontSize = 13.sp, lineHeight = 18.sp,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                    }
                    d.shippingAddress?.let { addr ->
                        Column {
                            Text(stringResource(R.string.order_detail_shipping_address), color = cs.onSurfaceVariant, fontSize = 12.sp)
                            Text(
                                addr, color = cs.onSurface, fontSize = 13.sp, lineHeight = 18.sp,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                    }
                }
            }

            // Order history
            val orders = vm.orders
            if (orders != null) {
                Text(
                    if (orders.total > 0) stringResource(R.string.customer_detail_orders_count, orders.total)
                    else stringResource(R.string.nav_orders),
                    color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 10.dp)
                )
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    orders.error?.let { SyncErrorCard(it, onRetry = orders::reload, compact = true) }
                    orders.items.forEach { order -> OrderRow(shop, order, onClick = onOpenOrder) }
                    if (orders.items.isEmpty() && !orders.loading && orders.error == null) {
                        Text(
                            stringResource(R.string.customer_detail_no_orders),
                            color = cs.onSurfaceVariant, fontSize = 13.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                    if (orders.items.size < orders.total) {
                        FilledTonalButton(
                            onClick = orders::loadMore,
                            enabled = !orders.loading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .height(46.dp)
                        ) {
                            if (orders.loading) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                val remaining = orders.total - orders.items.size
                                Text(pluralStringResource(R.plurals.customer_detail_load_more, remaining, remaining))
                            }
                        }
                    }
                }
            }
        }
    }
}
