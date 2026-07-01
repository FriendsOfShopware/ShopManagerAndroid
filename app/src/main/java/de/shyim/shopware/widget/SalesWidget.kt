package de.shyim.shopware.widget

import android.content.Context
import androidx.compose.runtime.Composable
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
import androidx.glance.appwidget.updateAll
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
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import de.shyim.shopware.MainActivity
import de.shyim.shopware.R

// Forest palette (matches the in-app hero card's primaryContainer / onPrimaryContainer)
private val Container = Color(0xFFA6F2BF)
private val OnContainer = Color(0xFF00210F)
private val Track = Color(0x2900210F)
private val Good = Color(0xFF1E6B3C)
private val Bad = Color(0xFF8C1D18)

class SalesWidget : GlanceAppWidget() {

    // Reflow to the actual cell size (tablets get much larger cells than phones)
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // read cache off the IO thread (provideGlance is already a suspend context)
        val state = WidgetData.readSelectedShopSnapshot(context)
        provideContent {
            GlanceTheme { WidgetContent(context, state) }
        }
    }
}

@Composable
private fun WidgetContent(context: Context, state: WidgetState?) {
    val openApp = actionStartActivity(MainActivity::class.java)
    // Scale type to the cell so a big tablet cell isn't a small label lost in a sea of green
    val size = LocalSize.current
    val big = size.width >= 260.dp && size.height >= 130.dp
    val pad = if (big) 20.dp else 14.dp
    val revenueSp = if (big) 40.sp else 26.sp
    val labelSp = if (big) 15.sp else 12.sp
    val footSp = if (big) 13.sp else 10.5.sp
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(20.dp)
            .background(ColorProvider(Container))
            .padding(pad)
            .clickable(openApp),
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
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    context.getString(R.string.home_sales_today),
                    style = TextStyle(
                        color = ColorProvider(OnContainer),
                        fontSize = labelSp, fontWeight = FontWeight.Medium,
                    ),
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
            Spacer(modifier = GlanceModifier.height(if (big) 8.dp else 4.dp))
            Text(
                state.revenueText,
                style = TextStyle(
                    color = ColorProvider(OnContainer),
                    fontSize = revenueSp, fontWeight = FontWeight.Bold,
                ),
            )

            if (state.targetPct != null && state.targetText != null) {
                Spacer(modifier = GlanceModifier.height(if (big) 12.dp else 8.dp))
                ProgressRow(state.targetPct)
                Spacer(modifier = GlanceModifier.height(3.dp))
                Text(
                    context.getString(
                        R.string.home_target_progress,
                        (state.targetPct * 100).toInt(),
                        state.targetText,
                    ),
                    style = TextStyle(color = ColorProvider(OnContainer), fontSize = if (big) 12.sp else 10.sp),
                )
            }

            Spacer(modifier = GlanceModifier.defaultWeight())
            Text(
                state.shopName,
                style = TextStyle(color = ColorProvider(OnContainer), fontSize = footSp),
            )
        }
    }
}

// Glance has no progress bar; draw it as two stacked rounded boxes
@Composable
private fun ProgressRow(pct: Float) {
    val bars = 20
    val filled = (pct * bars).toInt().coerceIn(0, bars)
    Row(modifier = GlanceModifier.fillMaxWidth().height(6.dp)) {
        repeat(bars) { i ->
            Box(
                modifier = GlanceModifier
                    .defaultWeight()
                    .height(6.dp)
                    .padding(end = 1.dp)
                    .cornerRadius(3.dp)
                    .background(ColorProvider(if (i < filled) OnContainer else Track))
            ) {}
        }
    }
}

class SalesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SalesWidget()
}

// Repaints all placed widgets from the cache; safe no-op when none are placed.
// Called from AppRepository after a snapshot write or shop switch.
suspend fun refreshSalesWidgets(context: Context) {
    runCatching { SalesWidget().updateAll(context) }
    runCatching { WeeklySalesWidget().updateAll(context) }
}
