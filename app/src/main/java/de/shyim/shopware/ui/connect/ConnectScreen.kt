package de.shyim.shopware.ui.connect

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.shyim.shopware.R
import de.shyim.shopware.data.currencySymbol
import de.shyim.shopware.data.model.TintPalette

@Composable
fun ConnectScreen(
    onClose: () -> Unit,
    onFinished: (String) -> Unit,
    vm: ConnectViewModel = viewModel(),
) {
    val cs = MaterialTheme.colorScheme

    BackHandler { if (!vm.goBack()) onClose() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (!vm.goBack()) onClose() }) {
                Icon(
                    if (vm.step == 0) Icons.Outlined.Close else Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.common_back), tint = cs.onSurface
                )
            }
            Text(
                stringResource(R.string.wizard_title),
                color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                stringResource(R.string.wizard_step_indicator, vm.step + 1),
                color = cs.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Medium
            )
        }
        LinearProgressIndicator(
            progress = { (vm.step + 1) / 4f },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        )

        AnimatedContent(
            targetState = vm.step,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally { it / 3 } + fadeIn()).togetherWith(slideOutHorizontally { -it / 3 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 3 } + fadeIn()).togetherWith(slideOutHorizontally { it / 3 } + fadeOut())
                }
            },
            label = "wizard-step"
        ) { step ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .imePadding()
                        .navigationBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    when (step) {
                        0 -> StepUrl(vm)
                        1 -> StepCredentials(vm)
                        2 -> StepVerify(vm)
                        3 -> StepPersonalize(vm, onFinished)
                    }
                }
            }
        }
    }

    // Shop connected but its FCM entity is missing → the push app isn't installed.
    if (vm.pushAppMissing) {
        AlertDialog(
            onDismissRequest = { vm.proceedAfterConnect() },
            title = { Text(stringResource(R.string.push_app_missing_title)) },
            text = { Text(stringResource(R.string.push_app_missing_text)) },
            confirmButton = {
                TextButton(onClick = { vm.proceedAfterConnect() }) {
                    Text(stringResource(R.string.common_continue))
                }
            },
        )
    }
}

@Composable
private fun StepHeadline(title: String, body: String) {
    val cs = MaterialTheme.colorScheme
    Text(
        title,
        color = cs.onSurface, fontSize = 28.sp, fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp, lineHeight = 34.sp
    )
    Text(
        body,
        color = cs.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
    )
}

@Composable
private fun WizardButton(text: String, enabled: Boolean, busy: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
            .height(52.dp)
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ── Step 1 · URL ─────────────────────────────────────────────

@Composable
private fun StepUrl(vm: ConnectViewModel) {
    StepHeadline(
        stringResource(R.string.wizard_url_title),
        stringResource(R.string.wizard_url_body)
    )
    OutlinedTextField(
        value = vm.url,
        onValueChange = { vm.url = it; vm.urlError = null },
        label = { Text(stringResource(R.string.wizard_shop_url)) },
        placeholder = { Text("https://my-boutique.example") },
        singleLine = true,
        isError = vm.urlError != null,
        supportingText = vm.urlError?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        leadingIcon = { Icon(Icons.Outlined.Storefront, contentDescription = null) },
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        stringResource(R.string.wizard_url_hint),
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
        modifier = Modifier.padding(top = 8.dp)
    )
    WizardButton(stringResource(R.string.common_continue), enabled = vm.url.isNotBlank(), busy = vm.busy) { vm.submitUrl() }
}

// ── Step 2 · Credentials ─────────────────────────────────────

@Composable
private fun StepCredentials(vm: ConnectViewModel) {
    val cs = MaterialTheme.colorScheme
    StepHeadline(
        stringResource(R.string.wizard_credentials_title),
        stringResource(R.string.wizard_credentials_body)
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = cs.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, cs.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Person, contentDescription = null, tint = cs.primary)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                ) {
                    Text(
                        stringResource(R.string.wizard_admin_login),
                        color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.wizard_admin_login_subtitle),
                        color = cs.onSurfaceVariant, fontSize = 12.sp
                    )
                }
            }
            OutlinedTextField(
                value = vm.username, onValueChange = { vm.username = it },
                label = { Text(stringResource(R.string.wizard_username)) }, singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            )
            OutlinedTextField(
                value = vm.password, onValueChange = { vm.password = it },
                label = { Text(stringResource(R.string.wizard_password)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            )
            Text(
                stringResource(R.string.wizard_login_help),
                color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }

    WizardButton(stringResource(R.string.wizard_verify_connection), enabled = vm.credsValid) { vm.startVerify() }
}

// ── Step 3 · Verify ──────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StepVerify(vm: ConnectViewModel) {
    val cs = MaterialTheme.colorScheme
    val v = vm.verify

    when {
        v.running -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 64.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LoadingIndicator(modifier = Modifier.size(72.dp))
                Text(
                    stringResource(R.string.wizard_connecting),
                    color = cs.onSurfaceVariant, fontSize = 14.sp,
                    modifier = Modifier.padding(top = 20.dp)
                )
            }
        }
        v.error != null -> {
            StepHeadline(stringResource(R.string.wizard_verify_failed_title), "")
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = cs.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = cs.onErrorContainer)
                    Text(
                        v.error,
                        color = cs.onErrorContainer, fontSize = 13.5.sp, lineHeight = 19.sp,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
            WizardButton(stringResource(R.string.wizard_back_to_credentials), enabled = true) { vm.retryFromCredentials() }
        }
        else -> {
            StepHeadline(
                stringResource(R.string.wizard_check_title),
                stringResource(R.string.wizard_check_body)
            )
            ChecklistRow(
                ok = true,
                text = stringResource(
                    R.string.wizard_connected_to,
                    vm.normalizedUrl.removePrefix("https://").removePrefix("http://")
                )
            )
            ChecklistRow(ok = true, text = stringResource(R.string.wizard_shopware_version, v.version.orEmpty()))
            v.scopes.forEach { scope ->
                ChecklistRow(
                    ok = scope.ok,
                    text = if (scope.ok) stringResource(R.string.wizard_scope_readable, scope.label)
                    else stringResource(R.string.wizard_scope_not_readable, scope.label, scope.entity)
                )
            }
            if (v.scopes.any { !it.ok }) {
                Text(
                    stringResource(R.string.wizard_continue_partial),
                    color = cs.onSurfaceVariant, fontSize = 12.5.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            WizardButton(stringResource(R.string.common_continue), enabled = vm.canLeaveVerify) { vm.toPersonalize() }
        }
    }
}

