package de.shyim.shopware.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.shyim.shopware.data.fmtMoney
import de.shyim.shopware.data.model.ConnectedShop
import de.shyim.shopware.data.model.RecentOrder
import de.shyim.shopware.ui.relativeAgoText

fun orderStateTone(technical: String): BadgeTone = when (technical) {
    "completed" -> BadgeTone.Good
    "cancelled" -> BadgeTone.Bad
    "in_progress" -> BadgeTone.Warn
    else -> BadgeTone.Neutral
}

@Composable
fun OrderRow(
    shop: ConnectedShop,
    order: RecentOrder,
    onClick: ((String) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val clickable = onClick != null && order.id.isNotBlank()
    Surface(
        onClick = { if (clickable) onClick?.invoke(order.id) },
        enabled = clickable,
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
                    .background(cs.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.ReceiptLong, contentDescription = null,
                    tint = cs.onSecondaryContainer, modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "#${order.orderNumber} · ${order.customer}",
                    color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    relativeAgoText(order.placedMs),
                    color = cs.onSurfaceVariant, fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    // orders keep their own currency (multi-currency instances)
                    fmtMoney(order.amount, order.currencyIso ?: shop.currency, shop.localeCode),
                    color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold
                )
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    StatusBadge(
                        tone = orderStateTone(order.stateTechnical),
                        text = order.state
                    )
                }
            }
        }
    }
}
