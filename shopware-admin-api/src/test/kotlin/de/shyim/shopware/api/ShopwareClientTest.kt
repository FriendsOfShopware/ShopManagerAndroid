package de.shyim.shopware.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// Mirrors the live 6.7.8 token endpoint: password and refresh grants both return a
// rotating refresh_token; a used refresh token is revoked (400, errors envelope).
private class FakeTokenServer {
    var accessCounter = 0
    var refreshCounter = 0
    val validRefreshTokens = mutableSetOf<String>()
    val grantLog = mutableListOf<String>()
    var expiresIn = 600

    fun handle(body: String): Pair<HttpStatusCode, String> {
        val grantType = Regex("\"grant_type\":\"(\\w+)\"").find(body)?.groupValues?.get(1)
        grantLog += grantType ?: "?"
        return when (grantType) {
            "password" ->
                if (body.contains("\"password\":\"correct\"")) HttpStatusCode.OK to issue()
                else HttpStatusCode.BadRequest to
                    """{"errors":[{"code":"6","status":"400","title":"The user credentials were incorrect."}]}"""
            "refresh_token" -> {
                val token = Regex("\"refresh_token\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                if (token != null && validRefreshTokens.remove(token)) HttpStatusCode.OK to issue()
                else HttpStatusCode.BadRequest to
                    """{"errors":[{"code":"8","status":"400","title":"The refresh token is invalid.","detail":"Token has been revoked"}]}"""
            }
            else -> HttpStatusCode.BadRequest to """{"errors":[{"title":"unsupported grant"}]}"""
        }
    }

    private fun issue(): String {
        val access = "access-${accessCounter++}"
        val refresh = "refresh-${refreshCounter++}"
        validRefreshTokens += refresh
        return """{"token_type":"Bearer","expires_in":$expiresIn,"access_token":"$access","refresh_token":"$refresh"}"""
    }
}

class ShopwareClientTest {

