package de.shyim.shopware.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Null-safe view over a plain-JSON Admin API entity; JsonNull counts as absent everywhere
class SwEntity(val json: JsonObject) {

    val id: String? get() = string("id")

    fun string(field: String): String? = primitive(field)?.content
    fun int(field: String): Int? = primitive(field)?.intOrNull
    fun long(field: String): Long? = primitive(field)?.longOrNull
    fun double(field: String): Double? = primitive(field)?.doubleOrNull
    fun boolean(field: String): Boolean? = primitive(field)?.booleanOrNull

    // Accepts "2026-06-11T08:21:33.000+00:00", "2026-06-11 08:21:33.000" (UTC),
    // and date-only "2026-06-11" prefixes (midnight UTC)
    fun instant(field: String): Instant? = string(field)?.let { value ->
        runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(value, SPACE_FORMAT).toInstant(ZoneOffset.UTC)
            }.getOrNull()
            ?: runCatching {
                LocalDate.parse(value.take(10)).atStartOfDay().toInstant(ZoneOffset.UTC)
            }.getOrNull()
    }

    // "translated" is [] instead of {} when an entity has no translatable fields
    fun translated(field: String): String? {
        val resolved = (json["translated"] as? JsonObject)?.get(field) as? JsonPrimitive
        return resolved?.takeUnless { it is JsonNull }?.content ?: string(field)
    }

    fun entity(field: String): SwEntity? = (json[field] as? JsonObject)?.let(::SwEntity)

    fun entities(field: String): List<SwEntity> =
        (json[field] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::SwEntity) }
            ?: emptyList()

    private fun primitive(field: String): JsonPrimitive? =
        (json[field] as? JsonPrimitive)?.takeUnless { it is JsonNull }

    private companion object {
        val SPACE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]")
    }
}

class Bucket(val entity: SwEntity) {
    val key: String = entity.string("key") ?: ""
    val count: Int = entity.int("count") ?: 0

    // Without a name, takes the first child object carrying a "sum" key (nested aggregation)
    fun sum(nestedName: String? = null): Double {
        val holder = when (nestedName) {
            null -> entity.json.values.firstOrNull { it is JsonObject && "sum" in it }
            else -> entity.json[nestedName]
        } as? JsonObject
        return (holder?.get("sum") as? JsonPrimitive)?.doubleOrNull ?: 0.0
    }

    // Buckets of a nested bucketing aggregation (terms→histogram, terms→terms, …)
    fun buckets(name: String): List<Bucket> =
        ((entity.json[name] as? JsonObject)?.get("buckets") as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.let { b -> Bucket(SwEntity(b)) } }
            ?: emptyList()
}

class SearchResult(val total: Int, val data: List<SwEntity>, val aggregations: JsonObject) {

    fun sum(name: String): Double =
        ((aggregations[name] as? JsonObject)?.get("sum") as? JsonPrimitive)?.doubleOrNull ?: 0.0

    fun count(name: String): Int =
        ((aggregations[name] as? JsonObject)?.get("count") as? JsonPrimitive)?.intOrNull ?: 0

    fun buckets(name: String): List<Bucket> =
        ((aggregations[name] as? JsonObject)?.get("buckets") as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.let { bucket -> Bucket(SwEntity(bucket)) } }
            ?: emptyList()

    companion object {
        fun from(envelope: JsonObject): SearchResult = SearchResult(
            total = ((envelope["total"] as? JsonPrimitive)?.intOrNull) ?: 0,
            data = (envelope["data"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.let(::SwEntity) }
                ?: emptyList(),
            aggregations = envelope["aggregations"] as? JsonObject ?: JsonObject(emptyMap()),
        )
    }
}
