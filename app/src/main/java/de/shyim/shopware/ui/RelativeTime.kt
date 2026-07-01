package de.shyim.shopware.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import de.shyim.shopware.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

// Models carry raw epoch millis so timestamps re-localize with the app language;
// epochMs <= 0 means "unknown" (legacy persisted snapshots) and reads as "just now".
@Composable
fun relativeAgoText(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val mins = ((nowMs - epochMs) / 60_000).coerceAtLeast(0)
    return when {
        epochMs <= 0 || mins < 1 -> stringResource(R.string.ago_just_now)
        mins < 60 -> stringResource(R.string.ago_minutes, mins)
        mins < 60 * 24 -> stringResource(R.string.ago_hours, mins / 60)
        mins < 60 * 24 * 7 -> {
            val days = (mins / (60 * 24)).toInt()
            pluralStringResource(R.plurals.ago_days, days, days)
        }
        else -> {
            val weeks = (mins / (60 * 24 * 7)).toInt()
            pluralStringResource(R.plurals.ago_weeks, weeks, weeks)
        }
    }
}

// The snapshot's week revenue always covers the 7 days ending on the sync date,
// so the axis labels are derived here in the current app language instead of
// being persisted as formatted strings.
fun weekDayLabels(endEpochMs: Long): List<String> {
    val end = if (endEpochMs > 0) {
        Instant.ofEpochMilli(endEpochMs).atZone(ZoneId.systemDefault()).toLocalDate()
    } else LocalDate.now()
    return (6 downTo 0).map {
        end.minusDays(it.toLong()).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }
}
