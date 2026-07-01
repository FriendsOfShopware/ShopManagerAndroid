package de.shyim.shopware.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class CriteriaTest {

    private fun assertGolden(expected: String, criteria: Criteria) {
        assertEquals(Json.parseToJsonElement(expected), criteria.toJson())
    }

    @Test
    fun emptyCriteriaEmitsEmptyObject() {
        assertGolden("{}", Criteria())
    }

    @Test
    fun dashboardStyleQuery() {
        val criteria = Criteria()
            .setLimit(1)
            .addFilter(Criteria.equals("order.stateMachineState.technicalName", "open"))
            .addFilter(Criteria.range("orderDateTime", gte = "2026-06-01 00:00:00"))
            .addFilter(Criteria.not("and", Criteria.equals("customerComment", null)))
            .addSorting("orderDateTime", "DESC")
            .addAggregation(
                Criteria.histogram(
                    "order_count_day", "orderDateTime", "day",
                    aggregation = Criteria.sum("totalAmount", "amountTotal"),
                )
            )

        assertGolden(
            """
            {
              "limit": 1,
              "filter": [
                {"type": "equals", "field": "order.stateMachineState.technicalName", "value": "open"},
                {"type": "range", "field": "orderDateTime", "parameters": {"gte": "2026-06-01 00:00:00"}},
                {"type": "not", "operator": "and", "queries": [
                  {"type": "equals", "field": "customerComment", "value": null}
                ]}
              ],
              "sort": [{"field": "orderDateTime", "order": "DESC"}],
              "aggregations": [
                {
                  "name": "order_count_day",
                  "type": "histogram",
                  "field": "orderDateTime",
                  "interval": "day",
                  "aggregation": {"name": "totalAmount", "type": "sum", "field": "amountTotal"}
                }
              ]
            }
            """,
            criteria,
        )
    }

    @Test
    fun termsAggregationWithNestedSumAndCountSort() {
        val criteria = Criteria()
            .setLimit(0)
            .addAggregation(
                Criteria.terms(
                    "products", "lineItems.productId",
                    limit = 5,
                    sort = Criteria.sort("_count", "DESC"),
                    aggregation = Criteria.sum("revenue", "lineItems.totalPrice"),
                )
            )

        assertGolden(
            """
            {
              "limit": 0,
              "aggregations": [
                {
                  "name": "products",
                  "type": "terms",
                  "field": "lineItems.productId",
                  "limit": 5,
                  "sort": {"field": "_count", "order": "DESC"},
                  "aggregation": {"name": "revenue", "type": "sum", "field": "lineItems.totalPrice"}
                }
              ]
            }
            """,
            criteria,
        )
    }

    @Test
    fun dotPathAssociationExpandsNestedCriteria() {
        val criteria = Criteria()
            .addAssociation("deliveries.shippingMethod")
            .addAssociation("deliveries.shippingOrderAddress")
            .addAssociation("transactions")

        assertGolden(
            """
            {
              "associations": {
                "deliveries": {
                  "associations": {
                    "shippingMethod": {},
                    "shippingOrderAddress": {}
                  }
                },
                "transactions": {}
              }
            }
            """,
            criteria,
        )
    }

    @Test
    fun getAssociationAllowsConfiguringNestedCriteria() {
        val criteria = Criteria()
        criteria.getAssociation("lineItems").setLimit(10).addSorting("position")

        assertGolden(
            """
            {
              "associations": {
                "lineItems": {
                  "limit": 10,
                  "sort": [{"field": "position", "order": "ASC"}]
                }
              }
            }
            """,
            criteria,
        )
    }

    @Test
    fun includesAndTotalCountMode() {
        val criteria = Criteria()
            .setPage(2)
            .setLimit(25)
            .setTotalCountMode(TotalCountMode.Exact)
            .addIncludes("order", listOf("id", "orderNumber", "amountTotal"))
            .addIncludes("state_machine_state", listOf("name", "technicalName"))

        assertGolden(
            """
            {
              "page": 2,
              "limit": 25,
              "total-count-mode": 1,
              "includes": {
                "order": ["id", "orderNumber", "amountTotal"],
                "state_machine_state": ["name", "technicalName"]
              }
            }
            """,
            criteria,
        )
    }

    @Test
    fun equalsAnyAndNullEquals() {
        val criteria = Criteria()
            .setTerm("hoodie")
            .setIds(listOf("0190", "0191"))
            .setTotalCountMode(TotalCountMode.None)
            .addFilter(Criteria.equalsAny("stateId", listOf("aaa", "bbb")))
            .addFilter(Criteria.equals("parentId", null))
            .addFilter(Criteria.equals("active", true))
            .addFilter(Criteria.equals("stock", 0))

        assertGolden(
            """
            {
              "term": "hoodie",
              "ids": ["0190", "0191"],
              "total-count-mode": 0,
              "filter": [
                {"type": "equalsAny", "field": "stateId", "value": ["aaa", "bbb"]},
                {"type": "equals", "field": "parentId", "value": null},
                {"type": "equals", "field": "active", "value": true},
                {"type": "equals", "field": "stock", "value": 0}
              ]
            }
            """,
            criteria,
        )
    }

    @Test
    fun textAndMultiFilters() {
        val criteria = Criteria()
            .addFilter(
                Criteria.multi(
                    "or",
                    Criteria.contains("name", "shirt"),
                    Criteria.prefix("productNumber", "SW-"),
                    Criteria.suffix("name", "XL"),
                )
            )
            .addFilter(Criteria.range("stock", gt = 0, lte = 100))

        assertGolden(
            """
            {
              "filter": [
                {"type": "multi", "operator": "or", "queries": [
                  {"type": "contains", "field": "name", "value": "shirt"},
                  {"type": "prefix", "field": "productNumber", "value": "SW-"},
                  {"type": "suffix", "field": "name", "value": "XL"}
                ]},
                {"type": "range", "field": "stock", "parameters": {"lte": 100, "gt": 0}}
              ]
            }
            """,
            criteria,
        )
    }
}
