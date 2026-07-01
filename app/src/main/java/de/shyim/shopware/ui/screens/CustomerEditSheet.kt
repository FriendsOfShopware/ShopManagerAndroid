package de.shyim.shopware.ui.screens

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.shyim.shopware.R
import de.shyim.shopware.ShopwareApp
import de.shyim.shopware.data.model.ConnectedShop
import de.shyim.shopware.data.model.CountryOption
import de.shyim.shopware.data.model.CustomerDetail
import de.shyim.shopware.data.model.EditableAddress
import de.shyim.shopware.data.model.SalutationOption
import kotlinx.coroutines.launch

class CustomerEditViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as ShopwareApp).repository

    var salutations by mutableStateOf<List<SalutationOption>>(emptyList())
        private set
    var countries by mutableStateOf<List<CountryOption>>(emptyList())
        private set
    var saving by mutableStateOf(false)
        private set
    var saveError by mutableStateOf<String?>(null)

    private fun appString(res: Int) = getApplication<Application>().getString(res)

    fun loadPickers(shop: ConnectedShop) {
        viewModelScope.launch {
            if (salutations.isEmpty()) {
                runCatching { repo.salutations(shop) }.onSuccess { salutations = it }
            }
            if (countries.isEmpty()) {
                runCatching { repo.countries(shop) }.onSuccess { countries = it }
            }
        }
    }

    // Saves the customer contact patch plus any changed address(es), then refreshes Home.
    fun save(
        shop: ConnectedShop,
        customerId: String,
        firstName: String,
        lastName: String,
        email: String,
        salutationId: String?,
        title: String?,
        company: String?,
        billing: EditableAddress?,
        shipping: EditableAddress?,
        onSaved: () -> Unit,
    ) {
        if (saving) return
        saving = true
        saveError = null
        viewModelScope.launch {
            runCatching {
                repo.saveCustomerContact(shop, customerId, firstName, lastName, email, salutationId, title, company)
                billing?.let { repo.saveCustomerAddress(shop, it) }
                shipping?.let { repo.saveCustomerAddress(shop, it) }
            }.fold(
                onSuccess = {
                    repo.refresh(shop.id)
                    onSaved()
                },
                onFailure = { saveError = it.message ?: appString(R.string.customer_edit_save_failed) }
            )
            saving = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerEditSheet(
    shop: ConnectedShop,
    detail: CustomerDetail,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    vm: CustomerEditViewModel = viewModel(),
) {
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(shop.id) { vm.loadPickers(shop) }

    var firstName by remember(detail.id) { mutableStateOf(detail.firstName) }
    var lastName by remember(detail.id) { mutableStateOf(detail.lastName) }
    var email by remember(detail.id) { mutableStateOf(detail.email) }
    var title by remember(detail.id) { mutableStateOf(detail.title ?: "") }
    var company by remember(detail.id) { mutableStateOf(detail.company ?: "") }
    var salutationId by remember(detail.id) { mutableStateOf(detail.salutationId) }
    var billing by remember(detail.id) { mutableStateOf(detail.billing) }
    var shipping by remember(detail.id) {
        // when the customer shares one record, only edit it once (as billing)
        mutableStateOf(if (detail.sharedAddress) null else detail.shipping)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .imePadding()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 36.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.customer_edit_title),
                color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            )

            // Contact
            SalutationField(
                options = vm.salutations,
                selectedId = salutationId,
                onSelect = { salutationId = it },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = firstName, onValueChange = { firstName = it },
                    label = { Text(stringResource(R.string.customer_edit_first_name)) },
                    singleLine = true, modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = lastName, onValueChange = { lastName = it },
                    label = { Text(stringResource(R.string.customer_edit_last_name)) },
                    singleLine = true, modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text(stringResource(R.string.customer_edit_email)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text(stringResource(R.string.customer_edit_title_field)) },
                    singleLine = true, modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = company, onValueChange = { company = it },
                    label = { Text(stringResource(R.string.customer_edit_company)) },
                    singleLine = true, modifier = Modifier.weight(1f),
                )
            }

            // Billing address
            billing?.let { addr ->
                AddressBlock(
                    title = stringResource(
                        if (detail.sharedAddress) R.string.customer_edit_address
                        else R.string.order_detail_billing_address
                    ),
                    address = addr,
                    countries = vm.countries,
                    onChange = { billing = it },
                )
            }
            // Shipping address (only when distinct from billing)
            shipping?.let { addr ->
                AddressBlock(
                    title = stringResource(R.string.order_detail_shipping_address),
                    address = addr,
                    countries = vm.countries,
                    onChange = { shipping = it },
                )
            }

            vm.saveError?.let { err ->
                Text(err, color = cs.error, fontSize = 12.5.sp)
            }

            Button(
                onClick = {
                    vm.save(
                        shop, detail.id,
                        firstName.trim(), lastName.trim(), email.trim(),
                        salutationId, title, company,
                        billing, shipping, onSaved,
                    )
                },
                enabled = !vm.saving && firstName.isNotBlank() && lastName.isNotBlank() && email.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(50.dp),
            ) {
                if (vm.saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = cs.onPrimary,
                    )
                } else Text(stringResource(R.string.common_save))
            }
        }
    }
}

