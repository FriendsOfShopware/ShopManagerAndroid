package de.shyim.shopware.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed class ApiError(message: String) : Exception(message) {

    class Network(cause: Throwable) : ApiError(cause.message ?: "Network error") {
        init {
            initCause(cause)
        }
    }

    class Auth(message: String) : ApiError(message)

    // The stored refresh token was rejected (revoked/expired) — the user must sign in again.
    // Thrown only by the token refresh path, never by ordinary 401 responses.
    class AuthExpired(message: String) : ApiError(message)

    class Forbidden(message: String, val missingPrivileges: List<String>) : ApiError(message)

    class NotFound(message: String) : ApiError(message)

    class Validation(val violations: List<Violation>) :
        ApiError(violations.firstNotNullOfOrNull { it.detail ?: it.title } ?: "Validation failed")

    class Server(val status: Int, message: String) : ApiError(message)

    class Unexpected(val status: Int, message: String) : ApiError(message)

    data class Violation(
        val code: String?,
        val title: String?,
        val detail: String?,
        val pointer: String?,
    )

    companion object {
        private val json = ShopwareHttp.json

        fun parse(status: Int, body: String?): ApiError {
            val root = body
                ?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } as? JsonObject
            val errors = (root?.get("errors") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val violations = errors.map {
                Violation(
                    code = it.text("code"),
                    title = it.text("title"),
                    detail = it.text("detail"),
                    pointer = (it["source"] as? JsonObject)?.text("pointer"),
                )
            }
            val message = violations.firstNotNullOfOrNull { it.detail ?: it.title }
                ?: root?.text("error_description")
                ?: root?.text("error")
                ?: "HTTP $status"
            return when {
                status == 400 && violations.isNotEmpty() -> Validation(violations)
                status == 401 -> Auth(message)
                // 6.7 serializes the 403 detail as a JSON blob; show its inner message instead
                status == 403 -> Forbidden(forbiddenMessage(violations) ?: message, missingPrivileges(errors))
                status == 404 -> NotFound(message)
                status in 500..599 -> Server(status, message)
                else -> Unexpected(status, message)
            }
        }

        private fun forbiddenMessage(violations: List<Violation>): String? =
            violations.firstNotNullOfOrNull { v ->
                (v.detail
                    ?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } as? JsonObject)
                    ?.let { it.text("message") ?: v.title }
            }

        private fun missingPrivileges(errors: List<JsonObject>): List<String> =
            errors.firstNotNullOfOrNull { error ->
                privileges(error["meta"])
                    ?: error.text("detail")?.let { detail ->
                        privileges(runCatching { json.parseToJsonElement(detail) }.getOrNull())
                    }
            } ?: emptyList()

        // Accepts a plain array of privileges, or an object carrying them under
        // "missingPrivileges" (directly or below "parameters", as 403 meta does)
        private fun privileges(element: JsonElement?): List<String>? = when (element) {
            is JsonArray -> element
                .mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.content }
                .takeIf { it.isNotEmpty() }
            is JsonObject -> privileges(element["missingPrivileges"]) ?: privileges(element["parameters"])
            else -> null
        }

        private fun JsonObject.text(field: String): String? =
            (this[field] as? JsonPrimitive)
                ?.takeUnless { it is JsonNull }
                ?.content
                ?.takeIf { it.isNotBlank() }
    }
}
