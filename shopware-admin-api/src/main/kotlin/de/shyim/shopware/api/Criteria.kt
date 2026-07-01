package de.shyim.shopware.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

enum class TotalCountMode(val value: Int) { None(0), Exact(1) }

// Mirrors the web admin's Criteria; toJson() emits only explicitly-set parts
class Criteria {
    private var page: Int? = null
    private var limit: Int? = null
    private var term: String? = null
    private var totalCountMode: TotalCountMode? = null
    private var ids: List<String> = emptyList()
    private val filters = mutableListOf<JsonObject>()
    private val sorts = mutableListOf<JsonObject>()
    private val aggregations = mutableListOf<JsonObject>()
    private val associations = LinkedHashMap<String, Criteria>()
    private val includes = LinkedHashMap<String, List<String>>()

    fun setPage(page: Int): Criteria = apply { this.page = page }
    fun setLimit(limit: Int): Criteria = apply { this.limit = limit }
    fun setTerm(term: String): Criteria = apply { this.term = term }
    fun setIds(ids: List<String>): Criteria = apply { this.ids = ids }
    fun setTotalCountMode(mode: TotalCountMode): Criteria = apply { totalCountMode = mode }

    fun addFilter(filter: JsonObject): Criteria = apply { filters.add(filter) }

    fun addSorting(field: String, order: String = "ASC"): Criteria =
        apply { sorts.add(sort(field, order)) }

    fun addAggregation(aggregation: JsonObject): Criteria = apply { aggregations.add(aggregation) }

    // Dot paths expand into nested association criteria, e.g. "deliveries.shippingMethod"
    fun addAssociation(path: String): Criteria = apply { getAssociation(path) }

    fun getAssociation(path: String): Criteria {
        var current = this
        path.split('.').forEach { part ->
            current = current.associations.getOrPut(part) { Criteria() }
        }
        return current
    }

    fun addIncludes(entityAlias: String, fields: List<String>): Criteria =
        apply { includes[entityAlias] = (includes[entityAlias].orEmpty() + fields) }

    fun toJson(): JsonObject = buildJsonObject {
        page?.let { put("page", it) }
        limit?.let { put("limit", it) }
        term?.let { put("term", it) }
        if (ids.isNotEmpty()) putJsonArray("ids") { ids.forEach { add(it) } }
        totalCountMode?.let { put("total-count-mode", it.value) }
        if (filters.isNotEmpty()) put("filter", JsonArray(filters))
        if (sorts.isNotEmpty()) put("sort", JsonArray(sorts))
        if (associations.isNotEmpty()) putJsonObject("associations") {
            associations.forEach { (name, criteria) -> put(name, criteria.toJson()) }
        }
        if (includes.isNotEmpty()) putJsonObject("includes") {
            includes.forEach { (alias, fields) -> putJsonArray(alias) { fields.forEach { add(it) } } }
        }
        if (aggregations.isNotEmpty()) put("aggregations", JsonArray(aggregations))
    }

    companion object {
        private fun primitive(value: Any?): JsonPrimitive = when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            else -> throw IllegalArgumentException("Unsupported filter value type: ${value::class}")
        }

        fun equals(field: String, value: Any?): JsonObject = buildJsonObject {
            put("type", "equals"); put("field", field); put("value", primitive(value))
        }

        fun equalsAny(field: String, values: List<Any?>): JsonObject = buildJsonObject {
            put("type", "equalsAny"); put("field", field)
            put("value", JsonArray(values.map { primitive(it) }))
        }

        fun contains(field: String, value: String): JsonObject = textFilter("contains", field, value)
        fun prefix(field: String, value: String): JsonObject = textFilter("prefix", field, value)
        fun suffix(field: String, value: String): JsonObject = textFilter("suffix", field, value)

        private fun textFilter(type: String, field: String, value: String): JsonObject =
            buildJsonObject { put("type", type); put("field", field); put("value", value) }

        fun range(
            field: String,
            gte: Any? = null,
            lte: Any? = null,
            gt: Any? = null,
            lt: Any? = null,
        ): JsonObject = buildJsonObject {
            put("type", "range"); put("field", field)
            putJsonObject("parameters") {
                gte?.let { put("gte", primitive(it)) }
                lte?.let { put("lte", primitive(it)) }
                gt?.let { put("gt", primitive(it)) }
                lt?.let { put("lt", primitive(it)) }
            }
        }

        fun not(operator: String, vararg filters: JsonObject): JsonObject =
            compound("not", operator, filters)

        fun multi(operator: String, vararg filters: JsonObject): JsonObject =
            compound("multi", operator, filters)

        private fun compound(type: String, operator: String, filters: Array<out JsonObject>): JsonObject =
            buildJsonObject {
                put("type", type); put("operator", operator)
                put("queries", JsonArray(filters.toList()))
            }

        fun sort(field: String, order: String = "ASC"): JsonObject = buildJsonObject {
            put("field", field); put("order", order)
        }

        fun sum(name: String, field: String): JsonObject = metric("sum", name, field)
        fun avg(name: String, field: String): JsonObject = metric("avg", name, field)
        fun min(name: String, field: String): JsonObject = metric("min", name, field)
        fun max(name: String, field: String): JsonObject = metric("max", name, field)
        fun count(name: String, field: String): JsonObject = metric("count", name, field)
        fun stats(name: String, field: String): JsonObject = metric("stats", name, field)

        private fun metric(type: String, name: String, field: String): JsonObject =
            buildJsonObject { put("name", name); put("type", type); put("field", field) }

        fun terms(
            name: String,
            field: String,
            limit: Int? = null,
            sort: JsonObject? = null,
            aggregation: JsonObject? = null,
        ): JsonObject = buildJsonObject {
            put("name", name); put("type", "terms"); put("field", field)
            limit?.let { put("limit", it) }
            sort?.let { put("sort", it) }
            aggregation?.let { put("aggregation", it) }
        }

        fun histogram(
            name: String,
            field: String,
            interval: String,
            aggregation: JsonObject? = null,
        ): JsonObject = buildJsonObject {
            put("name", name); put("type", "histogram"); put("field", field)
            put("interval", interval)
            aggregation?.let { put("aggregation", it) }
        }
    }
}
