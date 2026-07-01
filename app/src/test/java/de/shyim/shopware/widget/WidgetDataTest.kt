package de.shyim.shopware.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WidgetDataTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(filesDir: File, rel: String, content: String) {
        File(filesDir, rel).apply { parentFile?.mkdirs() }.writeText(content)
    }

    @Test
    fun mapsSelectedShopRevenueDeltaAndTarget() {
        val files = tmp.newFolder("files")
        write(
            files, "datastore/app-data.json",
            """{"shops":[
                 {"id":"s1","name":"Other","baseUrl":"https://a","currency":"EUR"},
                 {"id":"s2","name":"Thread Studio","baseUrl":"https://b","currency":"EUR","localeCode":"en-GB","dailyTarget":1000.0,"tintIndex":2}
               ],"selectedShopId":"s2"}""",
        )
        write(
            files, "snapshots/s2.json",
            """{"todayRevenue":750.0,"yesterdayRevenue":500.0,"ordersToday":3,"openOrders":2,
                "unpaidOrders":0,"lowStockCount":0,"weekRevenue":[0.0,750.0],"lastSyncEpochMs":123}""",
        )

        val state = WidgetData.readSelectedShopSnapshot(files)!!
        assertEquals("Thread Studio", state.shopName)
        assertTrue(state.revenueText.contains("750"))
        assertEquals("+50%", state.deltaLabel) // 750 vs 500
        assertTrue(state.deltaUp)
        assertEquals(0.75f, state.targetPct!!, 1e-4f) // 750/1000
        assertEquals(2, state.tintIndex)
        assertEquals(123L, state.lastSyncMs)
    }

    @Test
    fun fallsBackToFirstShopWhenNoneSelected() {
        val files = tmp.newFolder("files")
        write(files, "datastore/app-data.json", """{"shops":[{"id":"s1","name":"Only","baseUrl":"https://a","currency":"EUR"}]}""")
        write(files, "snapshots/s1.json", """{"todayRevenue":10.0,"yesterdayRevenue":0.0,"ordersToday":1,"openOrders":0,"unpaidOrders":0,"lowStockCount":0,"weekRevenue":[10.0],"lastSyncEpochMs":1}""")

        val state = WidgetData.readSelectedShopSnapshot(files)!!
        assertEquals("Only", state.shopName)
        assertNull(state.targetPct) // no dailyTarget
    }

    @Test
    fun nullWhenNoShopsOrNoSnapshot() {
        val files = tmp.newFolder("files")
        assertNull(WidgetData.readSelectedShopSnapshot(files)) // nothing written

        write(files, "datastore/app-data.json", """{"shops":[{"id":"s1","name":"A","baseUrl":"https://a"}],"selectedShopId":"s1"}""")
        assertNull(WidgetData.readSelectedShopSnapshot(files)) // shop but no snapshot file
    }

    @Test
    fun weeklyComputesTotalBarsAndMomentum() {
        val files = tmp.newFolder("files")
        write(files, "datastore/app-data.json", """{"shops":[{"id":"s1","name":"A","baseUrl":"https://a","currency":"EUR"}],"selectedShopId":"s1"}""")
        // 7 days; first half (100+50+0=150) vs second half (200+300+400=900) → up
        write(
            files, "snapshots/s1.json",
            """{"todayRevenue":400.0,"yesterdayRevenue":300.0,"ordersToday":1,"openOrders":0,
                "unpaidOrders":0,"lowStockCount":0,"weekRevenue":[100.0,50.0,0.0,150.0,200.0,300.0,400.0],
                "todayIndex":6,"lastSyncEpochMs":99}""",
        )
        val w = WidgetData.readWeeklySnapshot(files)!!
        assertTrue(w.weekTotalText.contains("1,200")) // sum = 1200
        assertEquals(7, w.bars.size)
        assertEquals(1f, w.bars[6], 1e-4f) // 400 is the max → full bar
        assertEquals(0f, w.bars[2], 1e-4f) // the 0-revenue day
        assertEquals(6, w.todayIndex)
        assertTrue(w.deltaUp) // second half >> first half
    }

    @Test
    fun weeklyNullWhenNoWeekData() {
        val files = tmp.newFolder("files")
        write(files, "datastore/app-data.json", """{"shops":[{"id":"s1","name":"A","baseUrl":"https://a","currency":"EUR"}],"selectedShopId":"s1"}""")
        write(files, "snapshots/s1.json", """{"todayRevenue":1.0,"yesterdayRevenue":0.0,"ordersToday":0,"openOrders":0,"unpaidOrders":0,"lowStockCount":0,"weekRevenue":[],"lastSyncEpochMs":1}""")
        assertNull(WidgetData.readWeeklySnapshot(files))
    }

    @Test
    fun negativeDeltaIsDown() {
        val files = tmp.newFolder("files")
        write(files, "datastore/app-data.json", """{"shops":[{"id":"s1","name":"A","baseUrl":"https://a","currency":"EUR"}],"selectedShopId":"s1"}""")
        write(files, "snapshots/s1.json", """{"todayRevenue":250.0,"yesterdayRevenue":500.0,"ordersToday":1,"openOrders":0,"unpaidOrders":0,"lowStockCount":0,"weekRevenue":[250.0],"lastSyncEpochMs":1}""")

        val state = WidgetData.readSelectedShopSnapshot(files)!!
        assertFalse(state.deltaUp)
        assertEquals("-50%", state.deltaLabel)
    }
}
