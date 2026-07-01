package de.shyim.shopware.data.source

import de.shyim.shopware.api.ShopApi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

suspend fun ShopApi.setReviewStatus(reviewId: String, approved: Boolean) {
    repository("product-review").patch(reviewId, buildJsonObject { put("status", approved) })
}
