package de.shyim.shopware.ui.screens.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.shyim.shopware.R
import de.shyim.shopware.data.Delta
import de.shyim.shopware.data.fmt
import de.shyim.shopware.data.fmtCompact
import de.shyim.shopware.data.model.ConnectedShop
import de.shyim.shopware.data.model.KpiState
import de.shyim.shopware.ui.components.DeltaBadge
import de.shyim.shopware.ui.components.RankBar
import de.shyim.shopware.ui.components.WeekBars
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Shared card chrome: title, then a state-aware body (loading / error+retry / content).
@Composable
fun KpiCard(
    title: String,
    state: KpiState?,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
    content: @Composable (KpiState) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = cs.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, color = cs.onSurfaceVariant, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
            when (state) {
                null, KpiState.Loading -> Box(
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp) }
                is KpiState.Error -> Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text(state.message, color = cs.onSurfaceVariant, fontSize = 12.5.sp)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry), fontSize = 13.sp) }
                }
                else -> Box(modifier = Modifier.padding(top = 6.dp)) { content(state) }
            }
        }
    }
}

// Headline number + delta + a bar chart over the period.
@Composable
fun TrendCard(title: String, shop: ConnectedShop, state: KpiState?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    KpiCard(title, state, modifier, onRetry) { s ->
        val kpi = (s as KpiState.TimeSeries).data
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (kpi.isMoney) shop.fmt(kpi.total) else kpi.total.toLong().toString(),
                    color = cs.onSurface, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp,
                )
                kpi.previousTotal?.let { DeltaBadge(deltaOf(kpi.total, it)) }
            }
            if (kpi.points.isNotEmpty()) {
                Box(modifier = Modifier.padding(top = 14.dp)) {
                    WeekBars(
                        data = kpi.points.map { it.value },
                        labels = kpi.points.map { dayLabel(it.epochMs) },
                        height = 96.dp,
                        highlight = kpi.points.lastIndex,
                        valueLabel = { v ->
                            if (kpi.isMoney) shop.fmtCompact(v) else v.toLong().toString()
                        },
                    )
                }
            }
        }
    }
}

// Single big number with optional delta.
@Composable
fun SingleNumberCard(title: String, shop: ConnectedShop, state: KpiState?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    KpiCard(title, state, modifier, onRetry) { s ->
        val kpi = (s as KpiState.Single).data
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (kpi.isMoney) shop.fmt(kpi.value) else kpi.value.toLong().toString(),
                color = cs.onSurface, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp,
            )
            kpi.previousValue?.let { DeltaBadge(deltaOf(kpi.value, it)) }
        }
    }
}

// Top-N horizontal bars (revenue or count/quantity).
@Composable
fun BreakdownCard(
    title: String,
    shop: ConnectedShop,
    state: KpiState?,
    secondaryLabel: (Double) -> String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    KpiCard(title, state, modifier, onRetry) { s ->
        val kpi = (s as KpiState.Breakdown).data
        if (kpi.rows.isEmpty()) {
            Text(stringResource(R.string.reports_kpi_empty), color = cs.onSurfaceVariant, fontSize = 12.5.sp)
            return@KpiCard
        }
        val max = kpi.rows.maxOf { it.value }.coerceAtLeast(1.0)
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            kpi.rows.take(8).forEach { row ->
                Column {
                    Row(modifier = Modifier.padding(bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            row.label, color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (kpi.valueIsMoney) shop.fmt(row.value) else secondaryLabel(row.value),
                            color = cs.onSurface, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    RankBar(fraction = (row.value / max).toFloat(), fill = cs.primary)
                }
            }
        }
    }
}

private fun deltaOf(now: Double, prev: Double): Delta {
    if (prev <= 0.0) return Delta(pct = 0, up = now >= 0, label = if (now > 0) "+100%" else "0%")
    val pct = ((now - prev) / prev * 100).toInt()
    return Delta(pct = kotlin.math.abs(pct), up = pct >= 0, label = (if (pct >= 0) "+" else "") + "$pct%")
}

private val DAY_FMT = DateTimeFormatter.ofPattern("d.M")
private fun dayLabel(epochMs: Long): String =
    runCatching { DAY_FMT.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())) }.getOrDefault("")
