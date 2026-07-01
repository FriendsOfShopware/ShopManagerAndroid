package de.shyim.shopware.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

sealed class PlainAuth {
    // Used only for the initial connect and for sign-in-again; the password is never persisted
    data class Password(val username: String, val password: String) : PlainAuth()
    data class RefreshToken(val token: String) : PlainAuth()
}

object ShopwareHttp {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val client: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = 25_000
                connectTimeoutMillis = 10_000
            }
            expectSuccess = false
        }
    }

    // Normalizes user input to a base URL without trailing slash or /api suffix
    fun normalizeBaseUrl(input: String): String {
        var url = input.trim().removeSuffix("/")
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        return url.removeSuffix("/api").removeSuffix("/admin")
    }

    // A Shopware instance answers the token endpoint with a JSON error envelope even for bad requests
    suspend fun probeShopware(baseUrl: String): Result<Unit> = runCatching {
        val resp = client.post("$baseUrl/api/oauth/token") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("grant_type", "client_credentials") })
        }
        val body = resp.bodyAsText()
        val looksShopware = body.contains("\"errors\"") || body.contains("invalid_") || resp.status.isSuccess()
        if (!looksShopware) {
            throw ApiError.Unexpected(
                resp.status.value,
                "Reachable, but no Shopware Admin API found (HTTP ${resp.status.value})",
            )
        }
    }
}

