package de.shyim.shopware.data

import de.shyim.shopware.data.model.ConnectedShop
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.roundToLong

fun currencySymbol(iso: String): String =
    runCatching { Currency.getInstance(iso).symbol }.getOrDefault(iso)

fun fmtMoney(amount: Double, currencyIso: String = "EUR", localeTag: String? = null): String {
    val locale = localeTag?.let { Locale.forLanguageTag(it) } ?: Locale.UK
    return runCatching {
        NumberFormat.getCurrencyInstance(locale).apply {
            maximumFractionDigits = 0
            currency = Currency.getInstance(currencyIso)
        }.format(amount)
    }.getOrElse { currencySymbol(currencyIso) + "%,d".format(Locale.UK, amount.roundToLong()) }
}

fun ConnectedShop.fmt(amount: Double): String = fmtMoney(amount, currency, localeCode)

// Compact money for tight spots (per-bar chart labels): €1.2k, €53k, €1.4M.
fun fmtMoneyCompact(amount: Double, currencyIso: String = "EUR"): String {
    val sym = currencySymbol(currencyIso)
    val abs = kotlin.math.abs(amount)
    return when {
        abs >= 1_000_000 -> "$sym%.1fM".format(Locale.UK, amount / 1_000_000)
        abs >= 1_000 -> "$sym%.1fk".format(Locale.UK, amount / 1_000)
        else -> sym + amount.roundToLong().toString()
    }.replace(".0k", "k").replace(".0M", "M")
}

fun ConnectedShop.fmtCompact(amount: Double): String = fmtMoneyCompact(amount, currency)

fun delta(today: Double, yesterday: Double): Delta {
    val pct = if (yesterday <= 0.0) {
        if (today > 0.0) 100 else 0
    } else {
        ((today - yesterday) * 100.0 / yesterday).roundToLong().toInt()
    }
    return Delta(pct, pct >= 0, "${if (pct >= 0) "+" else ""}$pct%")
}
