package de.shyim.shopware.ui.screens

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import de.shyim.shopware.R
import de.shyim.shopware.ShopwareApp
import de.shyim.shopware.data.fmtMoney
import de.shyim.shopware.data.model.ConnectedShop
import de.shyim.shopware.data.model.ProductDetail
import de.shyim.shopware.data.model.ProductFieldConfig
import de.shyim.shopware.data.model.ProductVariant
import de.shyim.shopware.data.source.PriceEdit
import de.shyim.shopware.ui.components.BadgeTone
import de.shyim.shopware.ui.components.StatusBadge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed class ProductDetailUi {
    data object Loading : ProductDetailUi()
    data class Error(val message: String) : ProductDetailUi()
    data class Ready(val detail: ProductDetail) : ProductDetailUi()
}

class ProductDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as ShopwareApp).repository

    var ui by mutableStateOf<ProductDetailUi>(ProductDetailUi.Loading)
        private set
    var shop by mutableStateOf<ConnectedShop?>(null)
        private set
    var variants by mutableStateOf<List<ProductVariant>>(emptyList())
        private set
    var saving by mutableStateOf(false)
        private set
    var saveError by mutableStateOf<String?>(null)

    private fun appString(res: Int) = getApplication<Application>().getString(res)

    fun load(shopId: String, productId: String) {
        viewModelScope.launch {
            ui = ProductDetailUi.Loading
            val s = repo.data.first().shops.firstOrNull { it.id == shopId }
            if (s == null) {
                ui = ProductDetailUi.Error(appString(R.string.common_shop_not_found))
                return@launch
            }
            shop = s
            ui = runCatching { repo.productDetail(s, productId) }.fold(
                onSuccess = { it?.let { d -> ProductDetailUi.Ready(d) } ?: ProductDetailUi.Error(appString(R.string.product_detail_not_found)) },
                onFailure = { ProductDetailUi.Error(it.message ?: appString(R.string.product_detail_load_failed)) }
            )
            val d = (ui as? ProductDetailUi.Ready)?.detail
            variants = if (d != null && d.childCount > 0) {
                runCatching { repo.productVariants(s, d.id, d.taxRate) }.getOrDefault(emptyList())
            } else emptyList()
        }
    }

    fun saveBase(
        name: String,
        active: Boolean,
        stock: Int,
        ean: String?,
        manufacturerNumber: String?,
        price: PriceEdit?,
        onSaved: () -> Unit,
    ) {
        val s = shop ?: return
        val d = (ui as? ProductDetailUi.Ready)?.detail ?: return
        if (saving) return
        saving = true; saveError = null
        viewModelScope.launch {
            runCatching { repo.saveProductDetail(s, d, name, active, stock, ean, manufacturerNumber, price) }.fold(
                onSuccess = { load(s.id, d.id); onSaved() },
                onFailure = { saveError = it.message ?: appString(R.string.product_detail_save_failed) }
            )
            saving = false
        }
    }

    fun saveVariant(variant: ProductVariant, stock: Int, price: PriceEdit?, onSaved: () -> Unit) {
        val s = shop ?: return
        val d = (ui as? ProductDetailUi.Ready)?.detail ?: return
        if (saving) return
        saving = true; saveError = null
        viewModelScope.launch {
            runCatching { repo.saveVariantEdit(s, variant, stock, price) }.fold(
                onSuccess = {
                    variants = runCatching { repo.productVariants(s, d.id, d.taxRate) }.getOrDefault(variants)
                    onSaved()
                },
                onFailure = { saveError = it.message ?: appString(R.string.product_detail_save_failed) }
            )
            saving = false
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProductDetailScreen(
    shopId: String,
    productId: String,
    onBack: () -> Unit,
    vm: ProductDetailViewModel = viewModel(),
) {
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(shopId, productId) { vm.load(shopId, productId) }

    var editingBase by remember { mutableStateOf(false) }
    var editingVariant by remember { mutableStateOf<ProductVariant?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = cs.onSurface)
            }
            Text(stringResource(R.string.product_detail_title), color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.weight(1f))
            if (vm.ui is ProductDetailUi.Ready) {
                IconButton(onClick = { editingBase = true }) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.product_detail_edit), tint = cs.primary)
                }
            }
        }

        when (val ui = vm.ui) {
            ProductDetailUi.Loading -> Box(
                modifier = Modifier.fillMaxWidth().padding(top = 100.dp),
                contentAlignment = Alignment.Center
            ) { LoadingIndicator(modifier = Modifier.size(56.dp)) }
            is ProductDetailUi.Error -> SyncErrorCard(ui.message, onRetry = { vm.load(shopId, productId) })
            is ProductDetailUi.Ready -> {
                val d = ui.detail
                val shop = vm.shop ?: return

                val cfg = shop.productFields
                ProductHeader(shop, d, cfg)
                PricingStockCard(shop, d, cfg)
                IdentifiersCard(d, cfg)
                if (cfg.showDescription) d.description?.let { DescriptionCard(it) }
                MetaCard(d, cfg)

                if (d.childCount > 0 && vm.variants.isNotEmpty()) {
                    VariantsCard(shop, vm.variants, onEdit = { editingVariant = it })
                }
            }
        }
    }

    if (editingBase) {
        (vm.ui as? ProductDetailUi.Ready)?.let { ready ->
            val shop = vm.shop
            if (shop != null) {
                ProductBaseEditSheet(
                    shop = shop,
                    detail = ready.detail,
                    saving = vm.saving,
                    saveError = vm.saveError,
                    onSave = { name, active, stock, ean, mpn, price ->
                        vm.saveBase(name, active, stock, ean, mpn, price) { editingBase = false }
                    },
                    onDismiss = { editingBase = false },
                )
            }
        }
    }

    editingVariant?.let { variant ->
        val shop = vm.shop
        if (shop != null) {
            VariantEditSheet(
                shop = shop,
                variant = variant,
                saving = vm.saving,
                saveError = vm.saveError,
                onSave = { stock, price -> vm.saveVariant(variant, stock, price) { editingVariant = null } },
                onDismiss = { editingVariant = null },
            )
        }
    }
}

