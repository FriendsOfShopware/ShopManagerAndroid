package de.shyim.shopware.data.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDataMigrationTest {

    private fun read(json: String) = runBlocking(Dispatchers.Unconfined) {
        AppDataSerializer.readFrom(json.byteInputStream())
    }

    @Test
    fun legacyDemoShopsArePurgedAtLoad() {
        val data = read(
            """
            {
              "shops": [
                {"id": "oldtown", "name": "Old Town", "baseUrl": "", "demo": true},
                {"id": "live1", "name": "Storefront", "baseUrl": "https://shop.example"}
              ],
              "snapshots": {
                "oldtown": {"todayRevenue": 1.0, "yesterdayRevenue": 1.0, "ordersToday": 0,
                            "openOrders": 0, "unpaidOrders": 0, "lowStockCount": 0,
                            "weekRevenue": [], "weekLabels": []}
              },
              "selectedShopId": "oldtown"
            }
            """.trimIndent()
        )
        assertEquals(listOf("live1"), data.shops.map { it.id })
        assertTrue(data.snapshots.isEmpty())
        assertEquals("live1", data.selectedShopId)
    }

    @Test
    fun legacyAuthVariantsStillDecode() {
        val data = read(
            """
            {
              "shops": [
                {"id": "s1", "name": "A", "baseUrl": "https://a",
                 "auth": {"type": "integration", "clientId": "SWIAXX", "encSecret": "abc"}},
                {"id": "s2", "name": "B", "baseUrl": "https://b",
                 "auth": {"type": "password", "username": "admin", "encPassword": "abc"}},
                {"id": "s3", "name": "C", "baseUrl": "https://c",
                 "auth": {"type": "admin", "username": "admin", "encRefreshToken": "abc"}}
              ]
            }
            """.trimIndent()
        )
        assertEquals(listOf("s1", "s2", "s3"), data.shops.map { it.id })
        assertTrue(data.shops[0].auth is de.shyim.shopware.data.model.ShopAuth.Integration)
        assertTrue(data.shops[1].auth is de.shyim.shopware.data.model.ShopAuth.Password)
        assertTrue(data.shops[2].auth is de.shyim.shopware.data.model.ShopAuth.Admin)
    }

    @Test
    fun dataWithoutDemoShopsPassesThroughUntouched() {
        val data = read(
            """{"shops": [{"id": "live1", "name": "S", "baseUrl": "https://x"}], "selectedShopId": "live1"}"""
        )
        assertEquals(listOf("live1"), data.shops.map { it.id })
        assertEquals("live1", data.selectedShopId)
    }
}
