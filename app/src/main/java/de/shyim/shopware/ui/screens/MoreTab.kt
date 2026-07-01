package de.shyim.shopware.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.shyim.shopware.R
import de.shyim.shopware.data.model.ConnectedShop
import de.shyim.shopware.ui.components.ScreenTitle
import de.shyim.shopware.ui.isExpandedWidth

private data class MoreItem(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val onClick: () -> Unit,
)

// Secondary destinations; entries hide when the login lacks the entity's read access
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoreTab(
    shop: ConnectedShop,
    onOpenProducts: () -> Unit,
    onOpenPromos: () -> Unit,
    onOpenMedia: () -> Unit,
    onOpenReviews: () -> Unit,
) {
    val items = buildList {
        if (shop.canRead("product")) add(
            MoreItem(Icons.Outlined.Inventory2, stringResource(R.string.products_title), stringResource(R.string.more_products_desc), onOpenProducts)
        )
        if (shop.canRead("promotion")) add(
            MoreItem(Icons.Outlined.Sell, stringResource(R.string.promos_title), stringResource(R.string.more_promos_desc), onOpenPromos)
        )
        if (shop.canRead("media")) add(
            MoreItem(Icons.Outlined.PermMedia, stringResource(R.string.media_title), stringResource(R.string.more_media_desc), onOpenMedia)
        )
        if (shop.canRead("product_review")) add(
            MoreItem(Icons.Outlined.RateReview, stringResource(R.string.reviews_title), stringResource(R.string.more_reviews_desc), onOpenReviews)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScreenTitle(stringResource(R.string.nav_more))
        if (isExpandedWidth()) {
            // Two-column grid of tiles fills the tablet canvas
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                maxItemsInEachRow = 2,
            ) {
                items.forEach { item ->
                    MoreTile(item, modifier = Modifier.weight(1f))
                }
                // Odd count: pad the last row so the lone tile keeps half-width
                if (items.size % 2 == 1) Box(modifier = Modifier.weight(1f))
            }
        } else {
            items.forEach { MoreEntry(it) }
        }
    }
}

// Compact: full-width row with a chevron
@Composable
private fun MoreEntry(item: MoreItem) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = item.onClick,
        shape = RoundedCornerShape(18.dp),
        color = cs.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(item.icon, contentDescription = null, tint = cs.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(item.description, color = cs.onSurfaceVariant, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

// Expanded: taller tile with the icon in a tinted badge above the text
@Composable
private fun MoreTile(item: MoreItem, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = item.onClick,
        shape = RoundedCornerShape(22.dp),
        color = cs.surfaceContainerLow,
        modifier = modifier.height(150.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(cs.primaryContainer, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(item.icon, contentDescription = null, tint = cs.onPrimaryContainer, modifier = Modifier.size(26.dp))
            }
            Column(modifier = Modifier.padding(top = 14.dp)) {
                Text(item.title, color = cs.onSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(item.description, color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
