package de.shyim.shopware.ui.screens

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.shyim.shopware.R
import de.shyim.shopware.ShopwareApp
import de.shyim.shopware.data.fmtMoney
import de.shyim.shopware.data.model.ConnectedShop
import de.shyim.shopware.data.model.OrderDetail
import de.shyim.shopware.data.model.OrderStateInfo
import de.shyim.shopware.data.model.OrderTimelineEntry
import de.shyim.shopware.ui.components.BadgeTone
import de.shyim.shopware.ui.components.StatusBadge
import de.shyim.shopware.ui.relativeAgoText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed class OrderUi {
    data object Loading : OrderUi()
    data class Error(val message: String) : OrderUi()
    data class Ready(val detail: OrderDetail) : OrderUi()
}

class OrderDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as ShopwareApp).repository

    var ui by mutableStateOf<OrderUi>(OrderUi.Loading)
        private set
    var shop by mutableStateOf<ConnectedShop?>(null)
        private set
    var transitioning by mutableStateOf(false)
        private set
    var transitionError by mutableStateOf<String?>(null)

    // State history, loaded after the detail; a failure here never blocks the detail view
    var timeline by mutableStateOf<List<de.shyim.shopware.data.model.OrderTimelineEntry>>(emptyList())
        private set

    private fun appString(res: Int) = getApplication<Application>().getString(res)

    fun load(shopId: String, orderId: String) {
        viewModelScope.launch {
            ui = OrderUi.Loading
            val s = repo.data.first().shops.firstOrNull { it.id == shopId }
            if (s == null) {
                ui = OrderUi.Error(appString(R.string.common_shop_not_found))
                return@launch
            }
            shop = s
            val detail = runCatching { repo.orderDetail(s, orderId) }
            ui = detail.fold(
                onSuccess = { OrderUi.Ready(it) },
                onFailure = { OrderUi.Error(it.message ?: appString(R.string.order_detail_load_failed)) }
            )
            detail.getOrNull()?.let { d ->
                val refs = d.states.map { it.entityId }.filter { it.isNotEmpty() }
                timeline = runCatching { repo.orderTimeline(s, refs) }.getOrDefault(emptyList())
            }
        }
    }

    fun transition(
        orderId: String,
        state: OrderStateInfo,
        actionName: String,
        sendMail: Boolean,
        documentIds: List<String>,
        internalComment: String?,
    ) {
        val s = shop ?: return
        if (transitioning) return
        transitioning = true
        transitionError = null
        viewModelScope.launch {
            runCatching {
                repo.transitionOrderState(
                    s, state.entity, state.entityId, actionName,
                    sendMail, documentIds, internalComment,
                )
            }.fold(
                onSuccess = {
                    load(s.id, orderId)
                    repo.refresh(s.id) // keep dashboard's recent-orders state in sync
                },
                onFailure = { transitionError = it.message ?: appString(R.string.order_detail_transition_failed) }
            )
            transitioning = false
        }
    }

    fun saveInternalComment(orderId: String, comment: String) {
        val s = shop ?: return
        if (transitioning) return
        transitioning = true
        transitionError = null
        viewModelScope.launch {
            runCatching { repo.setInternalComment(s, orderId, comment.trim()) }.fold(
                onSuccess = { load(s.id, orderId) },
                onFailure = { transitionError = it.message ?: appString(R.string.order_detail_note_save_failed) }
            )
            transitioning = false
        }
    }

    fun setTrackingCodes(orderId: String, codes: List<String>) {
        val s = shop ?: return
        val deliveryId = (ui as? OrderUi.Ready)?.detail?.deliveryId ?: return
        if (transitioning) return
        transitioning = true
        transitionError = null
        viewModelScope.launch {
            runCatching {
                repo.setTrackingCodes(s, deliveryId, codes.filter { it.isNotBlank() }.distinct())
            }.fold(
                onSuccess = { load(s.id, orderId) },
                onFailure = { transitionError = it.message ?: appString(R.string.order_detail_tracking_failed) }
            )
            transitioning = false
        }
    }

    fun generateDocument(orderId: String, type: String) {
        val s = shop ?: return
        if (transitioning) return
        transitioning = true
        transitionError = null
        viewModelScope.launch {
            runCatching { repo.generateDocument(s, orderId, type) }.fold(
                onSuccess = { load(s.id, orderId) },
                onFailure = { transitionError = it.message ?: appString(R.string.order_detail_document_failed) }
            )
            transitioning = false
        }
    }

    // Downloads into cacheDir/documents and returns a shareable file
    suspend fun downloadDocument(doc: de.shyim.shopware.data.model.OrderDocument): java.io.File {
        val s = shop ?: error("No shop")
        val bytes = repo.downloadDocument(s, doc.id, doc.deepLinkCode)
        val dir = java.io.File(getApplication<Application>().cacheDir, "documents").apply { mkdirs() }
        val name = "${doc.typeTechnical.ifBlank { "document" }}-${doc.number.ifBlank { doc.id.take(8) }}.pdf"
        return java.io.File(dir, name).apply { writeBytes(bytes) }
    }
}

