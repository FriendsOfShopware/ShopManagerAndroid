package de.shyim.shopware.data.source

import de.shyim.shopware.api.Criteria
import de.shyim.shopware.api.SwEntity
import de.shyim.shopware.data.model.MediaFolderItem
import de.shyim.shopware.data.model.MediaItem

// Folders are few — loaded un-paged per level
fun mediaFolderCriteria(parentId: String?): Criteria = Criteria()
    .setLimit(100)
    .addFilter(Criteria.equals("parentId", parentId))
    .addSorting("name")
    .addIncludes("media_folder", listOf("id", "name", "childCount"))

fun mediaListCriteria(folderId: String?): Criteria = Criteria()
    .addFilter(Criteria.equals("mediaFolderId", folderId))
    .addSorting("uploadedAt", "DESC")
    .addIncludes(
        "media",
        listOf("id", "fileName", "fileExtension", "mimeType", "fileSize", "uploadedAt", "url"),
    )

fun parseMediaFolder(f: SwEntity): MediaFolderItem = MediaFolderItem(
    id = f.id ?: "",
    name = f.string("name") ?: "—",
    childCount = f.int("childCount") ?: 0,
)

fun parseMedia(m: SwEntity, shopBaseUrl: String): MediaItem {
    val mime = m.string("mimeType")
    return MediaItem(
        id = m.id ?: "",
        fileName = m.string("fileName") ?: "—",
        extension = m.string("fileExtension") ?: "",
        mimeType = mime,
        fileSize = m.long("fileSize") ?: 0L,
        uploadedMs = m.instant("uploadedAt")?.toEpochMilli() ?: 0L,
        url = m.string("url")?.let { rebaseMediaUrl(it, shopBaseUrl) },
        isImage = mime?.startsWith("image/") == true,
    )
}

// media.url carries the shop's configured public host (APP_URL), which can differ from
// the URL the app connects through (e.g. localhost:8000 vs 10.0.2.2:8000 on an emulator,
// or internal vs external hostnames). Swap scheme+authority for the connected base URL.
fun rebaseMediaUrl(url: String, shopBaseUrl: String): String {
    val mediaAuthorityEnd = url.indexOf('/', url.indexOf("://") + 3)
    if (!url.startsWith("http") || mediaAuthorityEnd < 0) return url
    return shopBaseUrl.trimEnd('/') + url.substring(mediaAuthorityEnd)
}
