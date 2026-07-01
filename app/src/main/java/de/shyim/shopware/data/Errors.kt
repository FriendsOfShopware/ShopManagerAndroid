package de.shyim.shopware.data

import android.content.Context
import de.shyim.shopware.R
import de.shyim.shopware.api.ApiError

// One place to turn any failure into a user-facing message. Maps the ApiError subtypes to
// localized, friendly strings (instead of leaking server text or English defaults) and falls back
// to the throwable's own message, then a generic string. Use everywhere a VM surfaces an error.
fun Throwable.userMessage(context: Context): String = userMessage(context::getString)

// Pure core (string id → string) so the mapping is unit-testable without an Android Context.
fun Throwable.userMessage(getString: (Int) -> String): String = when (this) {
    is ApiError.AuthExpired -> getString(R.string.auth_session_expired)
    is ApiError.Network -> getString(R.string.error_network)
    is ApiError.Forbidden -> message ?: getString(R.string.error_access_denied)
    is ApiError.NotFound -> getString(R.string.error_not_found)
    // Auth/Validation/Server/Unexpected carry a server message worth showing; else generic.
    is ApiError -> message ?: getString(R.string.error_generic)
    else -> message ?: getString(R.string.error_generic)
}
