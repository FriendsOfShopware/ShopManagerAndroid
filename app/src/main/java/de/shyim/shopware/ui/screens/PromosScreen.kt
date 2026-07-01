package de.shyim.shopware.ui.screens

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Redeem
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.shyim.shopware.R
import de.shyim.shopware.api.ShopApi
import de.shyim.shopware.data.PromoStatus
import de.shyim.shopware.data.model.ConnectedShop
import de.shyim.shopware.data.model.ShopPromo
import de.shyim.shopware.data.model.ShopSnapshot
import de.shyim.shopware.data.source.parsePromo
import de.shyim.shopware.data.source.promoCriteria
import de.shyim.shopware.ui.components.BadgeTone
import de.shyim.shopware.ui.components.IconMeta
import de.shyim.shopware.ui.components.StatusBadge
import de.shyim.shopware.ui.listing.FilterValue
import de.shyim.shopware.ui.listing.ListingFilter
import de.shyim.shopware.ui.listing.ListingScaffold
import de.shyim.shopware.ui.listing.ListingState
import de.shyim.shopware.ui.listing.ListingViewModel
import de.shyim.shopware.ui.listing.QuickFilter
import de.shyim.shopware.ui.listing.salesChannelFilter
import kotlinx.coroutines.launch
import java.time.Instant

private fun promoFilters(context: android.content.Context) = listOf(
    ListingFilter.Bool(
        "active", context.getString(R.string.common_active), "active",
        context.getString(R.string.common_active), context.getString(R.string.common_inactive),
    ),
    // Promotions assign channels m:n — promos without any assignment match no selection
    salesChannelFilter(context.getString(R.string.common_sales_channel), "salesChannels.salesChannelId"),
)

private fun promoQuickFilters(context: android.content.Context) = listOf(
    QuickFilter("active", context.getString(R.string.common_active), FilterValue.OptionsValue(setOf("true"))),
    QuickFilter("active", context.getString(R.string.common_inactive), FilterValue.OptionsValue(setOf("false"))),
)

class PromosViewModel(app: Application) : ListingViewModel<ShopPromo>(app) {
    var actionError by mutableStateOf<String?>(null)
        private set

    override fun createListing(shop: ConnectedShop, api: ShopApi): ListingState<ShopPromo> = ListingState(
        scope = viewModelScope,
        filters = promoFilters(getApplication()),
        source = { api.repository("promotion").search(it) },
        baseCriteria = ::promoCriteria,
        mapper = { parsePromo(it, Instant.now(), shop) },
        errorFallback = getApplication<Application>().getString(R.string.listing_request_failed),
    )

    fun toggleActive(shop: ConnectedShop, promo: ShopPromo, active: Boolean) {
        actionError = null
        viewModelScope.launch {
            runCatching { repo.setPromotionActive(shop, promo.id, active) }.fold(
                onSuccess = {
                    listing?.reload() // status/window derive from active — refetch instead of patching locally
                    repo.refresh(shop.id) // keep the snapshot hero current
                },
                onFailure = {
                    actionError = it.message
                        ?: getApplication<Application>().getString(R.string.common_update_failed)
                }
            )
        }
    }

    suspend fun addCodes(shop: ConnectedShop, promo: ShopPromo, amount: Int) {
        repo.addPromotionCodes(shop, promo.id, amount)
    }
}

