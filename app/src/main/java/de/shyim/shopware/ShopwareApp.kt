package de.shyim.shopware

import android.app.Application
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessaging
import de.shyim.shopware.data.AppRepository
import de.shyim.shopware.data.PushRegisterResult
import de.shyim.shopware.data.PushStatus
import de.shyim.shopware.data.model.ConnectedShop
import de.shyim.shopware.data.store.appDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class ShopwareApp : Application() {
    val repository: AppRepository by lazy { AppRepository(this) }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Order alerts come from FCM push now; cancel the legacy 15-min polling worker that
        // older installs may still have scheduled.
        runCatching { WorkManager.getInstance(this).cancelUniqueWork(LEGACY_SYNC_WORK) }
        // Push the current FCM token to every connected shop's ce_fcn (token rotation is
        // handled separately by FcmService.onNewToken). No-op when no shops/no Play services.
        registerPushToAllShops()
    }

    fun registerPushToAllShops() {
        appScope.launch {
            if (appDataStore.data.first().shops.isEmpty()) return@launch
            val token = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()
            if (!token.isNullOrBlank()) {
                runCatching { repository.registerPushToken(token, android.os.Build.MODEL ?: "Android") }
            }
        }
    }

    // Register one shop and report whether its push app is missing (for the connect dialog).
    // No Play services / no token → Ok (can't prove the app is missing, so don't prompt).
    suspend fun registerPushForShopChecked(shop: ConnectedShop): PushRegisterResult {
        val token = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()
            ?: return PushRegisterResult.Ok
        return repository.registerPushForShop(shop, token, android.os.Build.MODEL ?: "Android")
    }

    // Register this device with one shop, then return the resulting live status (for shop settings).
    // No Play-services token → can't register; report it as Unavailable so the UI stays honest.
    suspend fun enablePushForShop(shop: ConnectedShop): PushStatus {
        val token = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()
            ?: return PushStatus.Unavailable
        repository.registerPushForShop(shop, token, android.os.Build.MODEL ?: "Android")
        return repository.pushStatusForShop(shop)
    }

    private companion object {
        // unique name the removed SyncWorker used to enqueue under
        const val LEGACY_SYNC_WORK = "shop-sync"
    }
}
