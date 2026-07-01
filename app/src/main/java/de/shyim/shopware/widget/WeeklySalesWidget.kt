package de.shyim.shopware.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import de.shyim.shopware.MainActivity
import de.shyim.shopware.R

// Shared forest palette with the today widget
private val Container = Color(0xFFA6F2BF)
private val OnContainer = Color(0xFF00210F)
private val BarDim = Color(0x3300210F)
private val Good = Color(0xFF1E6B3C)
private val Bad = Color(0xFF8C1D18)

class WeeklySalesWidget : GlanceAppWidget() {

    // Reflow to the actual cell size so the chart fills a large tablet cell
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = WidgetData.readWeeklySnapshot(context)
        provideContent {
            GlanceTheme { WeeklyContent(context, state) }
        }
    }
}

@Composable
private fun WeeklyContent(context: Context, state: WeeklyWidgetState?) {
    val size = LocalSize.current
    val big = size.width >= 320.dp && size.height >= 160.dp
    val pad = if (big) 20.dp else 14.dp
    val totalSp = if (big) 34.sp else 24.sp
    val labelSp = if (big) 15.sp else 12.sp
    // Bar area = remaining cell height minus the header block + padding; the chart fills it.
    val chartMaxDp = (size.height.value - (if (big) 110 else 86)).coerceIn(40f, 320f)
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(20.dp)
            .background(ColorProvider(Container))
            .padding(pad)
            .clickable(actionStartActivity(MainActivity::class.java)),
        contentAlignment = Alignment.TopStart,
    ) {
        if (state == null) {
            Column(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    context.getString(R.string.widget_no_shop),
                    style = TextStyle(color = ColorProvider(OnContainer), fontSize = 13.sp),
                )
            }
            return@Box
        }

        Column(modifier = GlanceModifier.fillMaxSize()) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    context.getString(R.string.widget_sales_week),
                    style = TextStyle(color = ColorProvider(OnContainer), fontSize = labelSp, fontWeight = FontWeight.Medium),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Text(
                    state.deltaLabel,
                    style = TextStyle(
                        color = ColorProvider(if (state.deltaUp) Good else Bad),
                        fontSize = labelSp, fontWeight = FontWeight.Bold,
                    ),
                )
            }
            Spacer(modifier = GlanceModifier.height(4.dp))
            Text(
                state.weekTotalText,
                style = TextStyle(color = ColorProvider(OnContainer), fontSize = totalSp, fontWeight = FontWeight.Bold),
            )
            Spacer(modifier = GlanceModifier.height(if (big) 14.dp else 10.dp))
            Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                BarChart(state.bars, state.todayIndex, chartMaxDp.toInt())
            }
        }
    }
}

// 7 bars filling the remaining height. Each column is a full-height box with the bar
// anchored to the bottom; bar height = fraction of the column via a dp scale keyed off the
// measured cell height (Glance can't do weighted *partial* fills cleanly).
@Composable
private fun BarChart(bars: List<Float>, todayIndex: Int, maxBarDp: Int) {
    Row(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.Bottom,
    ) {
        bars.forEachIndexed { i, frac ->
            Box(
                modifier = GlanceModifier.defaultWeight().padding(horizontal = 2.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                val h = (frac.coerceIn(0f, 1f) * maxBarDp).toInt() + 3
                Box(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .height(h.dp)
                        .cornerRadius(3.dp)
                        .background(ColorProvider(if (i == todayIndex) OnContainer else BarDim))
                ) {}
            }
        }
    }
}

class WeeklySalesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeeklySalesWidget()
}
