package za.westend.watersplit.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.westend.watersplit.data.Packages
import za.westend.watersplit.share.FileKind
import za.westend.watersplit.share.ShareInbox
import za.westend.watersplit.share.ShareSheet
import java.io.File
import java.time.LocalDate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import za.westend.watersplit.data.Draft
import za.westend.watersplit.data.MonthRecord
import za.westend.watersplit.data.Store
import za.westend.watersplit.engine.DraftChecker
import za.westend.watersplit.engine.Fmt
import za.westend.watersplit.engine.House
import za.westend.watersplit.engine.MonthKey
import za.westend.watersplit.update.Updater

@Composable
fun HomeScreen(
    store: Store,
    onNew: () -> Unit,
    onOpenDraft: (String) -> Unit,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    inbox: ShareInbox,
) {
    val months = store.months.asReversed()
    val drafts = store.drafts.asReversed()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Long-pressing a completed month starts selecting months to share; tapping then ticks / unticks them.
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selecting by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler(enabled = selecting) {
        selecting = false
        selected = emptySet()
    }
    fun toggle(key: String) {
        selected = if (key in selected) selected - key else selected + key
    }
    // "Save": Android's Save screen, so the months file can be kept on the phone, in Drive...
    val saveMonths = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val keys = selected
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openOutputStream(uri)!!.use { Packages.writeMonths(store, keys, it) } }.isSuccess
                }
                Toast.makeText(context, if (ok) "Months saved." else "The months couldn't be saved. Please try another location.", Toast.LENGTH_LONG).show()
                if (ok) {
                    selecting = false
                    selected = emptySet()
                }
            }
        }
    }
    // "Import months": pick a .wsmonths file; it goes through the same questions as a file opened from outside.
    fun openMonthsFile(uri: android.net.Uri) {
        scope.launch {
            val pkg = withContext(Dispatchers.IO) { Packages.receive(context, uri) }
            if (pkg == null) {
                Toast.makeText(context, "That isn't a Water Splitter file.", Toast.LENGTH_LONG).show()
            } else {
                inbox.pending = pkg
            }
        }
    }
    val importMonths = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) openMonthsFile(uri) }
    var listing by remember { mutableStateOf(false) }
    if (listing) {
        FilePickerDialog(
            kind = FileKind.MONTHS,
            onPick = { found ->
                listing = false
                openMonthsFile(android.net.Uri.fromFile(found.file))
            },
            onOther = {
                listing = false
                importMonths.launch(arrayOf("*/*"))
            },
            onClose = { listing = false },
        )
    }
    fun shareSelected() {
        val keys = selected
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "exports").apply {
                    deleteRecursively()
                    mkdirs()
                }
                File(dir, "water-splitter-months-${LocalDate.now()}.wsmonths").also { f ->
                    f.outputStream().use { Packages.writeMonths(store, keys, it) }
                }
            }
            ShareSheet.shareFile(context, file, "Water Splitter months")
        }
    }

    val listState = rememberLazyListState()
    val backdrop = rememberBackdrop(listState)
    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().frostSource(backdrop).padding(horizontal = 16.dp),
        // room under the last month so the floating menu never covers it
        contentPadding = PaddingValues(top = 12.dp, bottom = if (selecting) 220.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { PermissionAlertBanner() }
        Updater.available?.let { release ->
            item {
                Panel(container = BannerColors.update(), contentColor = BannerColors.text()) {
                    Text("A new version is available", fontWeight = FontWeight.SemiBold)
                    Text("Version ${release.version} is ready. Your months and tenants stay as they are.")
                    UpdateActions()
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Water Splitter",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onHelp) {
                    Text("?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { contentDescription = "Help" })
                }
                IconButton(onClick = onSettings) {
                    // A dot on the cog tells her a new version is waiting (details are under Settings -> App settings).
                    BadgedBox(badge = { if (Updater.available != null) Badge() }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            }
        }
        item {
            Button(onClick = onNew, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                Text("New month", style = MaterialTheme.typography.titleMedium)
            }
        }
        if (!selecting) {
            item {
                OutlinedButton(onClick = { listing = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("Import months from a file")
                }
            }
        }
        if (drafts.isNotEmpty()) {
            item { SectionTitle("In progress") }
            items(drafts, key = { "draft-" + it.key }) { d ->
                DraftRow(
                    d, selecting = selecting, isSelected = d.key in selected,
                    onClick = { if (selecting) toggle(d.key) else onOpenDraft(d.key) },
                    onLongClick = {
                        selecting = true
                        if (d.key !in selected) toggle(d.key)
                    },
                )
            }
        }
        item { SectionTitle("Previous months") }
        items(months, key = { it.key }) { m ->
            MonthRow(
                m, store, selecting = selecting, isSelected = m.key in selected,
                onClick = { if (selecting) toggle(m.key) else onOpen(m.key) },
                onLongClick = {
                    selecting = true
                    if (m.key !in selected) toggle(m.key)
                },
            )
        }
    }
    if (selecting) {
        SelectionMenu(
            count = selected.size,
            backdrop = backdrop,
            onShare = { shareSelected() },
            onSave = { saveMonths.launch("water-splitter-months-${LocalDate.now()}.wsmonths") },
            onDelete = { confirmDelete = true },
            onSelectAll = { selected = (months.map { it.key } + drafts.map { it.key }).toSet() },
            onCancel = {
                selecting = false
                selected = emptySet()
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${selected.size} ${if (selected.size == 1) "month" else "months"}?") },
            text = {
                Text(
                    "The selected months (including any still in progress), with their invoices and meter photos, will be removed from this phone. " +
                        "This can't be undone (unless you saved them first or have a backup).",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    selected.forEach { if (store.draft(it) != null) store.deleteDraft(it) else store.deleteMonth(it) }
                    confirmDelete = false
                    selecting = false
                    selected = emptySet()
                }) { Text("Yes, delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** The floating menu at the bottom of the screen while months are being selected. Every button is the same style and size. */
@Composable
private fun SelectionMenu(
    count: Int,
    backdrop: Backdrop,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A frosted-glass panel: what is behind it (the months) shows through blurred, with a light tint and a thin edge.
    val shape = RoundedCornerShape(24.dp)
    val dark = isSystemInDarkTheme()
    val tint = if (dark) Color(0xFF1B1B1F).copy(alpha = 0.55f) else Color.White.copy(alpha = 0.4f)
    val solid = MaterialTheme.colorScheme.surfaceContainerHigh
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
            .shadow(16.dp, shape)
            .clip(shape)
            .onGloballyPositioned { origin = it.positionInRoot() }
            .drawBehind {
                // Redraw whenever the list behind scrolls.
                @Suppress("UNUSED_VARIABLE") val scrolled = backdrop.scrollKey()
                if (Build.VERSION.SDK_INT >= 31 && backdrop.ready()) {
                    drawFrost(backdrop, origin)
                    drawRect(tint)
                } else {
                    drawRect(solid.copy(alpha = 0.96f))
                }
            }
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), shape),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val b = Modifier.weight(1f).height(48.dp)
            val pad = PaddingValues(horizontal = 4.dp)
            val colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onShare, enabled = count > 0, colors = colors, modifier = b, contentPadding = pad) { Text("Share") }
                FilledTonalButton(onClick = onSave, enabled = count > 0, colors = colors, modifier = b, contentPadding = pad) { Text("Save") }
                FilledTonalButton(onClick = onDelete, enabled = count > 0, colors = colors, modifier = b, contentPadding = pad) { Text("Delete") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onSelectAll, colors = colors, modifier = b, contentPadding = pad) { Text("Select all", maxLines = 1) }
                FilledTonalButton(onClick = onCancel, colors = colors, modifier = b, contentPadding = pad) { Text("Cancel") }
                Text(
                    if (count == 0) "Tap months" else "$count selected",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DraftRow(d: Draft, selecting: Boolean, isSelected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val check = remember(d) { DraftChecker.check(d.fields, MonthKey.parse(d.key).daysInMonth()) }
    val colors = if (isSelected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()
    Card(colors = colors, modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selecting) Checkbox(checked = isSelected, onCheckedChange = { onClick() })
                Text(d.label, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Invoice: " + when {
                    check.invoiceCaptured -> "✓ numbers captured"
                    d.invoice != null -> "received, numbers not captured yet"
                    else -> "not received yet"
                },
            )
            Text(
                "Meter: " + when {
                    check.meterCaptured -> "✓ reading captured"
                    d.meter != null -> "photo received, reading not captured yet"
                    else -> "not received yet"
                },
            )
            if (check.ready) {
                Text("Ready to review and send", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Who a house's statement went to in a month: the tenant recorded for it, else the name saved with the month. */
private fun invoicedTo(store: Store, m: MonthRecord, house: House): String {
    val days = if (house == House.OUTBUILDING) m.outbuildingDays else m.forebuildingDays
    if (days == 0) return "Vacant"
    val snapshot = if (house == House.OUTBUILDING) m.outbuildingTenant else m.forebuildingTenant
    return store.tenantFor(house, m.key)?.name ?: snapshot.ifBlank { "Tenant not recorded" }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MonthRow(
    m: MonthRecord,
    store: Store,
    selecting: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val result = remember(m) { m.calculate() }
    val colors = if (isSelected) {
        androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        androidx.compose.material3.CardDefaults.cardColors()
    }
    Card(colors = colors, modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selecting) Checkbox(checked = isSelected, onCheckedChange = { onClick() })
                Text(m.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (result.warnings.isNotEmpty() && !m.importedFromSheet) Text("⚠ check")
            }
            HouseAmountRow(House.OUTBUILDING, invoicedTo(store, m, House.OUTBUILDING), Fmt.rand(result.outbuilding.total))
            HouseAmountRow(House.FOREBUILDING, invoicedTo(store, m, House.FOREBUILDING), Fmt.rand(result.forebuilding.total))
            if (m.importedFromSheet) {
                Text("From the old spreadsheet", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun HouseAmountRow(house: House, who: String, amount: String) {
    val unknown = who == "Tenant not recorded" || who == "Vacant"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(house.label)
            Text(
                who,
                style = MaterialTheme.typography.bodySmall,
                color = if (unknown) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                fontWeight = if (unknown) FontWeight.Normal else FontWeight.SemiBold,
            )
        }
        Text(amount)
    }
}