private fun stateTone(technical: String): BadgeTone = when (technical) {
    "completed", "paid", "shipped" -> BadgeTone.Good
    "cancelled", "failed", "refunded" -> BadgeTone.Bad
    "in_progress", "shipped_partially", "paid_partially", "reminded" -> BadgeTone.Warn
    else -> BadgeTone.Neutral
}

@Composable
private fun OrderTimelineCard(entries: List<OrderTimelineEntry>) {
    val cs = MaterialTheme.colorScheme
    // newest first reads better as an activity log
    val ordered = entries.sortedByDescending { it.createdAtMs }
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = cs.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.order_detail_timeline),
                color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
            )
            ordered.forEachIndexed { i, e ->
                val entityLabel = when (e.entity) {
                    "order_transaction" -> stringResource(R.string.order_detail_timeline_payment)
                    "order_delivery" -> stringResource(R.string.order_detail_timeline_delivery)
                    else -> stringResource(R.string.order_detail_timeline_order)
                }
                val who = e.userLabel ?: stringResource(R.string.order_detail_timeline_system)
                Row(modifier = Modifier.padding(top = if (i == 0) 12.dp else 0.dp)) {
                    // dot + connector rail
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(20.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(badgeColor(stateTone(e.toStateTechnical), cs))
                        )
                        if (i < ordered.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .width(2.dp)
                                    .height(34.dp)
                                    .background(cs.outlineVariant.copy(alpha = 0.6f))
                            )
                        }
                    }
                    Column(modifier = Modifier.padding(start = 12.dp, bottom = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "$entityLabel · ${e.toStateName}",
                                color = cs.onSurface, fontSize = 13.5.sp, fontWeight = FontWeight.Medium
                            )
                        }
                        Text(
                            "${relativeAgoText(e.createdAtMs)} · ${stringResource(R.string.order_detail_timeline_by, who)}",
                            color = cs.onSurfaceVariant, fontSize = 11.5.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun badgeColor(tone: BadgeTone, cs: androidx.compose.material3.ColorScheme) = when (tone) {
    BadgeTone.Good -> cs.primary
    BadgeTone.Bad -> cs.error
    BadgeTone.Warn -> cs.tertiary
    BadgeTone.Neutral -> cs.outline
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OrderDetailScreen(
    shopId: String,
    orderId: String,
    onBack: () -> Unit,
    vm: OrderDetailViewModel = viewModel(),
) {
    val cs = MaterialTheme.colorScheme
    var transitionTarget by androidx.compose.runtime.remember { mutableStateOf<OrderStateInfo?>(null) }

    LaunchedEffect(shopId, orderId) { vm.load(shopId, orderId) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = cs.onSurface)
        }
        when (val ui = vm.ui) {
            is OrderUi.Ready -> Text(
                stringResource(R.string.order_detail_title_number, ui.detail.orderNumber),
                color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold
            )
            else -> Text(stringResource(R.string.order_detail_title), color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }

    when (val ui = vm.ui) {
        OrderUi.Loading -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 100.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LoadingIndicator(modifier = Modifier.size(56.dp))
        }
        is OrderUi.Error -> SyncErrorCard(ui.message, onRetry = { vm.load(shopId, orderId) })
        is OrderUi.Ready -> {
            val d = ui.detail
            val shop = vm.shop ?: return

            Text(
                "${d.customerName} · ${d.orderDateTime}",
                color = cs.onSurfaceVariant, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            vm.transitionError?.let { err ->
                SyncErrorCard(err, onRetry = { vm.transitionError = null }, compact = true)
            }

            // States with transition actions
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = cs.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp)
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    d.states.forEachIndexed { i, state ->
                        if (i > 0) HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    state.label,
                                    color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium
                                )
                                Row(modifier = Modifier.padding(top = 5.dp)) {
                                    StatusBadge(stateTone(state.stateTechnical), state.stateName)
                                }
                            }
                            if (state.transitions.isNotEmpty()) {
                                FilledTonalButton(
                                    onClick = { transitionTarget = state },
                                    enabled = !vm.transitioning
                                ) { Text(stringResource(R.string.common_change), fontSize = 13.sp) }
                            }
                        }
                    }
                }
            }

            // Line items
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = cs.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // all detail amounts are in the order's own currency
                    val money = { v: Double -> fmtMoney(v, d.currencyIso ?: shop.currency, shop.localeCode) }
                    Text(stringResource(R.string.order_detail_items), color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    d.lineItems.forEach { li ->
                        Row(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                "${li.quantity}×",
                                color = cs.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(end = 10.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(li.label, color = cs.onSurface, fontSize = 13.5.sp)
                                val sub = listOfNotNull(
                                    li.productNumber,
                                    if (li.quantity > 1) stringResource(R.string.order_detail_unit_price, money(li.unitPrice)) else null,
                                ).joinToString(" · ")
                                if (sub.isNotBlank()) {
                                    Text(
                                        sub, color = cs.onSurfaceVariant, fontSize = 11.sp,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                            Text(
                                money(li.totalPrice),
                                color = cs.onSurface, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    HorizontalDivider(
                        color = cs.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                    TotalsLine(stringResource(R.string.order_detail_net), money(d.netTotal))
                    d.taxes.forEach { tax ->
                        val rate = "${if (tax.rate % 1.0 == 0.0) tax.rate.toInt() else tax.rate}"
                        TotalsLine(stringResource(R.string.order_detail_vat, rate), money(tax.amount))
                    }
                    if (d.shippingTotal > 0) {
                        TotalsLine(stringResource(R.string.order_detail_shipping), money(d.shippingTotal))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row {
                        Text(
                            stringResource(R.string.order_detail_total), color = cs.onSurface, fontSize = 15.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                        )
                        Text(
                            money(d.amountTotal),
                            color = cs.primary, fontSize = 15.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Documents
            val context = androidx.compose.ui.platform.LocalContext.current
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            var busyDocId by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
            val downloadFailed = stringResource(R.string.order_detail_download_failed)
            val noViewer = stringResource(R.string.order_detail_no_viewer)
            // Downloads the PDF, then runs it through the given intent action (VIEW or SEND)
            fun actOnDocument(
                doc: de.shyim.shopware.data.model.OrderDocument,
                action: String,
            ) {
                busyDocId = doc.id
                scope.launch {
                    runCatching { vm.downloadDocument(doc) }.fold(
                        onSuccess = { file ->
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", file
                            )
                            val intent = android.content.Intent(action).apply {
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                if (action == android.content.Intent.ACTION_VIEW) {
                                    setDataAndType(uri, "application/pdf")
                                } else {
                                    type = "application/pdf"
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                }
                            }
                            val chooser = android.content.Intent.createChooser(intent, doc.typeName)
                            runCatching { context.startActivity(chooser) }
                                .onFailure { vm.transitionError = noViewer }
                        },
                        onFailure = { vm.transitionError = it.message ?: downloadFailed }
                    )
                    busyDocId = null
                }
            }
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = cs.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.common_documents), color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    d.documents.forEach { doc ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Description, contentDescription = null,
                                tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp)
                            )
                            Text(
                                "${doc.typeName}${if (doc.number.isNotBlank()) " · ${doc.number}" else ""}",
                                color = cs.onSurface, fontSize = 13.5.sp,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 10.dp)
                            )
                            if (busyDocId == doc.id) {
                                Text("…", color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp))
                            } else {
                                TextButton(
                                    onClick = { actOnDocument(doc, android.content.Intent.ACTION_VIEW) },
                                    enabled = busyDocId == null
                                ) {
                                    Text(stringResource(R.string.order_detail_open), fontSize = 13.sp)
                                }
                                TextButton(
                                    onClick = { actOnDocument(doc, android.content.Intent.ACTION_SEND) },
                                    enabled = busyDocId == null
                                ) {
                                    Text(stringResource(R.string.order_detail_share), fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    if (d.documents.isEmpty()) {
                        Text(
                            stringResource(R.string.order_detail_no_documents),
                            color = cs.onSurfaceVariant, fontSize = 12.5.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    run {
                        // type -> localized label; selecting one asks for confirmation first
                        val docTypes = listOf(
                            "invoice" to stringResource(R.string.order_detail_invoice),
                            "delivery_note" to stringResource(R.string.order_detail_delivery_note),
                        )
                        var pendingDocType by androidx.compose.runtime.remember { mutableStateOf<Pair<String, String>?>(null) }
                        Row(
                            modifier = Modifier.padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            docTypes.forEach { entry ->
                                FilledTonalButton(
                                    onClick = { pendingDocType = entry },
                                    enabled = !vm.transitioning
                                ) { Text("+ ${entry.second}", fontSize = 12.5.sp) }
                            }
                        }
                        pendingDocType?.let { (type, label) ->
                            AlertDialog(
                                onDismissRequest = { pendingDocType = null },
                                title = { Text(stringResource(R.string.order_detail_generate_confirm_title, label)) },
                                text = { Text(stringResource(R.string.order_detail_generate_confirm_text, label)) },
                                confirmButton = {
                                    TextButton(onClick = {
                                        pendingDocType = null
                                        vm.generateDocument(orderId, type)
                                    }) { Text(stringResource(R.string.order_detail_generate_confirm)) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { pendingDocType = null }) {
                                        Text(stringResource(R.string.common_cancel))
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // Customer
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = cs.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.customer_detail_title), color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(d.customerName, color = cs.onSurface, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            if (d.customerEmail.isNotBlank()) {
                                Text(d.customerEmail, color = cs.onSurfaceVariant, fontSize = 12.sp)
                            }
                        }
                        if (d.customerEmail.isNotBlank()) {
                            IconButton(onClick = {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_SENDTO,
                                        android.net.Uri.parse("mailto:${d.customerEmail}")
                                    )
                                )
                            }) {
                                Icon(Icons.Outlined.MailOutline, contentDescription = stringResource(R.string.customer_detail_email_customer), tint = cs.primary)
                            }
                        }
                        d.phone?.let { phone ->
                            IconButton(onClick = {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_DIAL,
                                        android.net.Uri.parse("tel:$phone")
                                    )
                                )
                            }) {
                                Icon(Icons.Outlined.Call, contentDescription = stringResource(R.string.customer_detail_call_customer), tint = cs.primary)
                            }
                        }
                    }
                    d.paymentMethod?.let { DetailRow(stringResource(R.string.order_detail_payment), it) }
                    d.billingAddress?.let { addr ->
                        Column {
                            Text(stringResource(R.string.order_detail_billing_address), color = cs.onSurfaceVariant, fontSize = 12.sp)
                            Text(
                                addr, color = cs.onSurface, fontSize = 13.sp, lineHeight = 18.sp,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                    }
                    d.customerComment?.let { comment ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = cs.tertiaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(stringResource(R.string.order_detail_customer_note), color = cs.onSurfaceVariant, fontSize = 11.sp)
                                Text(
                                    comment, color = cs.onSurface, fontSize = 13.sp, lineHeight = 18.sp,
                                    modifier = Modifier.padding(top = 3.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Internal note (merchant-facing, not visible to the customer)
            run {
                var editingNote by androidx.compose.runtime.remember(d.id) { mutableStateOf(false) }
                var noteText by androidx.compose.runtime.remember(d.id, d.internalComment) {
                    mutableStateOf(d.internalComment ?: "")
                }
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = cs.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.order_detail_internal_note),
                                color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            if (!editingNote) {
                                TextButton(onClick = { editingNote = true }) {
                                    Text(
                                        if (d.internalComment == null) stringResource(R.string.common_add)
                                        else stringResource(R.string.common_edit),
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                        if (editingNote) {
                            OutlinedTextField(
                                value = noteText,
                                onValueChange = { noteText = it },
                                placeholder = { Text(stringResource(R.string.order_detail_note_placeholder), fontSize = 13.sp) },
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = {
                                        noteText = d.internalComment ?: ""
                                        editingNote = false
                                    },
                                    enabled = !vm.transitioning
                                ) { Text(stringResource(R.string.common_cancel)) }
                                FilledTonalButton(
                                    onClick = {
                                        editingNote = false
                                        vm.saveInternalComment(orderId, noteText)
                                    },
                                    enabled = !vm.transitioning && noteText.trim() != (d.internalComment ?: ""),
                                    modifier = Modifier.padding(start = 8.dp)
                                ) { Text(stringResource(R.string.common_save)) }
                            }
                        } else {
                            Text(
                                d.internalComment ?: stringResource(R.string.order_detail_no_note),
                                color = if (d.internalComment != null) cs.onSurface else cs.onSurfaceVariant,
                                fontSize = 13.sp, lineHeight = 18.sp,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }
            }

            // Shipping & tracking
            if (d.deliveryId != null) {
                var newCode by androidx.compose.runtime.remember(d.id) { mutableStateOf("") }
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = cs.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.order_detail_shipping), color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        d.shippingMethod?.let { DetailRow(stringResource(R.string.order_detail_method), it) }
                        d.shippingAddress?.let { addr ->
                            Column {
                                Text(stringResource(R.string.order_detail_shipping_address), color = cs.onSurfaceVariant, fontSize = 12.sp)
                                Text(
                                    addr, color = cs.onSurface, fontSize = 13.sp, lineHeight = 18.sp,
                                    modifier = Modifier.padding(top = 3.dp)
                                )
                            }
                        }
                        Text(stringResource(R.string.order_detail_tracking_codes), color = cs.onSurfaceVariant, fontSize = 12.sp)
                        d.trackingCodes.forEach { code ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.LocalShipping, contentDescription = null,
                                    tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    code,
                                    color = cs.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 8.dp)
                                )
                                run {
                                    IconButton(
                                        onClick = { vm.setTrackingCodes(orderId, d.trackingCodes - code) },
                                        enabled = !vm.transitioning
                                    ) {
                                        Icon(
                                            Icons.Outlined.Close, contentDescription = stringResource(R.string.order_detail_remove_tracking),
                                            tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                        if (d.trackingCodes.isEmpty()) {
                            Text(stringResource(R.string.order_detail_no_tracking), color = cs.onSurfaceVariant, fontSize = 12.5.sp)
                        }
                        run {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = newCode,
                                    onValueChange = { newCode = it },
                                    placeholder = { Text(stringResource(R.string.order_detail_add_tracking), fontSize = 13.sp) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                FilledTonalButton(
                                    onClick = {
                                        vm.setTrackingCodes(orderId, d.trackingCodes + newCode.trim())
                                        newCode = ""
                                    },
                                    enabled = newCode.isNotBlank() && !vm.transitioning,
                                    modifier = Modifier.padding(start = 8.dp)
                                ) { Text(stringResource(R.string.common_add)) }
                            }
                        }
                    }
                }
            }

            // Activity timeline (state-machine history across order/payment/delivery)
            if (vm.timeline.isNotEmpty()) {
                OrderTimelineCard(vm.timeline)
            }
        }
    }

    transitionTarget?.let { state ->
        val documents = (vm.ui as? OrderUi.Ready)?.detail?.documents.orEmpty()
        TransitionSheet(
            state = state,
            documents = documents,
            busy = vm.transitioning,
            onConfirm = { action, sendMail, documentIds, comment ->
                transitionTarget = null
                vm.transition(orderId, state, action, sendMail, documentIds, comment)
            },
            onDismiss = { transitionTarget = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransitionSheet(
    state: OrderStateInfo,
    documents: List<de.shyim.shopware.data.model.OrderDocument>,
    busy: Boolean,
    onConfirm: (action: String, sendMail: Boolean, documentIds: List<String>, comment: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // null while picking the target; set once a destination is chosen
    var picked by remember(state.entityId) {
        mutableStateOf(state.transitions.singleOrNull())
    }
    var sendMail by remember(state.entityId) { mutableStateOf(true) }
    var comment by remember(state.entityId) { mutableStateOf("") }
    val attached = remember(state.entityId) { mutableStateListOf<String>() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .imePadding()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 36.dp)
                .verticalScroll(rememberScrollState())
        ) {
            val target = picked
            if (target == null) {
                Text(
                    "${state.label}: ${state.stateName}",
                    color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.order_detail_move_to),
                    color = cs.onSurfaceVariant, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
                state.transitions.forEach { t ->
                    FilledTonalButton(
                        onClick = { picked = t },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .height(48.dp)
                    ) { Text(t.displayName) }
                }
            } else {
                Text(
                    stringResource(R.string.order_detail_transition_title, state.label, target.displayName),
                    color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    Text(
                        stringResource(R.string.order_detail_send_email),
                        color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.Switch(
                        checked = sendMail,
                        onCheckedChange = { sendMail = it },
                    )
                }

                if (sendMail && documents.isNotEmpty()) {
                    Text(
                        stringResource(R.string.order_detail_attach_documents),
                        color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    documents.forEach { doc ->
                        val checked = doc.id in attached
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    if (checked) attached.remove(doc.id) else attached.add(doc.id)
                                }
                                .padding(vertical = 2.dp)
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    if (it) attached.add(doc.id) else attached.remove(doc.id)
                                },
                            )
                            Text(
                                "${doc.typeName}${if (doc.number.isNotBlank()) " · ${doc.number}" else ""}",
                                color = cs.onSurface, fontSize = 13.5.sp,
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.order_detail_attach_hint),
                        color = cs.onSurfaceVariant, fontSize = 11.5.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text(stringResource(R.string.order_detail_transition_comment)) },
                    minLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                )

                Button(
                    onClick = {
                        onConfirm(
                            target.actionName,
                            sendMail,
                            if (sendMail) attached.toList() else emptyList(),
                            comment.trim().ifBlank { null },
                        )
                    },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                        .height(50.dp)
                ) { Text(stringResource(R.string.order_detail_confirm_transition)) }
            }
        }
    }
}

@Composable
private fun TotalsLine(label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Row(modifier = Modifier.padding(bottom = 6.dp)) {
        Text(label, color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = cs.onSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Row {
        Text(
            label, color = cs.onSurfaceVariant, fontSize = 13.sp,
            modifier = Modifier.padding(end = 12.dp)
        )
        Text(value, color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
