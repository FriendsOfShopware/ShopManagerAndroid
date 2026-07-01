package de.shyim.shopware.data.source

import de.shyim.shopware.api.ShopApi
import de.shyim.shopware.api.runCatchingCancellable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Register (upsert) this device's FCM token into the shop's ce_fcn entity. The external
// Shopware app server reads these rows (15-min TTL backstop) and fans order pushes out.
// `installId` is a stable per-installation id used as the row id. Goes through the /_action/sync
// `upsert` action, not POST: POST is insert-only, so every repeat call (token refresh, app
// restart) on the same id would otherwise fail with FRAMEWORK__WRITE_TYPE_INTEND_ERROR.
// Requires the FroshMobilePush app to be installed (the ce_fcn entity + ce_fcn ACL).
suspend fun ShopApi.registerFcmToken(installId: String, token: String, deviceName: String) {
    val payload = buildJsonObject {
        put("id", installId)
        put("token", token)
        put("deviceName", deviceName)
    }
    repository("ce-fcn").upsert(payload)
}

// Look up this device's ce_fcn row. Returns Present (with the stored deviceName, which may be
// blank) when the row exists, Absent when it doesn't. Throws ApiError.NotFound when the entity
// itself is missing (FroshMobilePush not installed) — callers distinguish that from Absent.
sealed interface FcmRegistration {
    data class Present(val deviceName: String?) : FcmRegistration
    data object Absent : FcmRegistration
}

suspend fun ShopApi.fetchFcmRegistration(installId: String): FcmRegistration =
    repository("ce-fcn").get(installId)
        ?.let { FcmRegistration.Present(it.string("deviceName")) }
        ?: FcmRegistration.Absent

// Remove this device's token from the shop (e.g. on disconnect). Ignores a 404.
suspend fun ShopApi.unregisterFcmToken(installId: String) {
    runCatchingCancellable { repository("ce-fcn").delete(installId) }
}
