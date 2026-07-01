package de.shyim.shopware.data.source

import de.shyim.shopware.api.Criteria
import de.shyim.shopware.api.ShopApi
import de.shyim.shopware.api.SwEntity
import de.shyim.shopware.data.model.CountryOption
import de.shyim.shopware.data.model.CustomerDetail
import de.shyim.shopware.data.model.EditableAddress
import de.shyim.shopware.data.model.SalutationOption
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

suspend fun ShopApi.fetchCustomerDetail(customerId: String): CustomerDetail? {
    val c = repository("customer").get(
        customerId,
        Criteria()
            .addAssociation("defaultBillingAddress.country")
            .addAssociation("defaultShippingAddress.country")
            .addAssociation("group")
    ) ?: return null

    val billing = c.entity("defaultBillingAddress")
    val shipping = c.entity("defaultShippingAddress")
    val billingId = c.string("defaultBillingAddressId")
    val shippingId = c.string("defaultShippingAddressId")
    val dateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
        .withZone(ZoneId.systemDefault())

    return CustomerDetail(
        id = customerId,
        name = listOfNotNull(c.string("firstName"), c.string("lastName"))
            .joinToString(" ").ifBlank { "—" },
        email = c.string("email") ?: "",
        customerNumber = c.string("customerNumber") ?: "",
        group = c.entity("group")?.translated("name"),
        active = c.boolean("active") ?: false,
        guest = c.boolean("guest") ?: false,
        orderCount = c.int("orderCount") ?: 0,
        totalSpend = c.double("orderTotalAmount") ?: 0.0,
        lastOrderMs = c.instant("lastOrderDate")?.toEpochMilli(),
        customerSince = c.instant("createdAt")?.let { dateFmt.format(it) },
        billingAddress = billing?.let(::formatAddress),
        shippingAddress = shipping?.let(::formatAddress)
            ?.takeIf { it != billing?.let(::formatAddress) },
        phone = billing?.string("phoneNumber") ?: shipping?.string("phoneNumber"),
        firstName = c.string("firstName") ?: "",
        lastName = c.string("lastName") ?: "",
        title = c.string("title"),
        company = c.string("company"),
        salutationId = c.string("salutationId"),
        billing = billing?.let(::parseEditableAddress),
        shipping = shipping?.let(::parseEditableAddress),
        sharedAddress = billingId != null && billingId == shippingId,
    )
}

private fun parseEditableAddress(a: SwEntity): EditableAddress = EditableAddress(
    id = a.id ?: "",
    firstName = a.string("firstName") ?: "",
    lastName = a.string("lastName") ?: "",
    street = a.string("street") ?: "",
    additionalLine = a.string("additionalAddressLine1"),
    zipcode = a.string("zipcode") ?: "",
    city = a.string("city") ?: "",
    company = a.string("company"),
    phoneNumber = a.string("phoneNumber"),
    countryId = a.string("countryId"),
    countryName = a.entity("country")?.translated("name"),
)

// Patch only customer-level contact fields. Empty optional strings are sent as null.
suspend fun ShopApi.saveCustomerContact(
    customerId: String,
    firstName: String,
    lastName: String,
    email: String,
    salutationId: String?,
    title: String?,
    company: String?,
) {
    val payload = buildJsonObject {
        put("firstName", firstName)
        put("lastName", lastName)
        put("email", email)
        salutationId?.let { put("salutationId", it) }
        putNullable("title", title)
        putNullable("company", company)
    }
    repository("customer").patch(customerId, payload)
}

// Patch one customer_address record.
suspend fun ShopApi.saveCustomerAddress(address: EditableAddress) {
    val payload = buildJsonObject {
        put("firstName", address.firstName)
        put("lastName", address.lastName)
        put("street", address.street)
        put("zipcode", address.zipcode)
        put("city", address.city)
        address.countryId?.let { put("countryId", it) }
        putNullable("additionalAddressLine1", address.additionalLine)
        putNullable("company", address.company)
        putNullable("phoneNumber", address.phoneNumber)
    }
    repository("customer-address").patch(address.id, payload)
}

suspend fun ShopApi.fetchSalutations(): List<SalutationOption> =
    repository("salutation").search(
        Criteria().addSorting("salutationKey")
            .addIncludes("salutation", listOf("id", "displayName", "translated", "salutationKey"))
    ).data.mapNotNull { s ->
        s.id?.let { SalutationOption(it, s.translated("displayName") ?: s.string("salutationKey") ?: "—") }
    }

suspend fun ShopApi.fetchCountries(): List<CountryOption> =
    repository("country").search(
        Criteria()
            .setLimit(500)
            .addFilter(Criteria.equals("active", true))
            .addSorting("name")
            .addIncludes("country", listOf("id", "name", "translated"))
    ).data.mapNotNull { c -> c.id?.let { CountryOption(it, c.translated("name") ?: "—") } }

// Blank optional → JSON null so the server clears the field.
private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: String?) {
    val trimmed = value?.trim()
    if (trimmed.isNullOrEmpty()) put(key, kotlinx.serialization.json.JsonNull)
    else put(key, trimmed)
}
