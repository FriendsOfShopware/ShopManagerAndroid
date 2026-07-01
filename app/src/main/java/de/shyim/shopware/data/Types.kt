package de.shyim.shopware.data

import androidx.compose.ui.graphics.Color

// Per-shop pastel tint (precomputed from the original design's oklch values)
data class ShopTint(
    val lightBg: Color, val lightFg: Color,
    val darkBg: Color, val darkFg: Color,
) {
    fun bg(dark: Boolean) = if (dark) darkBg else lightBg
    fun fg(dark: Boolean) = if (dark) darkFg else lightFg
}

enum class PromoStatus { Active, Scheduled, Ended }

data class Delta(val pct: Int, val up: Boolean, val label: String)