@Composable
private fun AddressBlock(
    title: String,
    address: EditableAddress,
    countries: List<CountryOption>,
    onChange: (EditableAddress) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title, color = cs.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )
        OutlinedTextField(
            value = address.street, onValueChange = { onChange(address.copy(street = it)) },
            label = { Text(stringResource(R.string.customer_edit_street)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = address.additionalLine ?: "",
            onValueChange = { onChange(address.copy(additionalLine = it)) },
            label = { Text(stringResource(R.string.customer_edit_additional_line)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = address.zipcode, onValueChange = { onChange(address.copy(zipcode = it)) },
                label = { Text(stringResource(R.string.customer_edit_zip)) },
                singleLine = true, modifier = Modifier.weight(0.4f),
            )
            OutlinedTextField(
                value = address.city, onValueChange = { onChange(address.copy(city = it)) },
                label = { Text(stringResource(R.string.customer_edit_city)) },
                singleLine = true, modifier = Modifier.weight(0.6f),
            )
        }
        CountryField(
            countries = countries,
            selectedId = address.countryId,
            fallbackName = address.countryName,
            onSelect = { onChange(address.copy(countryId = it)) },
        )
        OutlinedTextField(
            value = address.phoneNumber ?: "",
            onValueChange = { onChange(address.copy(phoneNumber = it)) },
            label = { Text(stringResource(R.string.customer_edit_phone)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = address.company ?: "",
            onValueChange = { onChange(address.copy(company = it)) },
            label = { Text(stringResource(R.string.customer_edit_company)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SalutationField(
    options: List<SalutationOption>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.id == selectedId }?.label
    DropdownAnchor(
        label = stringResource(R.string.customer_edit_salutation),
        value = selected ?: "—",
        expanded = expanded,
        onExpand = { expanded = it },
    ) {
        options.forEach { opt ->
            DropdownMenuItem(text = { Text(opt.label) }, onClick = {
                onSelect(opt.id); expanded = false
            })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountryField(
    countries: List<CountryOption>,
    selectedId: String?,
    fallbackName: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = countries.firstOrNull { it.id == selectedId }?.name ?: fallbackName
    DropdownAnchor(
        label = stringResource(R.string.customer_edit_country),
        value = selected ?: "—",
        expanded = expanded,
        onExpand = { expanded = it },
    ) {
        countries.forEach { opt ->
            DropdownMenuItem(text = { Text(opt.name) }, onClick = {
                onSelect(opt.id); expanded = false
            })
        }
    }
}

// A read-only TextField that opens a DropdownMenu — avoids ExposedDropdownMenuBox API churn.
@Composable
private fun DropdownAnchor(
    label: String,
    value: String,
    expanded: Boolean,
    onExpand: (Boolean) -> Unit,
    menuContent: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        // transparent overlay catches the tap (read-only fields don't get click focus reliably)
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { onExpand(true) }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpand(false) },
            modifier = Modifier.heightIn(max = 360.dp),
        ) { menuContent() }
    }
}
