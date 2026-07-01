package de.shyim.shopware.data

import de.shyim.shopware.R
import de.shyim.shopware.api.ApiError
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorsTest {
    // map each string id to a stable token so we assert which branch was taken
    private val getString: (Int) -> String = { id ->
        when (id) {
            R.string.auth_session_expired -> "expired"
            R.string.error_network -> "network"
            R.string.error_access_denied -> "denied"
            R.string.error_not_found -> "notfound"
            R.string.error_generic -> "generic"
            else -> "other"
        }
    }

    @Test fun authExpiredMapsToExpired() =
        assertEquals("expired", ApiError.AuthExpired("x").userMessage(getString))

    @Test fun networkMapsToNetwork() =
        assertEquals("network", ApiError.Network(RuntimeException("boom")).userMessage(getString))

    @Test fun forbiddenPrefersServerMessage() =
        assertEquals("Missing privilege", ApiError.Forbidden("Missing privilege", emptyList()).userMessage(getString))

    @Test fun notFoundMapsToNotFound() =
        assertEquals("notfound", ApiError.NotFound("nope").userMessage(getString))

    @Test fun serverErrorShowsItsMessage() =
        assertEquals("Boom 500", ApiError.Server(500, "Boom 500").userMessage(getString))

    @Test fun plainThrowableFallsBackToMessage() =
        assertEquals("raw", RuntimeException("raw").userMessage(getString))

    @Test fun messagelessThrowableFallsBackToGeneric() =
        assertEquals("generic", RuntimeException().userMessage(getString))
}
