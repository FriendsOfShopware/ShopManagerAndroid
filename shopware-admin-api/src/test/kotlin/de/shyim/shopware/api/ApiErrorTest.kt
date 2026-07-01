package de.shyim.shopware.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Fixtures captured from a live Shopware 6.7.8 instance
class ApiErrorTest {

    @Test
    fun `400 validation envelope with two errors, one null detail`() {
        val body = """
            {"errors":[
              {"status":"400","code":"FRAMEWORK__INVALID_LIMIT_QUERY","title":"Bad Request",
               "detail":"The limit parameter must be a positive integer greater or equals than 1. Given: abc",
               "source":{"pointer":"/limit"},"meta":{"parameters":{"limit":"abc"}}},
              {"code":"6","status":"400","title":"The user credentials were incorrect.","detail":null}
            ]}
        """.trimIndent()

        val error = ApiError.parse(400, body) as ApiError.Validation

        assertEquals(2, error.violations.size)
        assertEquals("FRAMEWORK__INVALID_LIMIT_QUERY", error.violations[0].code)
        assertEquals("/limit", error.violations[0].pointer)
        assertEquals(
            "The limit parameter must be a positive integer greater or equals than 1. Given: abc",
            error.violations[0].detail,
        )
        assertNull(error.violations[1].detail)
        assertEquals("The user credentials were incorrect.", error.violations[1].title)
        assertEquals(
            "The limit parameter must be a positive integer greater or equals than 1. Given: abc",
            error.message,
        )
    }

    @Test
    fun `401 oauth error shape`() {
        val body = """{"error":"invalid_client","error_description":"Client authentication failed"}"""

        val error = ApiError.parse(401, body)

        assertTrue(error is ApiError.Auth)
        assertEquals("Client authentication failed", error.message)
    }

    @Test
    fun `401 errors envelope`() {
        val body =
            """{"errors":[{"code":"9","status":"401","title":"The resource owner or authorization server denied the request.","detail":"The JWT string must have two dots"}]}"""

        val error = ApiError.parse(401, body)

        assertTrue(error is ApiError.Auth)
        assertEquals("The JWT string must have two dots", error.message)
    }

    @Test
    fun `403 with missingPrivileges in JSON-string detail`() {
        val body = """
            {"errors":[{"code":"FRAMEWORK__MISSING_PRIVILEGE_ERROR","status":"403","title":"Forbidden",
             "detail":"{\"message\":\"Missing privilege\",\"missingPrivileges\":[\"product_review:update\"]}"}]}
        """.trimIndent()

        val error = ApiError.parse(403, body) as ApiError.Forbidden

        assertEquals(listOf("product_review:update"), error.missingPrivileges)
        assertEquals("Missing privilege", error.message)
    }

    @Test
    fun `403 with missingPrivileges as plain array in meta`() {
        val body = """
            {"errors":[{"code":"FRAMEWORK__MISSING_PRIVILEGE_ERROR","status":"403","title":"Forbidden",
             "detail":null,"meta":{"parameters":{"missingPrivileges":["order:read","order:update"]}}}]}
        """.trimIndent()

        val error = ApiError.parse(403, body) as ApiError.Forbidden

        assertEquals(listOf("order:read", "order:update"), error.missingPrivileges)
        assertEquals("Forbidden", error.message)
    }

    @Test
    fun `404 envelope`() {
        val body =
            """{"errors":[{"code":"0","status":"404","title":"Not Found","detail":"No route found for POST http:\/\/localhost:8000\/api\/search\/nonexistent-entity"}]}"""

        val error = ApiError.parse(404, body)

        assertTrue(error is ApiError.NotFound)
        assertEquals("No route found for POST http://localhost:8000/api/search/nonexistent-entity", error.message)
    }

    @Test
    fun `500 with HTML body`() {
        val error = ApiError.parse(500, "<html><body><h1>Internal Server Error</h1></body></html>")

        assertTrue(error is ApiError.Server)
        assertEquals(500, (error as ApiError.Server).status)
        assertEquals("HTTP 500", error.message)
    }

    @Test
    fun `empty body falls back to HTTP status message`() {
        val error = ApiError.parse(502, "")
        assertTrue(error is ApiError.Server)
        assertEquals("HTTP 502", error.message)

        val nullBody = ApiError.parse(418, null)
        assertTrue(nullBody is ApiError.Unexpected)
        assertEquals(418, (nullBody as ApiError.Unexpected).status)
        assertEquals("HTTP 418", nullBody.message)
    }

    @Test
    fun `network error keeps cause`() {
        val cause = java.net.SocketTimeoutException("timeout")
        val error = ApiError.Network(cause)
        assertEquals(cause, error.cause)
        assertEquals("timeout", error.message)
    }
}
