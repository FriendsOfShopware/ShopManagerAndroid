package de.shyim.shopware.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import de.shyim.shopware.api.ShopApi
import de.shyim.shopware.api.SwEntity
import de.shyim.shopware.data.model.ConnectedShop
import de.shyim.shopware.data.model.ReviewItem
import de.shyim.shopware.ui.relativeAgoText
import de.shyim.shopware.ui.components.BadgeTone
import de.shyim.shopware.ui.components.StatusBadge
import de.shyim.shopware.ui.listing.FilterValue
import de.shyim.shopware.ui.listing.ListingFilter
import de.shyim.shopware.ui.listing.ListingScaffold
import de.shyim.shopware.ui.listing.ListingState
import de.shyim.shopware.ui.listing.QuickFilter
import de.shyim.shopware.ui.listing.salesChannelFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val PENDING = FilterValue.OptionsValue(setOf("false"))

private fun reviewFilters(context: android.content.Context) = listOf(
    ListingFilter.Bool(
        "status", context.getString(R.string.reviews_filter_status), "status",
        context.getString(R.string.reviews_approved), context.getString(R.string.reviews_pending),
    ),
    salesChannelFilter(context.getString(R.string.common_sales_channel)),
)

private fun reviewQuickFilters(context: android.content.Context) = listOf(
    QuickFilter("status", context.getString(R.string.reviews_pending), PENDING),
    QuickFilter("status", context.getString(R.string.reviews_approved), FilterValue.OptionsValue(setOf("true"))),
)

private fun reviewBaseCriteria(): Criteria = Criteria()
    .addSorting("createdAt", "DESC")
    .addAssociation("product")
    .addAssociation("customer")
    .addIncludes(
        "product_review",
        listOf(
            "id", "title", "content", "points", "status",
            "createdAt", "externalUser", "product", "customer",
        ),
    )
    .addIncludes("product", listOf("name", "translated"))
    .addIncludes("customer", listOf("firstName", "lastName"))

private fun mapReview(r: SwEntity, guestLabel: String): ReviewItem {
    val now = System.currentTimeMillis()
    val customer = r.entity("customer")
    val reviewer = listOfNotNull(customer?.string("firstName"), customer?.string("lastName"))
        .joinToString(" ")
        .ifBlank { r.string("externalUser") ?: guestLabel }
    return ReviewItem(
        id = r.id ?: "",
        title = r.string("title") ?: "—",
        content = r.string("content") ?: "",
        points = (r.double("points") ?: 0.0).toInt(),
        approved = r.boolean("status") ?: false,
        reviewer = reviewer,
        productName = r.entity("product")?.translated("name") ?: "—",
        createdMs = r.instant("createdAt")?.toEpochMilli() ?: now,
    )
}

class ReviewInboxViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as ShopwareApp).repository

    var api by mutableStateOf<ShopApi?>(null)
        private set
    var listing by mutableStateOf<ListingState<ReviewItem>?>(null)
        private set
    var busyId by mutableStateOf<String?>(null)
        private set
    var actionError by mutableStateOf<String?>(null)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    private var shop: ConnectedShop? = null

    fun load(shopId: String) {
        if (shop?.id == shopId) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val s = repo.data.first().shops.firstOrNull { it.id == shopId }
            if (s == null) {
                loadError = app.getString(R.string.common_shop_not_found)
                return@launch
            }
            loadError = null
            shop = s
            val shopApi = runCatching { repo.apiFor(s) }.getOrNull()
            api = shopApi
            val guestLabel = app.getString(R.string.customer_detail_guest)
            listing = ListingState(
                scope = viewModelScope,
                filters = reviewFilters(app),
                source = {
                    (shopApi ?: error(app.getString(R.string.reviews_need_connection)))
                        .repository("product-review").search(it)
                },
                baseCriteria = ::reviewBaseCriteria,
                mapper = { mapReview(it, guestLabel) },
                errorFallback = app.getString(R.string.listing_request_failed),
            ).also { it.setFilterValue("status", PENDING) } // default to the pending inbox; triggers the first load
        }
    }

    fun setStatus(reviewId: String, approved: Boolean) {
        val s = shop ?: return
        if (busyId != null) return
        busyId = reviewId
        actionError = null
        viewModelScope.launch {
            runCatching { repo.setReviewStatus(s, reviewId, approved) }.fold(
                onSuccess = {
                    // drop the review from a server-filtered list its new status no longer matches
                    val statusFilter = (listing?.activeValues?.get("status") as? FilterValue.OptionsValue)
                        ?.ids?.singleOrNull()
                    if (statusFilter != null && statusFilter != approved.toString()) {
                        listing?.removeItem { it.id == reviewId }
                    } else {
                        listing?.mutateItem({ it.id == reviewId }) { it.copy(approved = approved) }
                    }
                    repo.refresh(s.id) // keep the dashboard's pending count current
                },
                onFailure = {
                    actionError = it.message
                        ?: getApplication<Application>().getString(R.string.common_update_failed)
                }
            )
            busyId = null
        }
    }
}