@Composable
private fun ChecklistRow(ok: Boolean, text: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp)
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (ok) cs.primaryContainer else cs.errorContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (ok) Icons.Outlined.Check else Icons.Outlined.Close,
                contentDescription = null,
                tint = if (ok) cs.onPrimaryContainer else cs.onErrorContainer,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text,
            color = cs.onSurface, fontSize = 13.5.sp, lineHeight = 18.sp,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

// ── Step 4 · Personalize ─────────────────────────────────────

@Composable
private fun StepPersonalize(vm: ConnectViewModel, onFinished: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()

    StepHeadline(
        stringResource(R.string.wizard_personalize_title),
        stringResource(R.string.wizard_personalize_body)
    )
    OutlinedTextField(
        value = vm.shopName, onValueChange = { vm.shopName = it },
        label = { Text(stringResource(R.string.wizard_shop_name)) }, singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Text(
        stringResource(R.string.wizard_color_tag),
        color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 20.dp, bottom = 10.dp)
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TintPalette.forEachIndexed { i, tint ->
            val selected = vm.tintIndex == i
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
                    .selectable(selected = selected, onClick = { vm.tintIndex = i }),
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

    if (vm.languages.size > 1) {
        Text(
            stringResource(R.string.wizard_content_language),
            color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 20.dp, bottom = 10.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            vm.languages.forEach { lang ->
                FilterChip(
                    selected = vm.selectedLanguage?.id == lang.id,
                    onClick = { vm.selectedLanguage = lang },
                    label = { Text(lang.name) }
                )
            }
        }
        Text(
            stringResource(R.string.wizard_language_hint),
            color = cs.onSurfaceVariant, fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 20.dp)
    ) {
        Icon(Icons.Outlined.CloudDone, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(
            stringResource(R.string.wizard_currency_detected, vm.currency),
            color = cs.onSurfaceVariant, fontSize = 12.5.sp,
            modifier = Modifier.padding(start = 8.dp)
        )
    }

    OutlinedTextField(
        value = vm.dailyTarget, onValueChange = { vm.dailyTarget = it.filter { c -> c.isDigit() || c == '.' } },
        label = { Text(stringResource(R.string.wizard_daily_target)) },
        prefix = { Text(currencySymbol(vm.currency)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
    )

    WizardButton(stringResource(R.string.wizard_open_dashboard), enabled = vm.shopName.isNotBlank(), busy = vm.busy) {
        vm.finish(onFinished)
    }
}