class ShopwareClient(
    val baseUrl: String,
    private val auth: PlainAuth,
    private val context: ApiContext = ApiContext(),
    private val http: HttpClient = ShopwareHttp.client,
    // Refresh tokens are single-use (the server rotates and revokes on every refresh);
    // the new token is awaited here so it is persisted before the old one is gone for good.
    private val onRefreshToken: (suspend (String) -> Unit)? = null,
) {
    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var expiresAt: Long = 0

    @Volatile
    var currentRefreshToken: String? = (auth as? PlainAuth.RefreshToken)?.token
        private set

    private val grantMutex = Mutex()

    private suspend fun grant(): String = grantMutex.withLock {
        // another caller may have granted while this one waited on the lock
        accessToken?.let { if (System.currentTimeMillis() < expiresAt) return@withLock it }

        currentRefreshToken?.takeIf { it.isNotBlank() }?.let { refresh ->
            val resp = tokenRequest(buildJsonObject {
                put("grant_type", "refresh_token")
                put("client_id", "administration")
                put("refresh_token", refresh)
            })
            if (resp.status.isSuccess()) return@withLock storeTokens(resp)
            // 400/401 here means the refresh token is revoked or expired. Anything else
            // (5xx, proxies) is a transient failure and must not end the session.
            if (resp.status.value !in 400..401) throw ApiError.parse(resp.status.value, resp.bodyAsText())
            if (auth !is PlainAuth.Password) {
                throw ApiError.AuthExpired(ApiError.parse(resp.status.value, resp.bodyAsText()).message ?: "Session expired")
            }
            // initial-connect flow still holds the password — fall through and re-grant
        }

        when (auth) {
            is PlainAuth.Password -> {
                val resp = tokenRequest(buildJsonObject {
                    put("grant_type", "password")
                    put("client_id", "administration")
                    put("scopes", "write")
                    put("username", auth.username)
                    put("password", auth.password)
                })
                if (!resp.status.isSuccess()) throw ApiError.parse(resp.status.value, resp.bodyAsText())
                storeTokens(resp)
            }
            // currentRefreshToken was null or just rejected, and there is nothing to fall back to
            is PlainAuth.RefreshToken -> throw ApiError.AuthExpired("Session expired")
        }
    }

    private suspend fun tokenRequest(body: JsonObject): HttpResponse = perform {
        http.post("$baseUrl/api/oauth/token") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    private suspend fun storeTokens(resp: HttpResponse): String {
        val obj = resp.body<JsonObject>()
        val token = obj["access_token"]?.jsonPrimitive?.content
            ?: throw ApiError.Unexpected(resp.status.value, "Token response missing access_token")
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 600
        obj["refresh_token"]?.jsonPrimitive?.content?.let { rotated ->
            if (rotated != currentRefreshToken) {
                currentRefreshToken = rotated
                onRefreshToken?.invoke(rotated)
            }
        }
        accessToken = token
        expiresAt = System.currentTimeMillis() + (expiresIn - 30) * 1000
        return token
    }

    private suspend fun token(): String {
        accessToken?.let { if (System.currentTimeMillis() < expiresAt) return it }
        return grant()
    }

    // POST /api/search/{entity} in plain-JSON mode
    suspend fun search(entity: String, criteria: JsonObject): JsonObject =
        request { token ->
            http.post("$baseUrl/api/search/$entity") {
                commonHeaders(token)
                contentType(ContentType.Application.Json)
                setBody(criteria)
            }
        }.body()

    // GET on an /api path (e.g. /_admin/dashboard/..., /_action/state-machine/.../state)
    suspend fun getJson(path: String): JsonObject =
        request { token ->
            http.get("$baseUrl/api$path") { commonHeaders(token) }
        }.body()

    // POST to an /_action path (state transitions etc.); body defaults to {}
    suspend fun actionPost(path: String, body: JsonElement? = null): JsonObject? {
        val resp = request { token ->
            http.post("$baseUrl/api$path") {
                commonHeaders(token)
                contentType(ContentType.Application.Json)
                setBody(body ?: buildJsonObject {})
            }
        }
        return runCatching { resp.body<JsonObject>() }.getOrNull()
    }

    // GET binary content (e.g. generated PDF documents)
    suspend fun getBytes(path: String): ByteArray =
        request { token ->
            http.get("$baseUrl/api$path") { commonHeaders(token) }
        }.body()

    // POST binary content (e.g. media uploads)
    suspend fun postBytes(path: String, bytes: ByteArray, mimeType: String) {
        request { token ->
            http.post("$baseUrl/api$path") {
                commonHeaders(token)
                contentType(ContentType.parse(mimeType))
                setBody(bytes)
            }
        }
    }

    suspend fun post(path: String, payload: JsonObject) {
        request { token ->
            http.post("$baseUrl/api$path") {
                commonHeaders(token)
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
        }
    }

    // Bulk write via /_action/sync. Unlike POST (insert-only) and PATCH (update-only on the REST
    // route), the sync `upsert` action inserts-or-updates by primary key, so callers with a stable
    // client-supplied id can register idempotently. `entity` is the raw DAL name (underscored).
    suspend fun sync(entity: String, action: String, payload: JsonArray) {
        val body = buildJsonObject {
            putJsonObject(entity) {
                put("entity", entity)
                put("action", action)
                put("payload", payload)
            }
        }
        request { token ->
            http.post("$baseUrl/api/_action/sync") {
                commonHeaders(token)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
    }

    suspend fun patch(path: String, payload: JsonObject) {
        request { token ->
            http.patch("$baseUrl/api$path") {
                commonHeaders(token)
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
        }
    }

    suspend fun delete(path: String) {
        request { token ->
            http.delete("$baseUrl/api$path") { commonHeaders(token) }
        }
    }

    // Retries once with a fresh grant on 401; any non-2xx becomes an ApiError
    private suspend fun request(call: suspend (String) -> HttpResponse): HttpResponse {
        var resp = perform { call(token()) }
        if (resp.status.value == 401) {
            accessToken = null // the cached token was rejected regardless of its local expiry
            resp = perform { call(token()) }
        }
        if (!resp.status.isSuccess()) throw ApiError.parse(resp.status.value, resp.bodyAsText())
        return resp
    }

    private fun HttpRequestBuilder.commonHeaders(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(HttpHeaders.Accept, "application/json")
        context.headers().forEach { (name, value) -> header(name, value) }
    }

    private suspend fun perform(call: suspend () -> HttpResponse): HttpResponse =
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            throw e
        } catch (e: Exception) {
            throw ApiError.Network(e)
        }
}
