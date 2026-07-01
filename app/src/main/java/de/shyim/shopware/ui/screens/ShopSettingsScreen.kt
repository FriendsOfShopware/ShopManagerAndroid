package de.shyim.shopware.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.shyim.shopware.R
import de.shyim.shopware.data.PushStatus
import de.shyim.shopware.data.currencySymbol
import de.shyim.shopware.data.model.ConnectedShop
import de.shyim.shopware.api.LanguageOption
import de.shyim.shopware.data.model.ProductFieldConfig
import de.shyim.shopware.data.model.ShopAuth
import de.shyim.shopware.data.model.TintPalette
import de.shyim.shopware.ui.AppViewModel
import de.shyim.shopware.ui.components.ShopIconBox
import kotlinx.coroutines.launch

@Composable
private fun SettingsLabel(text: String, topPadding: Int = 20) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = topPadding.dp, bottom = 10.dp)
    )
}

@Composable
fun ShopSettingsScreen(
    vm: AppViewModel,
    shopId: String,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val data by vm.data.collectAsState()
    val shop = data?.shops?.firstOrNull { it.id == shopId }

    // Shop gone (removed) — leave the screen
    if (shop == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var name by remember(shopId) { mutableStateOf(shop.name) }
    var tintIndex by remember(shopId) { mutableStateOf(shop.tintIndex) }
    var targetText by remember(shopId) {
        mutableStateOf(shop.dailyTarget?.let { "%.0f".format(it) } ?: "")
    }
    var thresholdText by remember(shopId) { mutableStateOf("${shop.lowStockThreshold}") }
    var confirmRemoval by remember { mutableStateOf(false) }
    var languagePickerOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = cs.onSurface)
            }
            Text(
                stringResource(R.string.shop_settings_title),
                color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }

        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally)
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(top = 8.dp, bottom = 18.dp)
            ) {
                ShopIconBox(TintPalette[tintIndex.mod(TintPalette.size)])
                Column {
                    Text(shop.name, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        shop.baseUrl.removePrefix("https://").removePrefix("http://"),
                        color = cs.onSurfaceVariant, fontSize = 12.sp
                    )
                }
            }

            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text(stringResource(R.string.wizard_shop_name)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            SettingsLabel(stringResource(R.string.wizard_color_tag))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TintPalette.forEachIndexed { i, tint ->
                    val selected = tintIndex == i
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(tint.bg(dark))
                            .border(
                                if (selected) 3.dp else 1.dp,
                                if (selected) cs.primary else cs.outlineVariant,
                                CircleShape
                            )
                            .selectable(selected = selected, onClick = { tintIndex = i }),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Icon(
                                Icons.Outlined.Check, contentDescription = null,
                                tint = tint.fg(dark), modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            SettingsLabel(stringResource(R.string.shop_settings_daily_target))
            OutlinedTextField(
                value = targetText,
                onValueChange = { targetText = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                prefix = { Text(currencySymbol(shop.currency)) },
                supportingText = { Text(stringResource(R.string.shop_settings_daily_target_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            SettingsLabel(stringResource(R.string.shop_settings_low_stock))
            OutlinedTextField(
                value = thresholdText,
                onValueChange = { thresholdText = it.filter(Char::isDigit).take(4) },
                supportingText = { Text(stringResource(R.string.shop_settings_low_stock_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            SettingsLabel(stringResource(R.string.wizard_content_language))
            Surface(
                onClick = { languagePickerOpen = true },
                shape = RoundedCornerShape(14.dp),
                color = cs.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Outlined.Translate, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Text(
                        shop.localeCode ?: stringResource(R.string.shop_settings_language_default),
                        color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        stringResource(R.string.shop_settings_change),
                        color = cs.primary, fontSize = 13.sp
                    )
                }
            }

            SettingsLabel(stringResource(R.string.product_fields_section_title))
            ProductFieldsCard(vm, shop)

            SettingsLabel(stringResource(R.string.push_section_title))
            PushNotificationCard(vm, shop)

            SettingsLabel(stringResource(R.string.shop_settings_signin_title))
            SignInCard(vm, shop)

            Button(
                onClick = {
                    vm.updateShopSettings(
                        shopId = shopId,
                        name = name.trim(),
                        tintIndex = tintIndex,
                        dailyTarget = targetText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 },
                        lowStockThreshold = (thresholdText.toIntOrNull() ?: shop.lowStockThreshold)
                            .coerceIn(1, 9999),
                    )
                    onBack()
                },
                enabled = name.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
                    .height(50.dp)
            ) { Text(stringResource(R.string.common_save)) }

            OutlinedButton(
                onClick = { confirmRemoval = true },
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = cs.error),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(50.dp)
            ) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.shop_settings_remove), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }

    if (languagePickerOpen) {
        ShopLanguageDialog(
            vm = vm,
            shop = shop,
            onDismiss = { languagePickerOpen = false },
        )
    }

    if (confirmRemoval) {
        AlertDialog(
            onDismissRequest = { confirmRemoval = false },
            title = { Text(stringResource(R.string.manage_remove_shop_title, shop.name)) },
            text = { Text(stringResource(R.string.manage_remove_shop_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoval = false
                    vm.removeShop(shop.id)
                    // the null-shop guard above pops the screen once the store updates
                }) { Text(stringResource(R.string.common_remove), color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoval = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

// Per-shop product-detail layout: which optional fields show on the detail page and which appear
// in the edit sheet. Each toggle persists immediately. Core fields (name/active/stock) are always
// present and not listed here.
@Composable
private fun ProductFieldsCard(vm: AppViewModel, shop: ConnectedShop) {
    val cs = MaterialTheme.colorScheme
    val cfg = shop.productFields

    fun save(next: ProductFieldConfig) = vm.updateProductFields(shop.id, next)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cs.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.product_fields_section_desc),
                color = cs.onSurfaceVariant, fontSize = 12.5.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            // Fields with both a visibility and an editability toggle.
            FieldRow(
                label = stringResource(R.string.product_detail_ean),
                show = cfg.showEan, onShow = { save(cfg.copy(showEan = it)) },
                canEdit = cfg.editEan, onEdit = { save(cfg.copy(editEan = it)) },
            )
            FieldRow(
                label = stringResource(R.string.product_detail_manufacturer_number),
                show = cfg.showManufacturerNumber, onShow = { save(cfg.copy(showManufacturerNumber = it)) },
                canEdit = cfg.editManufacturerNumber, onEdit = { save(cfg.copy(editManufacturerNumber = it)) },
            )
            FieldRow(
                label = stringResource(R.string.product_detail_pricing),
                show = cfg.showPrice, onShow = { save(cfg.copy(showPrice = it)) },
                canEdit = cfg.editPrice, onEdit = { save(cfg.copy(editPrice = it)) },
            )

            // Display-only fields — visibility toggle only.
            FieldRow(
                label = stringResource(R.string.product_detail_description),
                show = cfg.showDescription, onShow = { save(cfg.copy(showDescription = it)) },
            )
            FieldRow(
                label = stringResource(R.string.product_manufacturer),
                show = cfg.showManufacturer, onShow = { save(cfg.copy(showManufacturer = it)) },
            )
            FieldRow(
                label = stringResource(R.string.product_detail_categories),
                show = cfg.showCategories, onShow = { save(cfg.copy(showCategories = it)) },
            )
            FieldRow(
                label = stringResource(R.string.product_detail_sales_channels),
                show = cfg.showSalesChannels, onShow = { save(cfg.copy(showSalesChannels = it)) },
            )
        }
    }
}

// One field's configuration row: a label, a "show" switch, and (optionally) an "edit" switch that
// is disabled when the field is hidden — you can't edit what isn't shown.
@Composable
private fun FieldRow(
    label: String,
    show: Boolean,
    onShow: (Boolean) -> Unit,
    canEdit: Boolean? = null,
    onEdit: ((Boolean) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Text(
            label,
            color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        if (canEdit != null && onEdit != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = 16.dp)) {
                Text(stringResource(R.string.product_fields_show), color = cs.onSurfaceVariant, fontSize = 10.sp)
                Switch(checked = show, onCheckedChange = onShow)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.product_fields_edit), color = cs.onSurfaceVariant, fontSize = 10.sp)
                Switch(checked = canEdit && show, enabled = show, onCheckedChange = onEdit)
            }
        } else {
            Switch(checked = show, onCheckedChange = onShow)
        }
    }
}

// Per-shop order-push registration (ce_fcn). Registration is shop-scoped, so it lives here rather
// than in the global Manage-shops screen. Shows the live server status and lets the user
// register/unregister this device, plus the Android-13 notification-permission prompt.
@Composable
private fun PushNotificationCard(vm: AppViewModel, shop: ConnectedShop) {
    val cs = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var status by remember(shop.id) { mutableStateOf<PushStatus?>(null) }
    var busy by remember(shop.id) { mutableStateOf(false) }

    // Re-read on (re)entry and when the shop changes — keeps the card honest after admin-side edits.
    suspend fun reload() { status = vm.pushStatus(shop) }
    LaunchedEffect(shop.id) { reload() }

    // Android 13+ runtime notification permission; recomposition re-reads the grant after return.
    var permissionAsked by remember { mutableStateOf(false) }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { permissionAsked = true }
    val needsPermission = android.os.Build.VERSION.SDK_INT >= 33 &&
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
        !permissionAsked

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cs.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.push_section_desc),
                color = cs.onSurfaceVariant, fontSize = 12.5.sp, lineHeight = 17.sp
            )

            val s = status
            val (statusText, statusColor) = when (s) {
                null -> stringResource(R.string.push_status_checking) to cs.onSurfaceVariant
                is PushStatus.Registered ->
                    (s.deviceName?.takeIf { it.isNotBlank() }
                        ?.let { stringResource(R.string.push_status_registered_named, it) }
                        ?: stringResource(R.string.push_status_registered)) to cs.primary
                PushStatus.NotRegistered ->
                    stringResource(R.string.push_status_not_registered) to cs.onSurface
                PushStatus.AppNotInstalled ->
                    stringResource(R.string.push_status_app_missing) to cs.error
                PushStatus.Unavailable ->
                    stringResource(R.string.push_status_unavailable) to cs.error
            }
            Text(
                statusText,
                color = statusColor, fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(top = 12.dp)
            )

            // The OS-level permission gate only matters once push can actually be delivered.
            if (needsPermission && s != PushStatus.AppNotInstalled) {
                Text(
                    stringResource(R.string.push_permission_needed),
                    color = cs.onSurfaceVariant, fontSize = 12.5.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
                TextButton(
                    onClick = { permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) { Text(stringResource(R.string.push_permission_grant), fontSize = 13.sp) }
            }

            // Primary action depends on the current state.
            when (s) {
                is PushStatus.Registered -> OutlinedButton(
                    onClick = { scope.launch { busy = true; status = vm.disablePush(shop); busy = false } },
                    enabled = !busy,
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = cs.error),
                    modifier = Modifier.padding(top = 10.dp)
                ) { Text(stringResource(R.string.push_action_unregister)) }

                PushStatus.NotRegistered -> Button(
                    onClick = { scope.launch { busy = true; status = vm.enablePush(shop); busy = false } },
                    enabled = !busy,
                    modifier = Modifier.padding(top = 10.dp)
                ) { Text(stringResource(R.string.push_action_register)) }

                PushStatus.Unavailable -> OutlinedButton(
                    onClick = { scope.launch { busy = true; reload(); busy = false } },
                    enabled = !busy,
                    modifier = Modifier.padding(top = 10.dp)
                ) { Text(stringResource(R.string.push_action_retry)) }

                else -> Unit // checking (null) or app-missing — no action button
            }
        }
    }
}

// Renews the OAuth session (rotating refresh token). Required for shops connected with
// the removed Integration mode and whenever the stored refresh token was revoked.
@Composable
private fun SignInCard(vm: AppViewModel, shop: ConnectedShop) {
    val cs = MaterialTheme.colorScheme
    val signInRequired = shop.auth !is ShopAuth.Admin && shop.auth !is ShopAuth.Password
    var username by remember(shop.id) {
        mutableStateOf(
            when (val a = shop.auth) {
                is ShopAuth.Admin -> a.username
                is ShopAuth.Password -> a.username
                else -> ""
            }
        )
    }
    var password by remember(shop.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val successText = stringResource(R.string.shop_settings_signin_success)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cs.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                if (signInRequired) stringResource(R.string.shop_settings_signin_required)
                else stringResource(R.string.shop_settings_signin_subtitle),
                color = if (signInRequired) cs.error else cs.onSurfaceVariant,
                fontSize = 12.5.sp, lineHeight = 17.sp
            )
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text(stringResource(R.string.wizard_username)) }, singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text(stringResource(R.string.wizard_password)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            )
            message?.let {
                Text(
                    it,
                    color = if (failed) cs.error else cs.primary,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            OutlinedButton(
                onClick = {
                    busy = true
                    message = null
                    vm.reauthenticate(shop, username, password) { error ->
                        busy = false
                        failed = error != null
                        message = error ?: successText
                        if (error == null) password = ""
                    }
                },
                enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.padding(top = 10.dp)
            ) {
                Text(
                    if (busy) stringResource(R.string.shop_settings_signin_busy)
                    else stringResource(R.string.shop_settings_signin_button)
                )
            }
        }
    }
}

// Shared with Manage shops historically; now only entered from shop settings
@Composable
fun ShopLanguageDialog(
    vm: AppViewModel,
    shop: ConnectedShop,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var options by remember(shop.id) { mutableStateOf<List<LanguageOption>?>(null) }

    LaunchedEffect(shop.id) {
        options = runCatching { vm.languagesFor(shop) }.getOrDefault(emptyList())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wizard_content_language)) },
        text = {
            val opts = options
            if (opts == null) {
                Text(stringResource(R.string.manage_loading_languages, shop.name))
            } else if (opts.isEmpty()) {
                Text(stringResource(R.string.manage_languages_failed))
            } else {
                Column {
                    opts.forEach { lang ->
                        Surface(
                            onClick = {
                                vm.setShopLanguage(shop.id, lang)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (shop.languageId == lang.id) cs.secondaryContainer
                            else cs.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    lang.name,
                                    color = cs.onSurface, fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                lang.localeCode?.let {
                                    Text(it, color = cs.onSurfaceVariant, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
