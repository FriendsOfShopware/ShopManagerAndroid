package de.shyim.shopware.api

data class ApiContext(
    val languageId: String? = null,
    val inheritance: Boolean = true,
    val currencyId: String? = null,
    val versionId: String? = null,
) {
    fun headers(): Map<String, String> = buildMap {
        languageId?.let { put("sw-language-id", it) }
        put("sw-inheritance", inheritance.toString())
        currencyId?.let { put("sw-currency-id", it) }
        versionId?.let { put("sw-version-id", it) }
    }
}