@Composable
fun PromosScreen(
    shop: ConnectedShop,
    snapshot: ShopSnapshot?,
    onBack: (() -> Unit)? = null,
    vm: PromosViewModel = viewModel(),
) {
    LaunchedEffect(shop.id, shop.languageId) { vm.init(shop) }
    val listing = vm.listing ?: return
    var codesPromo by remember { mutableStateOf<ShopPromo?>(null) }
    val context = LocalContext.current
    val quickFilters = remember(context) { promoQuickFilters(context) }

    ListingScaffold(
        title = stringResource(R.string.promos_title),
        state = listing,
        api = vm.api,
        onBack = onBack,
        searchPlaceholder = stringResource(R.string.promos_search_placeholder),
        emptyText = stringResource(R.string.promos_empty),
        emptyIcon = Icons.Outlined.Inbox,
        quickFilters = quickFilters,
        gridOnExpanded = true,
        header = {
            Column {
                PromoHero(snapshot)
                vm.actionError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error, fontSize = 12.5.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
    ) { promo ->
        PromoCard(
            shop = shop,
            promo = promo,
            onToggleActive = { vm.toggleActive(shop, promo, it) },
            onGenerateCodes = { codesPromo = promo },
        )
    }

    codesPromo?.let { promo ->
        GenerateCodesDialog(
            promo = promo,
            onAddCodes = { p, amount -> vm.addCodes(shop, p, amount) },
            onDismiss = { codesPromo = null }
        )
    }
}

@Composable
private fun PromoHero(snapshot: ShopSnapshot?) {
    val cs = MaterialTheme.colorScheme
    val promos = snapshot?.promos.orEmpty()
    val liveCount = promos.count { it.status == PromoStatus.Active }
    val redemptions = promos.filter { it.status == PromoStatus.Active }.sumOf { it.redemptions }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = cs.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(cs.onSecondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Sell, contentDescription = null, tint = cs.secondaryContainer)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    pluralStringResource(R.plurals.promos_live_campaigns, liveCount, liveCount),
                    color = cs.onSecondaryContainer,
                    fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp
                )
                Text(
                    pluralStringResource(R.plurals.promos_redemptions, redemptions, redemptions),
                    color = cs.onSecondaryContainer.copy(alpha = 0.85f),
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun PromoCard(
    shop: ConnectedShop,
    promo: ShopPromo,
    onToggleActive: (Boolean) -> Unit,
    onGenerateCodes: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = cs.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(promo.name, color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        promo.detail, color = cs.onSurfaceVariant, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                StatusBadge(
                    tone = when (promo.status) {
                        PromoStatus.Active -> BadgeTone.Good
                        PromoStatus.Scheduled -> BadgeTone.Warn
                        PromoStatus.Ended -> BadgeTone.Neutral
                    },
                    text = when (promo.status) {
                        PromoStatus.Active -> stringResource(R.string.common_active)
                        PromoStatus.Scheduled -> stringResource(R.string.promos_status_scheduled)
                        PromoStatus.Ended -> stringResource(R.string.promos_status_ended)
                    }
                )
            }
            Row(
                modifier = Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconMeta(Icons.Outlined.Schedule, promo.window)
                if (promo.redemptions > 0) {
                    IconMeta(Icons.Outlined.Redeem, stringResource(R.string.promos_used, promo.redemptions), color = cs.primary)
                }
            }
            if (promo.id.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (promo.useIndividualCodes) {
                        TextButton(onClick = onGenerateCodes) {
                            Text(stringResource(R.string.promos_generate_codes), fontSize = 12.5.sp)
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        stringResource(R.string.common_active),
                        color = cs.onSurfaceVariant, fontSize = 12.5.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Switch(
                        checked = promo.active,
                        onCheckedChange = onToggleActive
                    )
                }
            }
        }
    }
}

@Composable
private fun GenerateCodesDialog(
    promo: ShopPromo,
    onAddCodes: suspend (ShopPromo, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var amount by remember { mutableStateOf("10") }
    var busy by remember { mutableStateOf(false) }
    // success flag + message, so the color does not depend on the message text
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.promos_generate_codes)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.promos_codes_dialog_text, promo.name),
                    fontSize = 13.sp, lineHeight = 18.sp
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.promos_amount)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                )
                result?.let { (ok, message) ->
                    Text(
                        message,
                        color = if (ok) cs.primary else cs.error,
                        fontSize = 12.5.sp,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val n = amount.toIntOrNull() ?: return@TextButton
                    busy = true
                    result = null
                    scope.launch {
                        result = runCatching { onAddCodes(promo, n) }.fold(
                            onSuccess = {
                                true to context.resources.getQuantityString(R.plurals.promos_codes_added, n, n)
                            },
                            onFailure = { false to (it.message ?: context.getString(R.string.promos_failed)) }
                        )
                        busy = false
                    }
                },
                enabled = !busy && (amount.toIntOrNull() ?: 0) in 1..500
            ) { Text(stringResource(R.string.promos_generate)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.common_close)) }
        }
    )
}
