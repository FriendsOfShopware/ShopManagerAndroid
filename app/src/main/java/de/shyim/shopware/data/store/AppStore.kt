package de.shyim.shopware.data.store

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import de.shyim.shopware.data.model.AppData
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

object AppDataSerializer : Serializer<AppData> {
    override val defaultValue: AppData = AppData()

    override suspend fun readFrom(input: InputStream): AppData = try {
        val decoded = json.decodeFromString(AppData.serializer(), input.readBytes().decodeToString())
        // Demo mode was removed — purge legacy demo shops persisted by older versions
        val demoIds = decoded.shops.filter { it.demo }.map { it.id }.toSet()
        if (demoIds.isEmpty()) decoded
        else decoded.copy(
            shops = decoded.shops.filterNot { it.id in demoIds },
            snapshots = decoded.snapshots - demoIds,
            selectedShopId = decoded.selectedShopId?.takeIf { it !in demoIds }
                ?: decoded.shops.firstOrNull { !it.demo }?.id,
        )
    } catch (e: SerializationException) {
        throw CorruptionException("Cannot read app data", e)
    }

    override suspend fun writeTo(t: AppData, output: OutputStream) {
        output.write(json.encodeToString(AppData.serializer(), t).encodeToByteArray())
    }
}

val Context.appDataStore: DataStore<AppData> by dataStore(
    fileName = "app-data.json",
    serializer = AppDataSerializer,
)
