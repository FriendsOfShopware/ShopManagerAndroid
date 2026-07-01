package de.shyim.shopware.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.shyim.shopware.data.Delta
import de.shyim.shopware.data.ShopTint

enum class BadgeTone { Neutral, Good, Warn, Bad }

@Composable
fun StatusBadge(tone: BadgeTone, text: String, icon: ImageVector? = null) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (tone) {
        BadgeTone.Neutral -> cs.surfaceContainerHighest to cs.onSurfaceVariant
        BadgeTone.Good -> cs.primaryContainer to cs.onPrimaryContainer
        BadgeTone.Warn -> cs.tertiaryContainer to cs.onTertiaryContainer
        BadgeTone.Bad -> cs.errorContainer to cs.onErrorContainer
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(13.dp))
        Text(
            text,
            color = fg,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp,
            maxLines = 1
        )
    }
}

@Composable
fun DeltaBadge(d: Delta) {
    StatusBadge(
        tone = if (d.up) BadgeTone.Good else BadgeTone.Bad,
        text = d.label,
        icon = if (d.up) Icons.AutoMirrored.Outlined.TrendingUp else Icons.AutoMirrored.Outlined.TrendingDown
    )
}

// Tinted rounded square holding the storefront glyph — the shop's identity mark
@Composable
fun ShopIconBox(tint: ShopTint, size: Dp = 46.dp, corner: Dp = 14.dp, iconSize: Dp = 24.dp) {
    val dark = isSystemInDarkTheme()
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(tint.bg(dark)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Outlined.Storefront,
            contentDescription = null,
            tint = tint.fg(dark),
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
fun InitialsAvatar(name: String, size: Dp = 40.dp, bg: Color? = null, fg: Color? = null) {
    val cs = MaterialTheme.colorScheme
    val initials = name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("")
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(bg ?: cs.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            initials,
            color = fg ?: cs.onPrimaryContainer,
            fontSize = (size.value * 0.38).sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp
        )
    }
}

@Composable
fun StatTile(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: Color = MaterialTheme.colorScheme.onSurface,
    contentVariant: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    sub: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(container)
            .padding(14.dp)
    ) {
        Icon(icon, contentDescription = null, tint = contentVariant, modifier = Modifier.size(20.dp))
        Text(
            value,
            color = content,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            modifier = Modifier.padding(top = 10.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            label,
            color = contentVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 3.dp)
        )
        sub?.invoke()
    }
}

@Composable
fun Sparkline(
    data: List<Int>,
    color: Color,
    modifier: Modifier = Modifier.size(width = 72.dp, height = 28.dp)
) {
    Canvas(modifier = modifier) {
        val max = data.max().toFloat()
        val min = data.min().toFloat()
        val range = (max - min).takeIf { it > 0f } ?: 1f
        val path = Path()
        data.forEachIndexed { i, v ->
            val x = i / (data.size - 1f) * (size.width - 4f) + 2f
            val y = size.height - 3f - (v - min) / range * (size.height - 6f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path, color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

// Vertical weekly bar chart with day labels; one bar may be highlighted.
// When `valueLabel` is set, the formatted value is shown above each bar.
@Composable
fun WeekBars(
    data: List<Double>,
    labels: List<String>,
    height: Dp = 110.dp,
    highlight: Int = -1,
    barColor: Color = MaterialTheme.colorScheme.primaryContainer,
    highlightColor: Color = MaterialTheme.colorScheme.primary,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    valueLabel: ((Double) -> String)? = null,
) {
    val max = (data.max() * 1.08).coerceAtLeast(1.0)
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (valueLabel != null) 6.dp else 10.dp)
    ) {
        data.forEachIndexed { i, v ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // value sits above the bar; reserve a line so bars stay baseline-aligned
                if (valueLabel != null) {
                    Text(
                        if (v > 0) valueLabel(v) else "",
                        color = if (i == highlight) onSurface else labelColor,
                        fontSize = 9.5.sp,
                        fontWeight = if (i == highlight) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1
                    )
                }
                Box(modifier = Modifier.height(height), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 44.dp)
                            .fillMaxWidth()
                            .height((height.value * (v / max).toFloat()).coerceAtLeast(8f).dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (i == highlight) highlightColor else barColor)
                    )
                }
                Text(
                    labels[i],
                    color = labelColor,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

// Horizontal ranking bar (Reports — sales by shop)
@Composable
fun RankBar(fraction: Float, fill: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(10.dp)
                .clip(CircleShape)
                .background(fill)
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

@Composable
fun ScreenTitle(title: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 27.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

@Composable
fun TinyPill(text: String, bg: Color, fg: Color) {
    Text(
        text,
        color = fg,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.3.sp,
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
fun IconMeta(icon: ImageVector, text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