@Composable
private fun ProductHeader(shop: ConnectedShop, d: ProductDetail, cfg: ProductFieldConfig) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(cs.surfaceContainerLow),
            contentAlignment = Alignment.Center
        ) {
            if (d.coverUrl != null) {
                AsyncImage(
                    model = d.coverUrl,
                    contentDescription = d.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Outlined.Image, contentDescription = null, tint = cs.outline, modifier = Modifier.size(48.dp))
            }
        }
        // Thumbnail strip when there's more than the cover
        if (d.galleryUrls.size > 1) {
            LazyRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(d.galleryUrls.size) { i ->
                    AsyncImage(
                        model = d.galleryUrls[i],
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(cs.surfaceContainerLow)
                    )
                }
            }
        }
        Row(
            modifier = Modifier.padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                d.name, color = cs.onSurface, fontSize = 21.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp,
                modifier = Modifier.weight(1f)
            )
            if (!d.active) StatusBadge(BadgeTone.Bad, stringResource(R.string.common_inactive))
        }
        Text(
            listOfNotNull(
                d.productNumber.ifBlank { null },
                d.manufacturer.takeIf { cfg.showManufacturer },
                d.ratingAverage?.let { "★ %.1f".format(it) },
            ).joinToString(" · "),
            color = cs.onSurfaceVariant, fontSize = 12.5.sp,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

@Composable
private fun PricingStockCard(shop: ConnectedShop, d: ProductDetail, cfg: ProductFieldConfig) {
    val cs = MaterialTheme.colorScheme
    // When price is hidden the card collapses to just the stock badge; relabel it accordingly.
    val title = if (cfg.showPrice) R.string.product_detail_pricing else R.string.product_detail_stock
    DetailCard(stringResource(title)) {
        if (cfg.showPrice) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    d.grossPrice?.let { fmtMoney(it, shop.currency, shop.localeCode) } ?: "—",
                    color = cs.onSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold
                )
                d.netPrice?.let {
                    Text(
                        stringResource(R.string.product_detail_net, fmtMoney(it, shop.currency, shop.localeCode)),
                        color = cs.onSurfaceVariant, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 8.dp, bottom = 3.dp)
                    )
                }
            }
            d.taxRate?.let {
                Text(
                    stringResource(R.string.product_detail_tax_rate, it),
                    color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (!d.priceEditable) {
                Text(
                    stringResource(R.string.product_sheet_price_not_editable),
                    color = cs.onSurfaceVariant, fontSize = 11.5.sp, modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(stockTone(d.stock, shop.lowStockThreshold), stringResource(R.string.products_stock_badge, d.stock))
            if (d.availableStock != d.stock) {
                Text(
                    stringResource(R.string.product_detail_available, d.availableStock),
                    color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun IdentifiersCard(d: ProductDetail, cfg: ProductFieldConfig) {
    val cs = MaterialTheme.colorScheme
    val ean = d.ean?.takeIf { cfg.showEan }
    val mpn = d.manufacturerNumber?.takeIf { cfg.showManufacturerNumber }
    if (ean == null && mpn == null) return
    DetailCard(stringResource(R.string.product_detail_identifiers)) {
        ean?.let {
            Text(stringResource(R.string.product_detail_ean), color = cs.onSurfaceVariant, fontSize = 12.sp)
            Text(it, color = cs.onSurface, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
        mpn?.let {
            Text(
                stringResource(R.string.product_detail_manufacturer_number),
                color = cs.onSurfaceVariant, fontSize = 12.sp,
                modifier = Modifier.padding(top = if (ean != null) 8.dp else 0.dp)
            )
            Text(it, color = cs.onSurface, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun DescriptionCard(text: String) {
    val cs = MaterialTheme.colorScheme
    DetailCard(stringResource(R.string.product_detail_description)) {
        Text(text, color = cs.onSurface, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun MetaCard(d: ProductDetail, cfg: ProductFieldConfig) {
    val cs = MaterialTheme.colorScheme
    val showCategories = cfg.showCategories && d.categories.isNotEmpty()
    val showChannels = cfg.showSalesChannels && d.salesChannels.isNotEmpty()
    if (!showCategories && !showChannels) return
    DetailCard(stringResource(R.string.product_detail_placement)) {
        if (showCategories) {
            Text(stringResource(R.string.product_detail_categories), color = cs.onSurfaceVariant, fontSize = 12.sp)
            Text(d.categories.joinToString(" · "), color = cs.onSurface, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        }
        if (showChannels) {
            Text(stringResource(R.string.product_detail_sales_channels), color = cs.onSurfaceVariant, fontSize = 12.sp)
            Text(d.salesChannels.joinToString(" · "), color = cs.onSurface, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun VariantsCard(shop: ConnectedShop, variants: List<ProductVariant>, onEdit: (ProductVariant) -> Unit) {
    val cs = MaterialTheme.colorScheme
    DetailCard(stringResource(R.string.product_detail_variants, variants.size)) {
        variants.forEachIndexed { i, v ->
            if (i > 0) Spacer(modifier = Modifier.height(8.dp))
            Surface(
                onClick = { onEdit(v) },
                shape = RoundedCornerShape(12.dp),
                color = cs.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            v.optionLabels.joinToString(" · ").ifBlank { v.productNumber },
                            color = cs.onSurface, fontSize = 13.5.sp, fontWeight = FontWeight.Medium
                        )
                        Text(v.productNumber, color = cs.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 1.dp))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        v.grossPrice?.let {
                            Text(fmtMoney(it, shop.currency, shop.localeCode), color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Row(modifier = Modifier.padding(top = 4.dp)) {
                            StatusBadge(stockTone(v.stock, shop.lowStockThreshold), stringResource(R.string.products_stock_badge, v.stock))
                        }
                    }
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.common_edit), tint = cs.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp).size(18.dp))
                }
            }
        }
    }
}

// Shared card chrome for the detail sections
@Composable
private fun DetailCard(title: String, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = cs.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
            content()
        }
    }
}

private fun stockTone(stock: Int, threshold: Int): BadgeTone = when {
    stock <= 0 -> BadgeTone.Bad
    stock <= threshold -> BadgeTone.Warn
    else -> BadgeTone.Good
}
