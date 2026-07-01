package de.shyim.shopware.ui.listing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import de.shyim.shopware.R
import de.shyim.shopware.api.ShopApi
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

// Buffered filter sheet: edits apply only on "Apply" (single reload via applyFilterValues)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    filters: List<ListingFilter>,
    activeValues: Map<String, FilterValue>,
    api: ShopApi?,
    onApply: (Map<String, FilterValue>) -> Unit,
    onDismiss: () -> Unit,
) {
    val buffer = remember { mutableStateMapOf<String, FilterValue>().apply { putAll(activeValues) } }
    var resetTick by remember { mutableIntStateOf(0) }

    fun set(key: String, value: FilterValue?) {
        if (value == null || value.isEmpty) buffer.remove(key) else buffer[key] = value
    }

    // Without an API handle, Options filters that can only resolve via the API are hidden
    val visibleFilters = filters.filter {
        it !is ListingFilter.Options || it.staticOptions != null || (api != null && it.loadOptions != null)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 36.dp)
        ) {
            Text(
                stringResource(R.string.listing_filters),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp, fontWeight = FontWeight.Bold
            )

            visibleFilters.forEach { filter ->
                Text(
                    filter.label,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 18.dp, bottom = 8.dp)
                )
                when (filter) {
                    is ListingFilter.Text -> TextEditor(
                        filter, buffer[filter.key] as? FilterValue.TextValue, resetTick
                    ) { set(filter.key, it) }

                    is ListingFilter.NumberRange -> NumberRangeEditor(
                        filter, buffer[filter.key] as? FilterValue.RangeValue, resetTick
                    ) { set(filter.key, it) }

                    is ListingFilter.DateRange -> DateRangeEditor(
                        filter, buffer[filter.key] as? FilterValue.DateRangeValue, resetTick
                    ) { set(filter.key, it) }

                    is ListingFilter.Options -> OptionsEditor(
                        filter, buffer[filter.key] as? FilterValue.OptionsValue, api
                    ) { set(filter.key, it) }

                    is ListingFilter.Bool -> BoolEditor(
                        filter, buffer[filter.key] as? FilterValue.OptionsValue
                    ) { set(filter.key, it) }

                    is ListingFilter.Existence -> ExistenceEditor(
                        filter, buffer[filter.key] as? FilterValue.ExistenceValue
                    ) { set(filter.key, it) }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
            ) {
                TextButton(onClick = { buffer.clear(); resetTick++ }) { Text(stringResource(R.string.filter_clear_all)) }
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = { onApply(buffer.toMap()); onDismiss() }) { Text(stringResource(R.string.common_apply)) }
            }
        }
    }
}