@Composable
fun ReviewInboxScreen(
    shopId: String,
    onBack: () -> Unit,
    vm: ReviewInboxViewModel = viewModel(),
) {
    LaunchedEffect(shopId) { vm.load(shopId) }
    val listing = vm.listing
    if (listing == null) {
        ReviewsFallback(vm.loadError, onBack, onRetry = { vm.load(shopId) })
        return
    }

    val context = LocalContext.current
    val quickFilters = remember(context) { reviewQuickFilters(context) }
    ListingScaffold(
        title = stringResource(R.string.reviews_title),
        state = listing,
        api = vm.api,
        searchPlaceholder = stringResource(R.string.reviews_search_placeholder),
        emptyText = if (listing.activeValues["status"] == PENDING) {
            stringResource(R.string.reviews_empty_pending)
        } else {
            stringResource(R.string.reviews_empty)
        },
        emptyIcon = Icons.Outlined.RateReview,
        quickFilters = quickFilters,
        header = vm.actionError?.let { message ->
            { Text(message, color = MaterialTheme.colorScheme.error, fontSize = 12.5.sp) }
        },
        onBack = onBack,
        gridOnExpanded = true,
    ) { review ->
        ReviewCard(
            review = review,
            busy = vm.busyId == review.id,
            onSetStatus = { vm.setStatus(review.id, it) }
        )
    }
}

// Pre-listing state: back header plus either the load error or nothing while the shop resolves
@Composable
private fun ReviewsFallback(error: String?, onBack: () -> Unit, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = cs.onSurface)
            }
            Text(stringResource(R.string.reviews_title), color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        error?.let { SyncErrorCard(it, onRetry = onRetry, compact = true) }
    }
}

@Composable
private fun ReviewCard(review: ReviewItem, busy: Boolean, onSetStatus: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = cs.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(modifier = Modifier.weight(1f)) {
                    repeat(5) { i ->
                        Icon(
                            if (i < review.points) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                            contentDescription = null,
                            tint = if (i < review.points) cs.tertiary else cs.outlineVariant,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
                StatusBadge(
                    tone = if (review.approved) BadgeTone.Good else BadgeTone.Warn,
                    text = if (review.approved) stringResource(R.string.reviews_approved)
                    else stringResource(R.string.reviews_pending)
                )
            }
            Text(
                review.title,
                color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp)
            )
            if (review.content.isNotBlank()) {
                Text(
                    review.content,
                    color = cs.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp,
                    maxLines = 4,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Text(
                "${review.reviewer} · ${review.productName} · ${relativeAgoText(review.createdMs)}",
                color = cs.onSurfaceVariant, fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.End
            ) {
                if (review.approved) {
                    TextButton(onClick = { onSetStatus(false) }, enabled = !busy) {
                        Text(stringResource(R.string.reviews_reject), color = cs.error)
                    }
                } else {
                    TextButton(onClick = { onSetStatus(false) }, enabled = !busy) {
                        Text(stringResource(R.string.reviews_keep_hidden), color = cs.onSurfaceVariant)
                    }
                    FilledTonalButton(
                        onClick = { onSetStatus(true) },
                        enabled = !busy,
                        modifier = Modifier.padding(start = 8.dp)
                    ) { Text(stringResource(R.string.reviews_approve)) }
                }
            }
        }
    }
}
