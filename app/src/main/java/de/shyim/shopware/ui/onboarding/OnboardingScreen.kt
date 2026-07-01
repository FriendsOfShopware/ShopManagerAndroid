package de.shyim.shopware.ui.onboarding

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.shyim.shopware.R
import kotlin.math.sin

private data class OnboardPage(@StringRes val title: Int, @StringRes val body: Int)

private val pages = listOf(
    OnboardPage(R.string.onboarding_page1_title, R.string.onboarding_page1_body),
    OnboardPage(R.string.onboarding_page2_title, R.string.onboarding_page2_body),
    OnboardPage(R.string.onboarding_page3_title, R.string.onboarding_page3_body),
)

@Composable
fun OnboardingScreen(
    onConnect: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val pagerState = rememberPagerState { pages.size }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(bottom = 24.dp)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { page ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 480.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when (page) {
                            0 -> DashboardVignette()
                            1 -> OrdersVignette()
                            else -> SyncVignette()
                        }
                    }
                    Text(
                        stringResource(pages[page].title),
                        color = cs.onSurface,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.8).sp,
                        lineHeight = 38.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 28.dp)
                    )
                    Text(
                        stringResource(pages[page].body),
                        color = cs.onSurfaceVariant,
                        fontSize = 14.5.sp,
                        lineHeight = 21.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }

        // Page indicator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(pages.size) { i ->
                val selected = pagerState.currentPage == i
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .height(8.dp)
                        .width(if (selected) 24.dp else 8.dp)
                        .clip(CircleShape)
                        .background(if (selected) cs.primary else cs.surfaceContainerHighest)
                        .animateContentSize()
                )
            }
        }

        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 28.dp)
        ) {
            Button(
                onClick = onConnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text(stringResource(R.string.onboarding_connect_your_shop), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ── Vignettes — live previews built from the app's own visual language ──

@Composable
private fun DashboardVignette() {
    val cs = MaterialTheme.colorScheme
    val phase by rememberInfiniteTransition(label = "bars").animateFloat(
        initialValue = 0f, targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing)),
        label = "phase"
    )
    val week = listOf(0.45f, 0.6f, 0.5f, 0.72f, 0.95f, 0.85f, 0.66f)

    Surface(shape = RoundedCornerShape(32.dp), color = cs.primaryContainer) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                stringResource(R.string.home_sales_today),
                color = cs.onPrimaryContainer.copy(alpha = 0.85f),
                fontSize = 13.sp, fontWeight = FontWeight.Medium
            )
            Text(
                "€2,840",
                color = cs.onPrimaryContainer,
                fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(
                modifier = Modifier
                    .padding(top = 20.dp)
                    .height(96.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                week.forEachIndexed { i, h ->
                    val wave = 0.86f + 0.14f * sin(phase + i * 0.9f)
                    Box(
                        modifier = Modifier
                            .width(26.dp)
                            .height(96.dp * h * wave)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (i == 4) cs.onPrimaryContainer
                                else cs.onPrimaryContainer.copy(alpha = 0.25f)
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun OrdersVignette() {
    val cs = MaterialTheme.colorScheme
    val t by rememberInfiniteTransition(label = "orders").animateFloat(
        initialValue = 0f, targetValue = 4.4f,
        animationSpec = infiniteRepeatable(tween(4400, easing = LinearEasing), RepeatMode.Restart),
        label = "t"
    )
    val rows = listOf(
        Triple("#10248", "Elena V.", "€184"),
        Triple("#10247", "Henrik O.", "€96"),
        Triple("#10246", "Sofia M.", "€241"),
    )
    Surface(shape = RoundedCornerShape(32.dp), color = cs.surfaceContainerLow) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            rows.forEachIndexed { i, (num, name, amount) ->
                val a = (t - i * 0.8f).coerceIn(0f, 1f)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = cs.surfaceContainerLowest,
                    modifier = Modifier
                        .alpha(a)
                        .offset(y = ((1f - a) * 14).dp)
                ) {
                    Row(
                        modifier = Modifier
                            .width(248.dp)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(cs.secondaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Storefront, contentDescription = null,
                                tint = cs.onSecondaryContainer, modifier = Modifier.size(18.dp)
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp)
                        ) {
                            Text(num, color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(name, color = cs.onSurfaceVariant, fontSize = 11.sp)
                        }
                        Text(amount, color = cs.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncVignette() {
    val cs = MaterialTheme.colorScheme
    val infinite = rememberInfiniteTransition(label = "sync")
    val rotation by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(14000, easing = LinearEasing)),
        label = "rot"
    )
    val pulse by infinite.animateFloat(
        initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse"
    )

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(230.dp)
                .rotate(rotation)
                .clip(RoundedCornerShape(38))
                .background(cs.secondaryContainer.copy(alpha = 0.55f))
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(22.dp), color = cs.primaryContainer) {
                Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Cloud, contentDescription = null,
                        tint = cs.onPrimaryContainer, modifier = Modifier.size(34.dp)
                    )
                }
            }
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                repeat(3) { i ->
                    val a = if (pulse.toInt() % 3 == i) 1f else 0.3f
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .alpha(a)
                            .background(cs.primary)
                    )
                }
            }
            Surface(shape = RoundedCornerShape(22.dp), color = cs.tertiaryContainer) {
                Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Storefront, contentDescription = null,
                        tint = cs.onTertiaryContainer, modifier = Modifier.size(34.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(0.dp))
    }
}
