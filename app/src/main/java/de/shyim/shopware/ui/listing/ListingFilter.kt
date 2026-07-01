package de.shyim.shopware.ui.listing

import de.shyim.shopware.api.Criteria
import de.shyim.shopware.api.ShopApi
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.ZoneId

data class FilterOption(val id: String, val label: String)

// Declarative per-listing filter config, mirroring the web admin's filterFactory
sealed class ListingFilter {
    abstract val key: String
    abstract val label: String

    data class Text(
        override val key: String,
        override val label: String,
        val field: String,
        val mode: Mode = Mode.Contains,
    ) : ListingFilter() {
        enum class Mode { Contains, Equals, Prefix }
    }

    data class NumberRange(
        override val key: String,
        override val label: String,
        val field: String,
    ) : ListingFilter()

    data class DateRange(
        override val key: String,
        override val label: String,
        val field: String,
        val withPresets: Boolean = true,
    ) : ListingFilter()

    data class Options(
        override val key: String,
        override val label: String,
        val field: String,
        val multi: Boolean = true,
        val staticOptions: List<FilterOption>? = null,
        val loadOptions: (suspend (ShopApi) -> List<FilterOption>)? = null,
    ) : ListingFilter()

    // Boolean field as two static options ("true"/"false" ids); emits equals(field, Boolean)
    data class Bool(
        override val key: String,
        override val label: String,
        val field: String,
        val trueLabel: String,
        val falseLabel: String,
    ) : ListingFilter()

    // field must already include ".id" where the existence check targets an association
    data class Existence(
        override val key: String,
        override val label: String,
        val field: String,
        val hasLabel: String,
        val hasNotLabel: String,
    ) : ListingFilter()
}

// Shared across the Orders/Customers/Reviews/Promos listings. `field` is the entity's
// salesChannelId FK; promotions use the m:n path "salesChannels.salesChannelId".
fun salesChannelFilter(label: String, field: String = "salesChannelId") = ListingFilter.Options(
    "salesChannel", label, field,
    loadOptions = { api ->
        api.repository("sales-channel").search(
            Criteria()
                .setLimit(100)
                .addSorting("name")
                .addIncludes("sales_channel", listOf("id", "name", "translated"))
        ).data.mapNotNull { c -> c.id?.let { FilterOption(it, c.translated("name") ?: "—") } }
    },
)

sealed class FilterValue {
    abstract val isEmpty: Boolean

    data class TextValue(val text: String) : FilterValue() {
        override val isEmpty get() = text.isBlank()
    }

    data class RangeValue(val min: Double? = null, val max: Double? = null) : FilterValue() {
        override val isEmpty get() = min == null && max == null
    }

    data class DateRangeValue(val from: LocalDate? = null, val to: LocalDate? = null) : FilterValue() {
        override val isEmpty get() = from == null && to == null
    }

    data class OptionsValue(val ids: Set<String>) : FilterValue() {
        override val isEmpty get() = ids.isEmpty()
    }

    data class ExistenceValue(val has: Boolean?) : FilterValue() {
        override val isEmpty get() = has == null
    }
}

fun ListingFilter.criteriaFor(value: FilterValue): List<JsonObject> {
    if (value.isEmpty) return emptyList()
    return when {
        this is ListingFilter.Text && value is FilterValue.TextValue -> listOf(
            when (mode) {
                ListingFilter.Text.Mode.Contains -> Criteria.contains(field, value.text)
                ListingFilter.Text.Mode.Equals -> Criteria.equals(field, value.text)
                ListingFilter.Text.Mode.Prefix -> Criteria.prefix(field, value.text)
            }
        )

        this is ListingFilter.NumberRange && value is FilterValue.RangeValue ->
            listOf(Criteria.range(field, gte = value.min, lte = value.max))

        this is ListingFilter.DateRange && value is FilterValue.DateRangeValue -> {
            val zone = ZoneId.systemDefault()
            listOf(
                Criteria.range(
                    field,
                    gte = value.from?.atStartOfDay(zone)?.toInstant()?.toString(),
                    // exclusive next-day bound keeps the full "to" day incl. sub-second timestamps
                    lt = value.to?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toString(),
                )
            )
        }

        this is ListingFilter.Options && value is FilterValue.OptionsValue ->
            listOf(Criteria.equalsAny(field, value.ids.toList()))

        this is ListingFilter.Bool && value is FilterValue.OptionsValue ->
            value.ids.singleOrNull()?.let { listOf(Criteria.equals(field, it == "true")) } ?: emptyList()

        this is ListingFilter.Existence && value is FilterValue.ExistenceValue -> listOf(
            if (value.has == true) Criteria.not("and", Criteria.equals(field, null))
            else Criteria.equals(field, null)
        )

        else -> emptyList()
    }
}