@Composable
private fun TextEditor(
    filter: ListingFilter.Text,
    value: FilterValue.TextValue?,
    resetKey: Int,
    onChange: (FilterValue?) -> Unit,
) {
    var text by remember(filter.key, resetKey) { mutableStateOf(value?.text ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(if (it.isBlank()) null else FilterValue.TextValue(it))
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun NumberRangeEditor(
    filter: ListingFilter.NumberRange,
    value: FilterValue.RangeValue?,
    resetKey: Int,
    onChange: (FilterValue?) -> Unit,
) {
    fun numText(d: Double?) = d?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: ""
    var minText by remember(filter.key, resetKey) { mutableStateOf(numText(value?.min)) }
    var maxText by remember(filter.key, resetKey) { mutableStateOf(numText(value?.max)) }

    fun push() {
        val min = minText.replace(',', '.').toDoubleOrNull()
        val max = maxText.replace(',', '.').toDoubleOrNull()
        onChange(if (min == null && max == null) null else FilterValue.RangeValue(min, max))
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = minText,
            onValueChange = { minText = it.filter { c -> c.isDigit() || c == '.' || c == ',' }; push() },
            label = { Text(stringResource(R.string.filter_min)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = maxText,
            onValueChange = { maxText = it.filter { c -> c.isDigit() || c == '.' || c == ',' }; push() },
            label = { Text(stringResource(R.string.filter_max)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DateRangeEditor(
    filter: ListingFilter.DateRange,
    value: FilterValue.DateRangeValue?,
    resetKey: Int,
    onChange: (FilterValue?) -> Unit,
) {
    var fromText by remember(filter.key, resetKey) { mutableStateOf(value?.from?.toString() ?: "") }
    var toText by remember(filter.key, resetKey) { mutableStateOf(value?.to?.toString() ?: "") }

    fun parse(s: String): LocalDate? = runCatching { LocalDate.parse(s.trim()) }.getOrNull()

    fun push() {
        val from = parse(fromText)
        val to = parse(toText)
        onChange(if (from == null && to == null) null else FilterValue.DateRangeValue(from, to))
    }

    if (filter.withPresets) {
        val today = LocalDate.now()
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 4.dp)
        ) {
            listOf(
                stringResource(R.string.filter_today) to today,
                stringResource(R.string.filter_last_7_days) to today.minusDays(6),
                stringResource(R.string.filter_last_30_days) to today.minusDays(29),
            )
                .forEach { (label, from) ->
                    FilterChip(
                        selected = fromText == from.toString() && toText == today.toString(),
                        onClick = {
                            fromText = from.toString()
                            toText = today.toString()
                            push()
                        },
                        label = { Text(label) }
                    )
                }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = fromText,
            onValueChange = { fromText = it; push() },
            label = { Text(stringResource(R.string.filter_from)) },
            singleLine = true,
            isError = fromText.isNotBlank() && parse(fromText) == null,
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = toText,
            onValueChange = { toText = it; push() },
            label = { Text(stringResource(R.string.filter_to)) },
            singleLine = true,
            isError = toText.isNotBlank() && parse(toText) == null,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionsEditor(
    filter: ListingFilter.Options,
    value: FilterValue.OptionsValue?,
    api: ShopApi?,
    onChange: (FilterValue?) -> Unit,
) {
    var options by remember(filter.key) { mutableStateOf(filter.staticOptions) }
    var loadError by remember(filter.key) { mutableStateOf<String?>(null) }
    val loadErrorFallback = stringResource(R.string.filter_options_load_failed)
    val loader = filter.loadOptions
    if (options == null && loader != null && api != null) {
        LaunchedEffect(filter.key) {
            try {
                options = loader(api)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loadError = e.message ?: loadErrorFallback
            }
        }
    }

    when {
        options == null && loadError != null -> Text(
            loadError!!,
            color = MaterialTheme.colorScheme.error, fontSize = 12.5.sp
        )

        options == null -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.common_loading), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp)
        }

        else -> FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.orEmpty().forEach { option ->
                val selected = value?.ids?.contains(option.id) == true
                FilterChip(
                    selected = selected,
                    onClick = {
                        val current = value?.ids ?: emptySet()
                        val next = when {
                            !filter.multi -> if (selected) emptySet() else setOf(option.id)
                            selected -> current - option.id
                            else -> current + option.id
                        }
                        onChange(if (next.isEmpty()) null else FilterValue.OptionsValue(next))
                    },
                    label = { Text(option.label) }
                )
            }
        }
    }
}

@Composable
private fun BoolEditor(
    filter: ListingFilter.Bool,
    value: FilterValue.OptionsValue?,
    onChange: (FilterValue?) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("true" to filter.trueLabel, "false" to filter.falseLabel).forEach { (id, label) ->
            val selected = value?.ids?.contains(id) == true
            FilterChip(
                selected = selected,
                onClick = { onChange(if (selected) null else FilterValue.OptionsValue(setOf(id))) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun ExistenceEditor(
    filter: ListingFilter.Existence,
    value: FilterValue.ExistenceValue?,
    onChange: (FilterValue?) -> Unit,
) {
    val choices = listOf<Pair<String, Boolean?>>(
        stringResource(R.string.filter_any) to null,
        filter.hasLabel to true,
        filter.hasNotLabel to false,
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, (label, has) ->
            SegmentedButton(
                selected = value?.has == has,
                onClick = { onChange(if (has == null) null else FilterValue.ExistenceValue(has)) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = choices.size),
            ) { Text(label, maxLines = 1) }
        }
    }
}
