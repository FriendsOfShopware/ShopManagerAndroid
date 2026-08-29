package de.shyim.shopware.api

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder
import java.time.LocalDate

class ShopApi private constructor(private val client: ShopwareClient) {

    // Production entry point — builds a real client over the OkHttp engine.
    constructor(
        baseUrl: String,
        auth: PlainAuth,
        context: ApiContext = ApiContext(),
        onRefreshToken: (suspend (String) -> Unit)? = null,
    ) : this(ShopwareClient(baseUrl, auth, context, onRefreshToken = onRefreshToken))

    // The latest rotated refresh token — read by the connect wizard after verify
    val currentRefreshToken: String? get() = client.currentRefreshToken

    private val repositories = mutableMapOf<String, EntityRepository>()

    fun repository(entityName: String): EntityRepository =
        repositories.getOrPut(entityName) { EntityRepository(client, entityName) }

    val stateMachine = StateMachineApi(client)
    val dashboard = DashboardStatsApi(client)
    val documents = DocumentApi(client)
    val promotions = PromotionApi(client, ::repository)
    val media = MediaApi(client, ::repository)
    val instance = InstanceApi(client, ::repository)

    companion object {
        // Test seam: build a ShopApi over a pre-constructed client (e.g. one backed by ktor's
        // MockEngine), so query/parsing extension functions can be unit-tested without a server.
        fun withClient(client: ShopwareClient): ShopApi = ShopApi(client)
    }
}

class DocumentApi(private val client: ShopwareClient) {

    // type: invoice | delivery_note | credit_note | storno
    suspend fun create(orderId: String, type: String) {
        client.actionPost("/_action/order/document/$type/create", buildJsonArray {
            add(buildJsonObject { put("orderId", orderId) ; putJsonObject("config") {} })
        })
    }

    suspend fun download(documentId: String, deepLinkCode: String): ByteArray =
        client.getBytes("/_action/document/$documentId/$deepLinkCode")
}

class MediaApi(
    private val client: ShopwareClient,
    private val repository: (String) -> EntityRepository,
) {
    // Creates a media entity (optionally inside a folder), uploads the binary, returns the id
    suspend fun uploadImage(
        bytes: ByteArray,
        extension: String = "jpg",
        fileName: String? = null,
        mediaFolderId: String? = null,
    ): String {
        val mediaId = newId()
        repository("media").create(buildJsonObject {
            put("id", mediaId)
            mediaFolderId?.let { put("mediaFolderId", it) }
        })
        val name = URLEncoder.encode(fileName ?: "app-$mediaId", "UTF-8")
        client.postBytes(
            "/_action/media/$mediaId/upload?extension=$extension&fileName=$name",
            bytes,
            if (extension == "png") "image/png" else "image/jpeg",
        )
        return mediaId
    }

    // Verified payload (6.7.8): empty configuration object is enough; the server
    // creates one and marks useParentConfiguration
    suspend fun createFolder(name: String, parentId: String?): String {
        val id = newId()
        repository("media-folder").create(buildJsonObject {
            put("id", id)
            put("name", name)
            parentId?.let { put("parentId", it) }
            put("configuration", buildJsonObject {})
        })
        return id
    }

    // Uploads the image and attaches it to the product as cover
    suspend fun uploadProductCover(productId: String, bytes: ByteArray, extension: String = "jpg") {
        val mediaId = uploadImage(bytes, extension)
        val productMediaId = newId()
        repository("product").patch(productId, buildJsonObject {
            put("coverId", productMediaId)
            put("media", buildJsonArray {
                add(buildJsonObject {
                    put("id", productMediaId)
                    put("mediaId", mediaId)
                    put("position", 0)
                })
            })
        })
    }

    private fun newId() = java.util.UUID.randomUUID().toString().replace("-", "")
}

class PromotionApi(
    private val client: ShopwareClient,
    private val repository: (String) -> EntityRepository,
) {
    suspend fun setActive(promotionId: String, active: Boolean) {
        repository("promotion").patch(promotionId, buildJsonObject { put("active", active) })
    }

    // Only valid for promotions configured with useIndividualCodes — codes are
    // generated server-side from the promotion's individualCodePattern
    suspend fun addIndividualCodes(promotionId: String, amount: Int) {
        client.actionPost("/_action/promotion/codes/add-individual", buildJsonObject {
            put("promotionId", promotionId)
            put("amount", amount)
        })
    }
}

