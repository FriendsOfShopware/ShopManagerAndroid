package de.shyim.shopware.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.shyim.shopware.R
import de.shyim.shopware.ShopwareApp
import de.shyim.shopware.data.currencySymbol
import de.shyim.shopware.data.model.ConnectedShop
import de.shyim.shopware.data.model.ProductQuickInfo
import kotlinx.coroutines.launch

sealed class ProductSheetUi {
    data object Loading : ProductSheetUi()
    data class Error(val message: String) : ProductSheetUi()
    data class Ready(val info: ProductQuickInfo) : ProductSheetUi()
}

class ProductSheetViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as ShopwareApp).repository

    var ui by mutableStateOf<ProductSheetUi>(ProductSheetUi.Loading)
        private set
    var saving by mutableStateOf(false)
        private set
    var saveError by mutableStateOf<String?>(null)

    private fun appString(res: Int) = getApplication<Application>().getString(res)

    fun load(shop: ConnectedShop, productId: String) {
        viewModelScope.launch {
            ui = ProductSheetUi.Loading
            ui = runCatching { repo.productQuickInfo(shop, productId) }.fold(
                onSuccess = {
                    it?.let { i -> ProductSheetUi.Ready(i) }
                        ?: ProductSheetUi.Error(appString(R.string.product_sheet_not_found))
                },
                onFailure = { ProductSheetUi.Error(it.message ?: appString(R.string.product_sheet_load_failed)) }
            )
        }
    }

    fun save(shop: ConnectedShop, stock: Int, active: Boolean, newGross: Double?, onSaved: () -> Unit) {
        val info = (ui as? ProductSheetUi.Ready)?.info ?: return
        if (saving) return
        saving = true
        saveError = null
        viewModelScope.launch {
            runCatching { repo.saveProductQuickEdit(shop, info, stock, active, newGross) }.fold(
                onSuccess = {
                    repo.refresh(shop.id)
                    onSaved()
                },
                onFailure = { saveError = it.message ?: appString(R.string.product_sheet_save_failed) }
            )
            saving = false
        }
    }

    var uploadingPhoto by mutableStateOf(false)
        private set
    var photoMessage by mutableStateOf<String?>(null)
        private set
    var photoError by mutableStateOf(false)
        private set

    fun uploadPhoto(shop: ConnectedShop, productId: String, file: java.io.File) {
        if (uploadingPhoto) return
        uploadingPhoto = true
        photoMessage = null
        viewModelScope.launch {
            runCatching {
                repo.uploadProductPhoto(shop, productId, file.readBytes())
                file.delete()
            }.fold(
                onSuccess = {
                    photoError = false
                    photoMessage = appString(R.string.product_sheet_photo_success)
                },
                onFailure = {
                    photoError = true
                    photoMessage = it.message ?: appString(R.string.product_sheet_upload_failed)
                }
            )
            uploadingPhoto = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductActionSheet(
    shop: ConnectedShop,
    productId: String,
    onDismiss: () -> Unit,
    vm: ProductSheetViewModel = viewModel(),
) {
    val cs = MaterialTheme.colorScheme

    LaunchedEffect(productId) { vm.load(shop, productId) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 36.dp)
        ) {
            when (val ui = vm.ui) {
                ProductSheetUi.Loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
                is ProductSheetUi.Error -> {
                    Text(ui.message, color = cs.error, fontSize = 14.sp)
                }
                is ProductSheetUi.Ready -> {
                    val info = ui.info
                    var stock by androidx.compose.runtime.remember(info.id) {
                        mutableStateOf(info.stock)
                    }
                    var active by androidx.compose.runtime.remember(info.id) {
                        mutableStateOf(info.active)
                    }
                    var priceText by androidx.compose.runtime.remember(info.id) {
                        mutableStateOf(info.grossPrice?.let { "%.2f".format(it) } ?: "")
                    }

                    Text(info.name, color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        info.productNumber,
                        color = cs.onSurfaceVariant, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp)
                    ) {
                        Text(
                            stringResource(R.string.product_sheet_active_in_shop),
                            color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = active, onCheckedChange = { active = it })
                    }

                    Box(modifier = Modifier.padding(top = 14.dp)) {
                        StockStepper(stock, onChange = { stock = it })
                    }

                    if (info.priceEditable) {
                        OutlinedTextField(
                            value = priceText,
                            onValueChange = { priceText = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                            label = { Text(stringResource(R.string.product_sheet_gross_price)) },
                            prefix = { Text(currencySymbol(shop.currency)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp)
                        )
                    } else {
                        Text(
                            stringResource(R.string.product_sheet_price_not_editable),
                            color = cs.onSurfaceVariant, fontSize = 12.sp,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }

                    // Camera capture → media upload → product cover
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val captureFile = androidx.compose.runtime.remember(info.id) {
                        java.io.File(context.cacheDir, "captures")
                            .apply { mkdirs() }
                            .let { java.io.File(it, "capture-${info.id}.jpg") }
                    }
                    val captureLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
                    ) { ok ->
                        if (ok) vm.uploadPhoto(shop, info.id, captureFile)
                    }
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", captureFile
                            )
                            captureLauncher.launch(uri)
                        },
                        enabled = !vm.uploadingPhoto,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                    ) {
                        Icon(Icons.Outlined.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            if (vm.uploadingPhoto) stringResource(R.string.product_sheet_uploading)
                            else stringResource(R.string.product_sheet_new_photo),
                            modifier = Modifier.padding(start = 8.dp), fontSize = 13.5.sp
                        )
                    }
                    vm.photoMessage?.let { msg ->
                        Text(
                            msg,
                            color = if (vm.photoError) cs.error else cs.primary,
                            fontSize = 12.5.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    vm.saveError?.let { err ->
                        Text(
                            err, color = cs.error, fontSize = 12.5.sp,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }

                    Button(
                        onClick = {
                            val newGross = priceText.replace(',', '.').toDoubleOrNull()
                                ?.takeIf { info.priceEditable && it != info.grossPrice }
                            vm.save(shop, stock, active, newGross, onSaved = onDismiss)
                        },
                        enabled = !vm.saving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp)
                            .height(50.dp)
                    ) {
                        if (vm.saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp), strokeWidth = 2.dp,
                                color = cs.onPrimary
                            )
                        } else Text(stringResource(R.string.common_save))
                    }
                }
            }
        }
    }
}
