package de.shyim.shopware.data.store

import de.shyim.shopware.data.model.ShopSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SnapshotStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun snapshot(revenue: Double) = ShopSnapshot(
        todayRevenue = revenue,
        yesterdayRevenue = 0.0,
        ordersToday = 1,
        openOrders = 2,
        unpaidOrders = 0,
        lowStockCount = 0,
        weekRevenue = listOf(0.0, revenue),
        lastSyncEpochMs = 123L,
    )

    @Test
    fun writeAndLoadRoundTrip() {
        val store = SnapshotStore(File(tmp.root, "snapshots"))
        store.write("shop-a", snapshot(100.0))
        store.write("shop-b", snapshot(200.0))

        val loaded = store.loadAll()
        assertEquals(setOf("shop-a", "shop-b"), loaded.keys)
        assertEquals(100.0, loaded["shop-a"]!!.todayRevenue, 1e-9)
        assertEquals(123L, loaded["shop-b"]!!.lastSyncEpochMs)
    }

    @Test
    fun overwriteReplacesSnapshot() {
        val store = SnapshotStore(File(tmp.root, "snapshots"))
        store.write("shop-a", snapshot(100.0))
        store.write("shop-a", snapshot(150.0))
        assertEquals(150.0, store.loadAll()["shop-a"]!!.todayRevenue, 1e-9)
    }

    @Test
    fun corruptFileIsDroppedNotFatal() {
        val dir = File(tmp.root, "snapshots").apply { mkdirs() }
        File(dir, "broken.json").writeText("{not json")
        val store = SnapshotStore(dir)
        store.write("shop-a", snapshot(100.0))

        val loaded = store.loadAll()
        assertEquals(setOf("shop-a"), loaded.keys)
        assertFalse("corrupt file should be deleted", File(dir, "broken.json").exists())
    }

    @Test
    fun deleteRemovesFile() {
        val dir = File(tmp.root, "snapshots")
        val store = SnapshotStore(dir)
        store.write("shop-a", snapshot(100.0))
        store.delete("shop-a")
        assertTrue(store.loadAll().isEmpty())
        assertFalse(File(dir, "shop-a.json").exists())
    }

    @Test
    fun missingDirectoryLoadsEmpty() {
        assertTrue(SnapshotStore(File(tmp.root, "nope")).loadAll().isEmpty())
    }
}
