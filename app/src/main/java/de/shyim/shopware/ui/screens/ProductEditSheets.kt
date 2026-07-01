package de.shyim.shopware.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.shyim.shopware.R
import de.shyim.shopware.data.currencySymbol
import de.shyim.shopware.data.model.ConnectedShop
import de.shyim.shopware.data.model.ProductDetail
import de.shyim.shopware.data.model.ProductVariant
import de.shyim.shopware.data.source.PriceEdit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductBaseEditSheet(
    shop: ConnectedShop,
    detail: ProductDetail,
    saving: Boolean,
    saveError: String?,
    onSave: (name: String, active: Boolean, stock: Int, ean: String?, manufacturerNumber: String?, price: PriceEdit?) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val cfg = shop.productFields
    var name by remember(detail.id) { mutableStateOf(detail.name) }
    var active by remember(detail.id) { mutableStateOf(detail.active) }
    var stock by remember(detail.id) { mutableStateOf(detail.stock) }
    // Seeded from the current value: when a field is hidden from editing its original value is
    // preserved untouched through save (the input just isn't rendered).
    var ean by remember(detail.id) { mutableStateOf(detail.ean ?: "") }
    var manufacturerNumber by remember(detail.id) { mutableStateOf(detail.manufacturerNumber ?: "") }
    val price = rememberPriceEditState(detail.id, detail.grossPrice, detail.netPrice, detail.priceLinked, detail.taxRate)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                // Keep content above the keyboard and clear of the gesture nav bar (edge-to-edge).
                .imePadding()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 36.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.product_detail_edit), color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)

            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text(stringResource(R.string.product_edit_name)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.product_sheet_active_in_shop),
                    color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = active, onCheckedChange = { active = it })
            }

            StockStepper(stock, onChange = { stock = it })

            // A field appears in the edit sheet only when it is both shown and editable — a hidden
            // field is never editable (its Edit toggle is disabled in settings).
            if (cfg.showEan && cfg.editEan) {
                OutlinedTextField(
                    value = ean, onValueChange = { ean = it },
                    label = { Text(stringResource(R.string.product_detail_ean)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }

            if (cfg.showManufacturerNumber && cfg.editManufacturerNumber) {
                OutlinedTextField(
                    value = manufacturerNumber, onValueChange = { manufacturerNumber = it },
                    label = { Text(stringResource(R.string.product_detail_manufacturer_number)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }

            if (cfg.showPrice && cfg.editPrice) PriceEditor(shop, detail.priceEditable, price)

            saveError?.let { Text(it, color = cs.error, fontSize = 12.5.sp) }

            Button(
                onClick = { onSave(name.trim(), active, stock, ean.trim(), manufacturerNumber.trim(), price.toEdit(detail.priceEditable)) },
                enabled = !saving && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                if (saving) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = cs.onPrimary)
                else Text(stringResource(R.string.common_save))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VariantEditSheet(
    shop: ConnectedShop,
    variant: ProductVariant,
    saving: Boolean,
    saveError: String?,
    onSave: (stock: Int, price: PriceEdit?) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var stock by remember(variant.id) { mutableStateOf(variant.stock) }
    val price = rememberPriceEditState(variant.id, variant.grossPrice, variant.netPrice, variant.priceLinked, variant.taxRate)

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
                variant.optionLabels.joinToString(" · ").ifBlank { variant.productNumber },
                color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold
            )
            Text(variant.productNumber, color = cs.onSurfaceVariant, fontSize = 12.sp)

            StockStepper(stock, onChange = { stock = it })

            PriceEditor(shop, variant.priceEditable, price)

            saveError?.let { Text(it, color = cs.error, fontSize = 12.5.sp) }

            Button(
                onClick = { onSave(stock, price.toEdit(variant.priceEditable)) },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                if (saving) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = cs.onPrimary)
                else Text(stringResource(R.string.common_save))
            }
        }
    }
}

// ---- Shared linked gross/net price editor (mirrors the admin's sw-price-field) ----

class PriceEditState(
    grossInit: String,
    netInit: String,
    linkedInit: Boolean,
    val taxRate: Double?,
    private val origGross: Double?,
    private val origNet: Double?,
    private val origLinked: Boolean,
) {
    var gross by mutableStateOf(grossInit)
    var net by mutableStateOf(netInit)
    var linked by mutableStateOf(linkedInit)

    private fun parse(s: String) = s.replace(',', '.').toDoubleOrNull()
    private fun fmt(v: Double) = "%.2f".format(v)

    // Editing gross recomputes net from the tax rate when linked
    fun onGrossChange(s: String) {
        gross = s
        val g = parse(s); val r = taxRate
        if (linked && g != null && r != null) net = fmt(g / (1 + r / 100.0))
    }

    fun onNetChange(s: String) {
        net = s
        val n = parse(s); val r = taxRate
        if (linked && n != null && r != null) gross = fmt(n * (1 + r / 100.0))
    }

    fun toggleLinked() {
        linked = !linked
        // On re-link, snap net to match the current gross (admin behavior)
        if (linked) {
            val g = parse(gross); val r = taxRate
            if (g != null && r != null) net = fmt(g / (1 + r / 100.0))
        }
    }

    // null when nothing changed or not editable; the validated PriceEdit otherwise
    fun toEdit(editable: Boolean): PriceEdit? {
        if (!editable) return null
        val g = parse(gross) ?: return null
        val n = parse(net) ?: return null
        val unchanged = g == origGross && n == origNet && linked == origLinked
        if (unchanged) return null
        return PriceEdit(gross = g, net = n, linked = linked)
    }
}

@Composable
private fun rememberPriceEditState(
    key: String,
    gross: Double?,
    net: Double?,
    linked: Boolean,
    taxRate: Double?,
) = remember(key) {
    PriceEditState(
        grossInit = gross?.let { "%.2f".format(it) } ?: "",
        netInit = net?.let { "%.2f".format(it) } ?: "",
        linkedInit = linked,
        taxRate = taxRate,
        origGross = gross,
        origNet = net,
        origLinked = linked,
    )
}

@Composable
private fun PriceEditor(shop: ConnectedShop, editable: Boolean, state: PriceEditState) {
    val cs = MaterialTheme.colorScheme
    if (!editable) {
        Text(stringResource(R.string.product_sheet_price_not_editable), color = cs.onSurfaceVariant, fontSize = 12.sp)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.gross,
            onValueChange = state::onGrossChange,
            label = { Text(stringResource(R.string.product_sheet_gross_price)) },
            prefix = { Text(currencySymbol(shop.currency)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        // Lock toggle between the two fields (linked = keep net in sync via tax)
        IconButton(onClick = { state.toggleLinked() }) {
            Icon(
                if (state.linked) Icons.Outlined.Link else Icons.Outlined.LinkOff,
                contentDescription = stringResource(
                    if (state.linked) R.string.product_price_unlink else R.string.product_price_link
                ),
                tint = if (state.linked) cs.primary else cs.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = state.net,
            onValueChange = state::onNetChange,
            label = { Text(stringResource(R.string.product_price_net_field)) },
            prefix = { Text(currencySymbol(shop.currency)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
    }
    if (state.taxRate == null && state.linked) {
        Text(stringResource(R.string.product_price_no_tax), color = cs.onSurfaceVariant, fontSize = 11.5.sp)
    }
}

@Composable
fun StockStepper(stock: Int, onChange: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.product_sheet_stock),
            color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        FilledTonalIconButton(onClick = { onChange((stock - 1).coerceAtLeast(0)) }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.product_sheet_decrease))
        }
        StockField(stock, onChange, modifier = Modifier.width(80.dp).padding(horizontal = 8.dp))
        FilledTonalIconButton(onClick = { onChange(stock + 1) }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.product_sheet_increase))
        }
    }
}

/**
 * Editable stock value: typed entry via the on-screen number keyboard, kept in sync with the
 * +/- buttons. Local text state lets the field go empty mid-edit without snapping back to 0;
 * a blank or non-numeric field is treated as 0 on save.
 */
@Composable
fun StockField(stock: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    var text by remember { mutableStateOf(stock.toString()) }
    // Reflect external changes (the +/- buttons) back into the field
    if (text.toIntOrNull() != stock) text = stock.toString()
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(7)
            text = digits
            onChange(digits.toIntOrNull() ?: 0)
        },
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(
            fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = cs.onSurface
        ),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
