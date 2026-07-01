package de.shyim.shopware.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class EntityRepository(private val client: ShopwareClient, entityName: String) {
    // The API routes entities kebab-cased (product_review -> /api/product-review)
    val entityName: String = entityName.replace('_', '-')

    suspend fun search(criteria: Criteria): SearchResult =
        SearchResult.from(client.search(entityName, criteria.toJson()))

    // Does not mutate the passed criteria; ids are added to the emitted payload only
    suspend fun get(id: String, criteria: Criteria = Criteria()): SwEntity? {
        val payload = JsonObject(criteria.toJson() + ("ids" to JsonArray(listOf(JsonPrimitive(id)))))
        return SearchResult.from(client.search(entityName, payload)).data.firstOrNull()
    }

    suspend fun create(payload: JsonObject) = client.post("/$entityName", payload)

    // Insert-or-update by primary key via /_action/sync. Use when the caller supplies a stable id
    // and POST (insert-only) would fail with FRAMEWORK__WRITE_TYPE_INTEND_ERROR on repeat calls.
    suspend fun upsert(payload: JsonObject) =
        client.sync(entityName.replace('-', '_'), "upsert", JsonArray(listOf(payload)))

    suspend fun patch(id: String, payload: JsonObject) = client.patch("/$entityName/$id", payload)

    suspend fun delete(id: String) = client.delete("/$entityName/$id")
}
