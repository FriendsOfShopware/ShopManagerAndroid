package de.shyim.shopware.data.model

// Live customer detail — fetched on demand, not persisted
data class CustomerDetail(
    val id: String,
    val name: String,
    val email: String,
    val customerNumber: String,
    val group: String?,
    val active: Boolean,
    val guest: Boolean,
    val orderCount: Int,
    val totalSpend: Double,
    val lastOrderMs: Long?,
    val customerSince: String?,
    val billingAddress: String?,
    val shippingAddress: String?,
    val phone: String?,
    // Structured fields for the edit sheet (display uses the formatted strings above)
    val firstName: String = "",
    val lastName: String = "",
    val title: String? = null,
    val company: String? = null,
    val salutationId: String? = null,
    val billing: EditableAddress? = null,
    val shipping: EditableAddress? = null,
    // true when billing and shipping point at the same address record
    val sharedAddress: Boolean = false,
)

// One editable address record (customer_address). countryName is for display only.
data class EditableAddress(
    val id: String,
    val firstName: String = "",
    val lastName: String = "",
    val street: String = "",
    val additionalLine: String? = null,
    val zipcode: String = "",
    val city: String = "",
    val company: String? = null,
    val phoneNumber: String? = null,
    val countryId: String? = null,
    val countryName: String? = null,
)

// Pickers loaded once for the edit sheet
data class SalutationOption(val id: String, val label: String)
data class CountryOption(val id: String, val name: String)
