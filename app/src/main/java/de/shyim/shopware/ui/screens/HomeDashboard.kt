package de.shyim.shopware.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.PendingActions
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.shyim.shopware.R
import de.shyim.shopware.data.delta
import de.shyim.shopware.data.fmt
import de.shyim.shopware.data.model.ConnectedShop
import de.shyim.shopware.data.model.ShopSnapshot
import de.shyim.shopware.ui.SyncState
import de.shyim.shopware.ui.relativeAgoText
import de.shyim.shopware.ui.weekDayLabels
import de.shyim.shopware.ui.components.BadgeTone
import de.shyim.shopware.ui.components.DeltaBadge
import de.shyim.shopware.ui.components.OrderRow
import de.shyim.shopware.ui.components.SectionHeader
import de.shyim.shopware.ui.components.ShopIconBox
import de.shyim.shopware.ui.components.StatTile
import de.shyim.shopware.ui.components.StatusBadge
import de.shyim.shopware.ui.components.WeekBars
import kotlin.math.roundToInt

@Composable
fun ShopSwitcherHeader(
    shop: ConnectedShop,
    shops: List<ConnectedShop>,
    syncState: SyncState,
    lastSyncMs: Long?,
    onSelectShop: (String) -> Unit,
    onRefresh: () -> Unit,
    onAddShop: () -> Unit,
    onManageShops: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { menuOpen = true }
                    .padding(6.dp)
            ) {
                ShopIconBox(shop.tint, size = 42.dp, corner = 13.dp, iconSize = 22.dp)
                Column(modifier = Modifier.padding(start = 10.dp)) {
                    Text(
                        shop.name,
                        color = cs.onSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.3).sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        shop.baseUrl.removePrefix("https://").removePrefix("http://"),
                        color = cs.onSurfaceVariant, fontSize = 11.5.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    Icons.Outlined.ExpandMore, contentDescription = stringResource(R.string.home_switch_shop),
                    tint = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp)
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                shops.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.name) },
                        leadingIcon = { ShopIconBox(s.tint, size = 30.dp, corner = 9.dp, iconSize = 16.dp) },
                        trailingIcon = if (s.id == shop.id) {
                            { Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.home_selected)) }
                        } else null,
                        onClick = {
                            menuOpen = false
                            onSelectShop(s.id)
                        }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_add_shop)) },
                    leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    onClick = { menuOpen = false; onAddShop() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_manage_shops)) },
                    leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                    onClick = { menuOpen = false; onManageShops() }
                )
            }
        }

        Box(modifier = Modifier.weight(1f))

        // Sync pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .clip(CircleShape)
                .background(
                    if (syncState is SyncState.Error) cs.errorContainer
                    else cs.surfaceContainer
                )
                .clickable(enabled = syncState != SyncState.Syncing) { onRefresh() }
                .padding(horizontal = 11.dp, vertical = 7.dp)
        ) {
            when (syncState) {
                SyncState.Syncing -> {
                    CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.8.dp)
                    Text(stringResource(R.string.home_syncing), color = cs.onSurfaceVariant, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                }
                is SyncState.Error -> {
                    Icon(
                        Icons.Outlined.ErrorOutline, contentDescription = null,
                        tint = cs.onErrorContainer, modifier = Modifier.size(13.dp)
                    )
                    Text(stringResource(R.string.home_retry_sync), color = cs.onErrorContainer, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                }
                SyncState.Idle -> {
                    Icon(
                        Icons.Outlined.Refresh, contentDescription = stringResource(R.string.home_refresh),
                        tint = cs.onSurfaceVariant, modifier = Modifier.size(13.dp)
                    )
                    Text(
                        lastSyncMs?.let { stringResource(R.string.home_synced_ago, relativeAgoText(it)) }
                            ?: stringResource(R.string.home_sync),
                        color = cs.onSurfaceVariant, fontSize = 11.5.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeDashboard(
    shop: ConnectedShop,
    snapshot: ShopSnapshot?,
    syncState: SyncState,
    onRefresh: () -> Unit,
    onOrderClick: (String) -> Unit = {},
    onOpenReviews: () -> Unit = {},
    twoColumn: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    var quickProductId by remember { mutableStateOf<String?>(null) }

    if (snapshot == null) {
        when (syncState) {
            is SyncState.Error -> SyncErrorCard(syncState.message, onRefresh)
            else -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 120.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LoadingIndicator(modifier = Modifier.size(64.dp))
                Text(
                    stringResource(R.string.home_first_sync, shop.name),
                    color = cs.onSurfaceVariant, fontSize = 13.5.sp,
                    modifier = Modifier.padding(top = 18.dp)
                )
            }
        }
        return
    }

    if (syncState is SyncState.Error) {
        SyncErrorCard(syncState.message, onRefresh, compact = true)
    }

    if (twoColumn) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                HeroCard(shop, snapshot)
                StatTilesRow(snapshot)
            }
            Column(modifier = Modifier.weight(1f)) {
                AttentionSection(shop, snapshot, onOpenReviews, onQuickProduct = { quickProductId = it })
                RecentOrdersSection(shop, snapshot, onOrderClick)
            }
        }
    } else {
        HeroCard(shop, snapshot)
        StatTilesRow(snapshot)
        AttentionSection(shop, snapshot, onOpenReviews, onQuickProduct = { quickProductId = it })
        RecentOrdersSection(shop, snapshot, onOrderClick)
    }

    QuickProductHost(shop, quickProductId) { quickProductId = null }
}

@Composable
private fun HeroCard(shop: ConnectedShop, snapshot: ShopSnapshot) {
    val cs = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val d = delta(snapshot.todayRevenue, snapshot.yesterdayRevenue)
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = cs.primaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.home_sales_today),
                        color = cs.onPrimaryContainer.copy(alpha = 0.85f),
                        fontSize = 13.sp, fontWeight = FontWeight.Medium
                    )
                    Text(
                        shop.fmt(snapshot.todayRevenue),
                        color = cs.onPrimaryContainer,
                        fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                DeltaBadge(d)
            }
            shop.dailyTarget?.takeIf { it > 0 }?.let { target ->
                val pct = (snapshot.todayRevenue / target).toFloat().coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { pct },
                    color = cs.onPrimaryContainer,
                    trackColor = cs.onPrimaryContainer.copy(alpha = 0.24f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                )
                Text(
                    stringResource(R.string.home_target_progress, (pct * 100).roundToInt(), shop.fmt(target)),
                    color = cs.onPrimaryContainer.copy(alpha = 0.85f),
                    fontSize = 11.5.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Row(modifier = Modifier.padding(top = 16.dp)) {
                WeekBars(
                    data = snapshot.weekRevenue,
                    labels = weekDayLabels(snapshot.lastSyncEpochMs),
                    height = 88.dp,
                    highlight = snapshot.todayIndex,
                    barColor = if (dark) Color.White.copy(alpha = 0.22f) else Color(0xFF00210F).copy(alpha = 0.18f),
                    highlightColor = cs.onPrimaryContainer,
                    labelColor = cs.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun StatTilesRow(snapshot: ShopSnapshot) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatTile(Icons.Outlined.ReceiptLong, "${snapshot.ordersToday}", stringResource(R.string.home_orders_today), modifier = Modifier.weight(1f))
        StatTile(Icons.Outlined.PendingActions, "${snapshot.openOrders}", stringResource(R.string.home_open_orders), modifier = Modifier.weight(1f))
        StatTile(Icons.Outlined.Inventory2, "${snapshot.lowStockCount}", stringResource(R.string.home_low_stock), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AttentionSection(
    shop: ConnectedShop,
    snapshot: ShopSnapshot,
    onOpenReviews: () -> Unit,
    onQuickProduct: (String) -> Unit,
) {
    if (snapshot.unpaidOrders == 0 && snapshot.lowStockItems.isEmpty() && snapshot.pendingReviews == 0) return
    SectionHeader(stringResource(R.string.home_needs_attention), modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (snapshot.pendingReviews > 0) {
            AttentionRow(
                icon = Icons.Outlined.RateReview,
                title = pluralStringResource(
                    R.plurals.home_pending_reviews, snapshot.pendingReviews, snapshot.pendingReviews
                ),
                subtitle = stringResource(R.string.home_tap_to_moderate),
                warn = true,
                onClick = onOpenReviews,
            )
        }
        if (snapshot.unpaidOrders > 0) {
            AttentionRow(
                icon = Icons.Outlined.Payments,
                title = pluralStringResource(
                    R.plurals.home_unpaid_orders, snapshot.unpaidOrders, snapshot.unpaidOrders
                ),
                subtitle = stringResource(R.string.home_payment_open_3_days),
            )
        }
        snapshot.lowStockItems.forEach { item ->
            val actionable = item.id.isNotBlank()
            AttentionRow(
                icon = Icons.Outlined.Inventory2,
                title = item.name,
                subtitle = if (actionable) stringResource(R.string.home_tap_to_restock)
                else stringResource(R.string.home_stock_running_low),
                badge = stringResource(R.string.home_stock_left, item.stock),
                onClick = if (actionable) ({ onQuickProduct(item.id) }) else null,
            )
        }
    }
}

@Composable
private fun RecentOrdersSection(
    shop: ConnectedShop,
    snapshot: ShopSnapshot,
    onOrderClick: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    SectionHeader(stringResource(R.string.home_recent_orders), modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
    if (snapshot.recentOrders.isEmpty()) {
        Text(
            stringResource(R.string.home_no_orders_yet),
            color = cs.onSurfaceVariant, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
    }
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        snapshot.recentOrders.forEach { order ->
            OrderRow(shop, order, onClick = onOrderClick)
        }
    }
}

@Composable
private fun QuickProductHost(
    shop: ConnectedShop,
    productId: String?,
    onDismiss: () -> Unit,
) {
    productId?.let {
        ProductActionSheet(shop = shop, productId = it, onDismiss = onDismiss)
    }
}

@Composable
private fun AttentionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    badge: String? = null,
    warn: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
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
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (warn) cs.tertiaryContainer else cs.errorContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon, contentDescription = null,
                    tint = if (warn) cs.onTertiaryContainer else cs.onErrorContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle, color = cs.onSurfaceVariant, fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            badge?.let { StatusBadge(BadgeTone.Bad, it) }
        }
    }
}

@Composable
fun SyncErrorCard(message: String, onRetry: () -> Unit, compact: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = cs.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = if (compact) 8.dp else 32.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = cs.onErrorContainer)
                Text(
                    if (compact) stringResource(R.string.sync_failed_showing_last)
                    else stringResource(R.string.sync_couldnt_sync),
                    color = cs.onErrorContainer, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
            Text(
                message,
                color = cs.onErrorContainer.copy(alpha = 0.9f), fontSize = 12.5.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
            if (!compact) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.padding(top = 12.dp)
                ) { Text(stringResource(R.string.common_retry)) }
            }
        }
    }
}
