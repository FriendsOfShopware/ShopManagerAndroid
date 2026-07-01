package de.shyim.shopware.data.store

import de.shyim.shopware.data.model.ShopSnapshot
import kotlinx.serialization.json.Json
import java.io.File

// Per-shop snapshot cache, one JSON file per shop ({shopId}.json). Snapshots are
// re-fetchable overview data: keeping them out of app-data.json means the frequent
// 15-minute sync writes never rewrite credentials/settings, and a corrupt cache file
// costs one shop's cache instead of every connection.
class SnapshotStore(private val dir: File) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // shopId is always an app-generated UUID hex string, safe as a file name
    private fun fileFor(shopId: String) = File(dir, "$shopId.json")

    fun loadAll(): Map<String, ShopSnapshot> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyMap()
        return files.mapNotNull { file ->
            runCatching {
                file.name.removeSuffix(".json") to
                    json.decodeFromString(ShopSnapshot.serializer(), file.readText())
            }.getOrElse {
                file.delete() // corrupt cache — drop it, the next refresh rebuilds it
                null
            }
        }.toMap()
    }

    fun write(shopId: String, snapshot: ShopSnapshot) {
        dir.mkdirs()
        val tmp = File(dir, "${shopId}.json.tmp")
        tmp.writeText(json.encodeToString(ShopSnapshot.serializer(), snapshot))
        if (!tmp.renameTo(fileFor(shopId))) {
            // rename across the same directory should not fail; fall back to direct write
            fileFor(shopId).writeText(json.encodeToString(ShopSnapshot.serializer(), snapshot))
            tmp.delete()
        }
    }

    fun delete(shopId: String) {
        fileFor(shopId).delete()
    }
}