    private fun client(
        server: FakeTokenServer,
        auth: PlainAuth,
        onRefreshToken: (suspend (String) -> Unit)? = null,
        api: (HttpRequestData) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to "{}" },
    ): ShopwareClient {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/oauth/token")) {
                val (status, body) = server.handle((request.body as TextContent).text)
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                val (status, body) = api(request)
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(ShopwareHttp.json) }
            expectSuccess = false
        }
        return ShopwareClient("https://shop.test", auth, http = http, onRefreshToken = onRefreshToken)
    }

    @Test
    fun passwordGrantStoresAndReportsRefreshToken() = runBlocking {
        val server = FakeTokenServer()
        var reported: String? = null
        val c = client(server, PlainAuth.Password("admin", "correct"), onRefreshToken = { reported = it })

        c.getJson("/test")

        assertEquals(listOf("password"), server.grantLog)
        assertEquals("refresh-0", reported)
        assertEquals("refresh-0", c.currentRefreshToken)
    }

    @Test
    fun refreshGrantRotatesAndPersistsBeforeUse() = runBlocking {
        val server = FakeTokenServer()
        server.validRefreshTokens += "seed"
        val rotations = mutableListOf<String>()
        val c = client(server, PlainAuth.RefreshToken("seed"), onRefreshToken = { rotations += it })

        c.getJson("/test")

        assertEquals(listOf("refresh_token"), server.grantLog)
        assertEquals(listOf("refresh-0"), rotations)
        assertEquals("refresh-0", c.currentRefreshToken)
        assertTrue("seed must be consumed", "seed" !in server.validRefreshTokens)
    }

    @Test
    fun expiredAccessTokenTriggersRefreshGrant() = runBlocking {
        val server = FakeTokenServer()
        server.expiresIn = 0 // with the 30s safety margin every call re-grants
        server.validRefreshTokens += "seed"
        val c = client(server, PlainAuth.RefreshToken("seed"))

        c.getJson("/one")
        c.getJson("/two")

        assertEquals(listOf("refresh_token", "refresh_token"), server.grantLog)
        assertEquals("refresh-1", c.currentRefreshToken)
    }

    @Test
    fun revokedRefreshTokenThrowsAuthExpired() = runBlocking {
        val server = FakeTokenServer() // "stale" was never valid → revoked
        val c = client(server, PlainAuth.RefreshToken("stale"))

        try {
            c.getJson("/test")
            fail("expected AuthExpired")
        } catch (e: ApiError.AuthExpired) {
            assertTrue(e.message!!.contains("revoked") || e.message!!.contains("invalid"))
        }
    }

    @Test
    fun blankRefreshTokenThrowsAuthExpiredWithoutServerCall() = runBlocking {
        val server = FakeTokenServer()
        val c = client(server, PlainAuth.RefreshToken(""))

        try {
            c.getJson("/test")
            fail("expected AuthExpired")
        } catch (e: ApiError.AuthExpired) {
            assertTrue(server.grantLog.isEmpty())
        }
    }

    @Test
    fun revokedRefreshFallsBackToPasswordDuringConnect() = runBlocking {
        val server = FakeTokenServer()
        server.expiresIn = 0
        val c = client(server, PlainAuth.Password("admin", "correct"))

        c.getJson("/one") // password grant → refresh-0
        server.validRefreshTokens.clear() // simulate server-side revocation
        c.getJson("/two") // refresh fails → falls back to password

        assertEquals(listOf("password", "refresh_token", "password"), server.grantLog)
    }

    @Test
    fun badPasswordSurfacesAsValidationNotAuthExpired() = runBlocking {
        val server = FakeTokenServer()
        val c = client(server, PlainAuth.Password("admin", "wrong"))

        try {
            c.getJson("/test")
            fail("expected Validation")
        } catch (e: ApiError.Validation) {
            assertTrue(e.message!!.contains("incorrect"))
        }
    }

    @Test
    fun rejected401RetriesOnceWithFreshGrant() = runBlocking {
        val server = FakeTokenServer()
        server.validRefreshTokens += "seed"
        var calls = 0
        val c = client(server, PlainAuth.RefreshToken("seed")) { _ ->
            calls++
            // first business call is rejected even though the token is locally unexpired
            if (calls == 1) HttpStatusCode.Unauthorized to "{}" else HttpStatusCode.OK to """{"ok":true}"""
        }

        val result = c.getJson("/test")

        assertEquals(2, calls)
        assertEquals(listOf("refresh_token", "refresh_token"), server.grantLog)
        assertEquals("true", (result as JsonObject)["ok"]?.jsonPrimitive?.content)
    }

    @Test
    fun repositoryUpsertGoesThroughSyncEnvelope() = runBlocking {
        val server = FakeTokenServer()
        var path: String? = null
        var method: String? = null
        var body: JsonObject? = null
        val c = client(server, PlainAuth.Password("admin", "correct")) { req ->
            path = req.url.encodedPath
            method = req.method.value
            body = ShopwareHttp.json.parseToJsonElement((req.body as TextContent).text).jsonObject
            HttpStatusCode.OK to "{}"
        }

        // The kebab REST name (ce-fcn) must map to the underscored DAL name (ce_fcn) in the envelope.
        ShopApi.withClient(c).repository("ce-fcn").upsert(
            buildJsonObject {
                put("id", "install-123")
                put("token", "fcm-tok")
                put("deviceName", "Pixel 9")
            },
        )

        // Goes through /_action/sync (POST), not the entity REST route.
        assertEquals("/api/_action/sync", path)
        assertEquals("POST", method)

        // Envelope: { "ce_fcn": { entity, action: upsert, payload: [ {id, token, deviceName} ] } }
        val op = body!!["ce_fcn"]!!.jsonObject
        assertEquals("ce_fcn", op["entity"]!!.jsonPrimitive.content)
        assertEquals("upsert", op["action"]!!.jsonPrimitive.content)
        val rows = op["payload"]!!.jsonArray
        assertEquals(1, rows.size)
        val row = rows[0].jsonObject
        assertEquals("install-123", row["id"]!!.jsonPrimitive.content)
        assertEquals("fcm-tok", row["token"]!!.jsonPrimitive.content)
        assertEquals("Pixel 9", row["deviceName"]!!.jsonPrimitive.content)
    }

    @Test
    fun concurrentRequestsShareOneGrant() = runBlocking {
        val server = FakeTokenServer()
        server.validRefreshTokens += "seed"
        val c = client(server, PlainAuth.RefreshToken("seed"))

        (1..8).map { async { c.getJson("/p$it") } }.awaitAll()

        // single-use rotation would make a second concurrent grant fail hard —
        // the mutex must collapse them into one
        assertEquals(listOf("refresh_token"), server.grantLog)
    }
}
