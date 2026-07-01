package de.shyim.shopware.data.model

// Live media browsing — fetched on demand, not persisted

data class MediaFolderItem(
    val id: String,
    val name: String,
    val childCount: Int,
)

data class MediaItem(
    val id: String,
    val fileName: String,
    val extension: String,
    val mimeType: String?,
    val fileSize: Long,
    val uploadedMs: Long,
    val url: String?, // already rebased onto the shop's base URL
    val isImage: Boolean,
)
