package de.shyim.shopware.ui.screens

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import de.shyim.shopware.R
import de.shyim.shopware.data.model.ConnectedShop
import de.shyim.shopware.data.model.MediaFolderItem
import de.shyim.shopware.data.model.MediaItem
import de.shyim.shopware.data.source.mediaListCriteria
import de.shyim.shopware.data.source.parseMedia
import de.shyim.shopware.api.ShopApi
import de.shyim.shopware.ui.listing.ListingState
import de.shyim.shopware.ui.listing.ListingViewModel
import de.shyim.shopware.ui.relativeAgoText
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.Locale

class MediaViewModel(app: Application) : ListingViewModel<MediaItem>(app) {

    private var shop: ConnectedShop? = null

    var path by mutableStateOf<List<MediaFolderItem>>(emptyList())
        private set
    var folders by mutableStateOf<List<MediaFolderItem>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var actionError by mutableStateOf<String?>(null)

    val currentFolderId: String? get() = path.lastOrNull()?.id

    override fun createListing(shop: ConnectedShop, api: ShopApi): ListingState<MediaItem> {
        this.shop = shop
        path = emptyList()
        loadFolders()
        return ListingState(
            scope = viewModelScope,
            pageSize = 50,
            source = { api.repository("media").search(it) },
            baseCriteria = { mediaListCriteria(currentFolderId) },
            mapper = { parseMedia(it, shop.baseUrl) },
            errorFallback = getApplication<Application>().getString(R.string.media_load_failed),
        )
    }

    private fun loadFolders() {
        val s = shop ?: return
        viewModelScope.launch {
            folders = runCatching { repo.mediaFolders(s, currentFolderId) }.getOrDefault(emptyList())
        }
    }

    fun open(folder: MediaFolderItem) {
        path = path + folder
        refresh()
    }

    fun up(): Boolean {
        if (path.isEmpty()) return false
        path = path.dropLast(1)
        refresh()
        return true
    }

    private fun refresh() {
        listing?.reload()
        loadFolders()
    }

    fun createFolder(name: String) {
        val s = shop ?: return
        runAction(R.string.media_folder_create_failed) {
            repo.createMediaFolder(s, currentFolderId, name.trim())
            loadFolders()
        }
    }

    fun upload(bytes: ByteArray, extension: String) {
        val s = shop ?: return
        runAction(R.string.media_upload_failed) {
            repo.uploadMedia(s, currentFolderId, bytes, extension)
            listing?.reload()
        }
    }

    fun delete(item: MediaItem, onDone: () -> Unit) {
        val s = shop ?: return
        runAction(R.string.media_delete_failed) {
            repo.deleteMedia(s, item.id)
            listing?.removeItem { it.id == item.id }
            onDone()
        }
    }

    private fun runAction(fallbackRes: Int, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        actionError = null
        viewModelScope.launch {
            runCatching { block() }.onFailure {
                actionError = it.message ?: getApplication<Application>().getString(fallbackRes)
            }
            busy = false
        }
    }
}

fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaScreen(
    shop: ConnectedShop,
    onBack: () -> Unit,
    vm: MediaViewModel = viewModel(),
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var newFolderOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<MediaItem?>(null) }

    LaunchedEffect(shop.id, shop.languageId) { vm.init(shop) }
    BackHandler { if (!vm.up()) onBack() }

    val listing = vm.listing

    // Photo picker (no permission required) and camera capture (FileProvider pattern)
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri)
            val ext = if (mime == "image/png") "png" else "jpg"
            context.contentResolver.openInputStream(uri)?.use { input ->
                vm.upload(input.readBytes(), ext)
            }
        }
    }
    val captureFile = remember {
        java.io.File(context.cacheDir, "captures").apply { mkdirs() }
            .let { java.io.File(it, "media-capture.jpg") }
    }
    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        if (ok) {
            vm.upload(captureFile.readBytes(), "jpg")
            captureFile.delete()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (!vm.up()) onBack() }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = cs.onSurface)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    vm.path.lastOrNull()?.name ?: stringResource(R.string.media_title),
                    color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (vm.path.size > 1) {
                    Text(
                        vm.path.dropLast(1).joinToString(" / ") { it.name },
                        color = cs.onSurfaceVariant, fontSize = 11.5.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (vm.busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(
                onClick = { newFolderOpen = true },
                label = { Text(stringResource(R.string.media_new_folder)) },
                leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            AssistChip(
                onClick = {
                    pickLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                label = { Text(stringResource(R.string.media_upload)) },
                leadingIcon = { Icon(Icons.Outlined.Upload, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            AssistChip(
                onClick = {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", captureFile
                    )
                    captureLauncher.launch(uri)
                },
                label = { Text(stringResource(R.string.media_capture)) },
                leadingIcon = { Icon(Icons.Outlined.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }

        vm.actionError?.let {
            Text(
                it, color = cs.error, fontSize = 12.5.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }

        listing?.let { l ->
            var term by remember(vm.currentFolderId) { mutableStateOf("") }
            OutlinedTextField(
                value = term,
                onValueChange = {
                    term = it
                    l.setTerm(it)
                    l.search()
                },
                placeholder = { Text(stringResource(R.string.media_search)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        val gridState = rememberLazyGridState()
        LaunchedEffect(gridState, listing) {
            if (listing == null) return@LaunchedEffect
            snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                .distinctUntilChanged()
                .filter { last ->
                    !listing.loading && listing.items.isNotEmpty() &&
                        last >= gridState.layoutInfo.totalItemsCount - 8
                }
                .collect { listing.loadMore() }
        }

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(104.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(vm.folders.size, key = { "folder-" + vm.folders[it].id }, span = { GridItemSpan(maxLineSpan) }) { i ->
                val folder = vm.folders[i]
                Surface(
                    onClick = { vm.open(folder) },
                    shape = RoundedCornerShape(14.dp),
                    color = cs.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Outlined.Folder, contentDescription = null, tint = cs.primary)
                        Text(
                            folder.name,
                            color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = cs.onSurfaceVariant)
                    }
                }
            }

            val items = listing?.items.orEmpty()
            items(items.size, key = { items[it].id }) { i ->
                val item = items[i]
                Surface(
                    onClick = { selected = item },
                    shape = RoundedCornerShape(14.dp),
                    color = cs.surfaceContainerLow,
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (item.isImage && item.url != null) {
                                AsyncImage(
                                    model = item.url,
                                    contentDescription = item.fileName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    Icons.Outlined.Description, contentDescription = null,
                                    tint = cs.onSurfaceVariant, modifier = Modifier.size(34.dp)
                                )
                            }
                        }
                        Text(
                            item.fileName,
                            color = cs.onSurfaceVariant, fontSize = 10.5.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            if (listing != null && !listing.loading && vm.folders.isEmpty() && items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        listing.error ?: stringResource(R.string.media_empty),
                        color = if (listing.error != null) cs.error else cs.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (newFolderOpen) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolderOpen = false },
            title = { Text(stringResource(R.string.media_new_folder)) },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.media_folder_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        vm.createFolder(name)
                        newFolderOpen = false
                    }
                ) { Text(stringResource(R.string.media_new_folder)) }
            },
            dismissButton = {
                TextButton(onClick = { newFolderOpen = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    selected?.let { item ->
        var confirmDelete by remember(item.id) { mutableStateOf(false) }
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, bottom = 36.dp)
            ) {
                if (item.isImage && item.url != null) {
                    AsyncImage(
                        model = item.url,
                        contentDescription = item.fileName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(RoundedCornerShape(18.dp))
                    )
                }
                Text(
                    "${item.fileName}.${item.extension}",
                    color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 14.dp)
                )
                DetailLine(stringResource(R.string.media_file_type), item.mimeType ?: item.extension)
                DetailLine(stringResource(R.string.media_file_size), formatFileSize(item.fileSize))
                DetailLine(stringResource(R.string.media_uploaded_at), relativeAgoText(item.uploadedMs))
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = cs.error),
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.media_delete), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text(stringResource(R.string.media_delete_confirm_title, item.fileName)) },
                text = { Text(stringResource(R.string.media_delete_confirm_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        vm.delete(item) { selected = null }
                    }) { Text(stringResource(R.string.common_remove), color = cs.error) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) }
                }
            )
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Row(modifier = Modifier.padding(top = 8.dp)) {
        Text(label, color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