class StateMachineApi(private val client: ShopwareClient) {

    // entity: order | order_transaction | order_delivery
    suspend fun transitions(entity: String, id: String): List<StateTransition> =
        (client.getJson("/_action/state-machine/$entity/$id/state")["transitions"] as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(::SwEntity) }
            .mapNotNull { t ->
                val action = t.string("actionName") ?: return@mapNotNull null
                val url = t.string("url") ?: return@mapNotNull null
                val technical = t.string("toStateName") ?: action
                StateTransition(
                    actionName = action,
                    toStateName = technical,
                    displayName = t.string("name")
                        ?: technical.replace('_', ' ').replaceFirstChar { c -> c.uppercase() },
                    url = url.removePrefix("/api"),
                )
            }

    suspend fun transition(url: String) = client.actionPost(url)

    // Entity-specific endpoint (entity ∈ order | order_transaction | order_delivery) that —
    // unlike the generic state-machine URL — carries the confirmation-mail options: sends the
    // mail when sendMail is true (attaching documentIds), records internalComment in history.
    suspend fun transitionWithOptions(
        entity: String,
        entityId: String,
        actionName: String,
        sendMail: Boolean,
        documentIds: List<String>,
        internalComment: String?,
    ) = client.actionPost(
        "/_action/$entity/$entityId/state/$actionName",
        buildJsonObject {
            put("sendMail", sendMail)
            putJsonArray("documentIds") { documentIds.forEach { add(it) } }
            internalComment?.takeIf { it.isNotBlank() }?.let { put("internalComment", it) }
        },
    )
}

class DashboardStatsApi(private val client: ShopwareClient) {

    // date ("yyyy-MM-dd") -> (orderCount, revenue); timezone-aware, same source as sw-dashboard
    suspend fun orderAmount(since: LocalDate, timezone: String, paid: Boolean): Map<String, Pair<Int, Double>> {
        val tz = URLEncoder.encode(timezone, "UTF-8")
        val result = client.getJson("/_admin/dashboard/order-amount/$since?timezone=$tz&paid=$paid")
        return (result["statistic"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(::SwEntity) }
            .associateBy(
                { it.string("date") ?: "" },
                { Pair(it.int("count") ?: 0, it.double("amount") ?: 0.0) },
            )
    }
}

class InstanceApi(
    private val client: ShopwareClient,
    private val repository: (String) -> EntityRepository,
) {
    suspend fun version(): String =
        SwEntity(client.getJson("/_info/version")).string("version") ?: "unknown"

    suspend fun probe(entity: String): Boolean = swallowing {
        repository(entity).search(Criteria().setLimit(1).setTotalCountMode(TotalCountMode.None))
        true
    } ?: false

    suspend fun defaultCurrencyIso(): String? = swallowing {
        // The system default currency has the fixed id Defaults::CURRENCY on every
        // Shopware 6 install. Filtering by factor == 1.0 is NOT reliable: other
        // currencies may share that factor and the row order is arbitrary.
        repository("currency").search(
            Criteria()
                .setLimit(1)
                .addFilter(Criteria.equals("id", SYSTEM_CURRENCY_ID))
                .addIncludes("currency", listOf("isoCode"))
        ).data.firstOrNull()?.string("isoCode")
    }

    suspend fun defaultSalesChannelName(): String? = swallowing {
        repository("sales-channel").search(
            Criteria()
                .setLimit(1)
                .addIncludes("sales_channel", listOf("name", "translated"))
        ).data.firstOrNull()?.translated("name")
    }

    // Best-effort probes: failures become null, but cancellation must propagate
    private suspend fun <T> swallowing(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        // Shopware\Core\Defaults::CURRENCY — the undeletable system default currency
        const val SYSTEM_CURRENCY_ID = "b7d2554b0ce847cd82f3ac9bd1c0dfca"
    }

    suspend fun languages(): List<LanguageOption> =
        repository("language").search(
            Criteria()
                .setLimit(25)
                .addAssociation("locale")
                .addIncludes("language", listOf("id", "name", "locale", "translated"))
                .addIncludes("locale", listOf("code"))
        ).data.mapNotNull { language ->
            val id = language.id ?: return@mapNotNull null
            LanguageOption(
                id = id,
                name = language.translated("name") ?: "—",
                localeCode = language.entity("locale")?.string("code"),
            )
        }
}
