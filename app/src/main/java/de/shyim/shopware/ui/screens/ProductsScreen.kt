package de.shyim.shopware.ui.screens

import android.app.Application
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import de.shyim.shopware.R
import de.shyim.shopware.api.Criteria
import de.shyim.shopware.api.ShopApi
import de.shyim.shopware.data.fmtMoney
import de.shyim.shopware.data.model.ConnectedShop
import de.shyim.shopware.data.model.ProductRow
import de.shyim.shopware.data.source.parseProduct
import de.shyim.shopware.data.source.productListCriteria
import de.shyim.shopware.ui.components.BadgeTone
import de.shyim.shopware.ui.components.StatusBadge
import de.shyim.shopware.ui.listing.FilterOption
import de.shyim.shopware.ui.listing.FilterValue
import de.shyim.shopware.ui.listing.ListingFilter
import de.shyim.shopware.ui.listing.ListingScaffold
import de.shyim.shopware.ui.listing.ListingState
import de.shyim.shopware.ui.listing.ListingViewModel
import de.shyim.shopware.ui.listing.QuickFilter
import de.shyim.shopware.ui.listing.salesChannelFilter

private fun productFilters(context: Context): List<ListingFilter> = listOf(
    ListingFilter.Bool(
        "active", context.getString(R.string.common_active), "active",
        context.getString(R.string.common_active), context.getString(R.string.common_inactive),
    ),
    ListingFilter.NumberRange("stock", context.getString(R.string.products_filter_stock), "stock"),
    ListingFilter.NumberRange("price", context.getString(R.string.products_filter_price), "price"),
    ListingFilter.Options(
        "manufacturer", context.getString(R.string.products_filter_manufacturer), "manufacturer.id",
        loadOptions = { api ->
            api.repository("product-manufacturer").search(
                Criteria()
                    .setLimit(100)
                    .addSorting("name")
                    .addIncludes("product_manufacturer", listOf("id", "name", "translated"))
            ).data.mapNotNull { m -> m.id?.let { FilterOption(it, m.translated("name") ?: "—") } }
        },
    ),
    // product visibility is per sales channel
    salesChannelFilter(context.getString(R.string.common_sales_channel), "visibilities.salesChannelId"),
    ListingFilter.Existence(
        "images", context.getString(R.string.products_filter_images), "media.id",
        context.getString(R.string.products_has_images),
        context.getString(R.string.products_no_images),
    ),
    ListingFilter.Text(
        "productNumber", context.getString(R.string.products_filter_number), "productNumber",
        ListingFilter.Text.Mode.Contains,
    ),
    ListingFilter.DateRange("releaseDate", context.getString(R.string.products_filter_release_date), "releaseDate"),
)

private fun productQuickFilters(context: Context) = listOf(
    QuickFilter("active", context.getString(R.string.common_active), FilterValue.OptionsValue(setOf("true"))),
    QuickFilter("active", context.getString(R.string.common_inactive), FilterValue.OptionsValue(setOf("false"))),
)

class ProductsViewModel(app: Application) : ListingViewModel<ProductRow>(app) {
    override fun createListing(shop: ConnectedShop, api: ShopApi): ListingState<ProductRow> =
        ListingState(
            scope = viewModelScope,
            filters = productFilters(getApplication()),
            source = { api.repository("product").search(it) },
            baseCriteria = ::productListCriteria,
            mapper = { parseProduct(it, shop.baseUrl) },
            errorFallback = getApplication<Application>().getString(R.string.products_load_failed),
        )
}

@Composable
fun ProductsScreen(
    shop: ConnectedShop,
    onBack: (() -> Unit)? = null,
    onOpenProduct: ((String) -> Unit)? = null,
    vm: ProductsViewModel = viewModel(),
) {
    LaunchedEffect(shop.id, shop.languageId) { vm.init(shop) }
    val listing = vm.listing ?: return
    val context = androidx.compose.ui.platform.LocalContext.current

    ListingScaffold(
        title = stringResource(R.string.products_title),
        state = listing,
        api = vm.api,
        searchPlaceholder = stringResource(R.string.products_search_placeholder),
        emptyText = stringResource(R.string.products_empty),
        emptyIcon = Icons.Outlined.Inventory2,
        quickFilters = remember(context) { productQuickFilters(context) },
        onBack = onBack,
        gridOnExpanded = true,
    ) { product ->
        ProductListRow(shop, product, onClick = onOpenProduct?.let { { it(product.id) } })
    }
}

@Composable
private fun ProductListRow(shop: ConnectedShop, product: ProductRow, onClick: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = cs.surfaceContainerLow,
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (product.coverUrl != null) {
                    AsyncImage(
                        model = product.coverUrl,
                        contentDescription = product.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(48.dp),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(cs.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Image, contentDescription = null,
                            tint = cs.onSecondaryContainer, modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    product.name,
                    color = if (product.active) cs.onSurface else cs.onSurfaceVariant,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(product.productNumber.ifBlank { null }, product.manufacturer)
                        .joinToString(" · "),
                    color = cs.onSurfaceVariant, fontSize = 11.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                product.grossPrice?.let {
                    Text(
                        fmtMoney(it, shop.currency, shop.localeCode),
                        color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    )
                }
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    StatusBadge(
                        tone = stockTone(product.stock, shop.lowStockThreshold),
                        text = stringResource(R.string.products_stock_badge, product.stock),
                    )
                }
            }
        }
    }
}

private fun stockTone(stock: Int, threshold: Int): BadgeTone = when {
    stock <= 0 -> BadgeTone.Bad
    stock <= threshold -> BadgeTone.Warn
    else -> BadgeTone.Good
}
