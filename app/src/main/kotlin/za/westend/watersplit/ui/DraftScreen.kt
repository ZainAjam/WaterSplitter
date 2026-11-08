package za.westend.watersplit.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.westend.watersplit.data.Attachment
import za.westend.watersplit.data.AttachmentKind
import za.westend.watersplit.data.MonthRecord
import za.westend.watersplit.data.Occupant
import za.westend.watersplit.data.ReadInfo
import za.westend.watersplit.data.RulesVersion
import za.westend.watersplit.data.Store
import za.westend.watersplit.data.plain
import za.westend.watersplit.engine.DraftCheck
import za.westend.watersplit.engine.DraftChecker
import za.westend.watersplit.engine.DraftFields
import za.westend.watersplit.engine.Fmt
import za.westend.watersplit.engine.House
import za.westend.watersplit.engine.MonthKey
import za.westend.watersplit.engine.Parse
import za.westend.watersplit.engine.Party
import za.westend.watersplit.engine.ReadingNote
import za.westend.watersplit.reading.ReaderService
import za.westend.watersplit.data.DriveBackup
import za.westend.watersplit.share.Incoming
import za.westend.watersplit.share.PickDocumentFrom
import za.westend.watersplit.share.SourceFiles
import za.westend.watersplit.share.StartFolder

/** The draft's fields as live, editable text. */
private class DraftForm(f: DraftFields) {
    var totalKl by mutableStateOf(f.totalKl)
    var water by mutableStateOf(f.water)
    var avail by mutableStateOf(f.avail)
    var sewer by mutableStateOf(f.sewer)
    var sewAvail by mutableStateOf(f.sewAvail)
    var refuse by mutableStateOf(f.refuse)
    var rates by mutableStateOf(f.rates)
    var interest by mutableStateOf(f.interest)
    var adjustments by mutableStateOf(f.adjustments)
    var payable by mutableStateOf(f.payable)
    var prev by mutableStateOf(f.prev)
    var prevEdited by mutableStateOf(f.prevEdited)
    var curr by mutableStateOf(f.curr)
    var replaced by mutableStateOf(f.replaced)
    var oldFinal by mutableStateOf(f.oldFinal)
    var newStart by mutableStateOf(f.newStart)
    var fullMonth by mutableStateOf(f.fullMonth)
    var outbDays by mutableStateOf(f.outbDays)
    var foreDays by mutableStateOf(f.foreDays)
    var interestTo by mutableStateOf(f.interestTo)
    var adjustmentsTo by mutableStateOf(f.adjustmentsTo)

    fun toFields() = DraftFields(
        totalKl = totalKl, water = water, avail = avail, sewer = sewer, sewAvail = sewAvail, refuse = refuse,
        rates = rates, interest = interest, adjustments = adjustments, payable = payable, prev = prev,
        prevEdited = prevEdited, curr = curr, replaced = replaced, oldFinal = oldFinal, newStart = newStart,
        fullMonth = fullMonth, outbDays = outbDays, foreDays = foreDays, interestTo = interestTo,
        adjustmentsTo = adjustmentsTo,
    )

    /** Takes in numbers filled by automatic reading, but only into boxes that are still empty here. */
    fun fillBlanksFrom(f: DraftFields) {
        if (totalKl.isBlank()) totalKl = f.totalKl
        if (water.isBlank()) water = f.water
        if (avail.isBlank()) avail = f.avail
        if (sewer.isBlank()) sewer = f.sewer
        if (sewAvail.isBlank()) sewAvail = f.sewAvail
        if (refuse.isBlank()) refuse = f.refuse
        if (rates.isBlank()) rates = f.rates
        if (interest.isBlank()) interest = f.interest
        if (adjustments.isBlank()) adjustments = f.adjustments
        if (payable.isBlank()) payable = f.payable
        if (curr.isBlank()) curr = f.curr
    }
}

/** Where an item (invoice / meter photo) is in its journey. */
private enum class Stage { WAITING, RECEIVED, CAPTURED }

private fun stageOf(hasFile: Boolean, captured: Boolean) = when {
    captured -> Stage.CAPTURED
    hasFile -> Stage.RECEIVED
    else -> Stage.WAITING
}

@OptIn(FlowPreview::class)
@Composable
fun DraftScreen(
    store: Store,
    key: String,
    onBack: () -> Unit,
    onKeyChanged: (String) -> Unit,
    onView: (AttachmentKind) -> Unit,
    onReview: (String) -> Unit,
    onTenants: () -> Unit,
    onSettings: () -> Unit,
) {
    val draft = store.draft(key)
    if (draft == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val month = remember(key) { MonthKey.parse(key) }
    val daysInMonth = month.daysInMonth()
    val form = remember(key) {
        val f = draft.fields
        DraftForm(if (f.prev.isBlank() && !f.prevEdited) f.copy(prev = store.previousReadingFor(key).value.plain()) else f)
    }
    val seenReadVersion = remember(key) { intArrayOf(draft.readVersion) }
    var notice by remember(key) { mutableStateOf<String?>(null) }
    var askDelete by remember(key) { mutableStateOf(false) }
    var askCleanup by remember(key) { mutableStateOf(false) }
    // The numbers are hidden behind a read-only summary once captured; these open them for correcting.
    var editInvoice by remember(key) { mutableStateOf(false) }
    var editMeter by remember(key) { mutableStateOf(false) }

    // Everything typed is saved as she goes, so a draft can be left and picked up later.
    // Numbers that automatic reading filled in meanwhile are merged in first so they are never overwritten by stale boxes.
    fun flush() {
        val latest = store.draft(key) ?: return
        if (latest.readVersion != seenReadVersion[0]) {
            form.fillBlanksFrom(latest.fields)
            seenReadVersion[0] = latest.readVersion
        }
        store.saveDraft(latest.copy(fields = form.toFields()))
    }
    LaunchedEffect(key) {
        snapshotFlow { form.toFields() }.drop(1).debounce(500).collect { flush() }
    }
    LaunchedEffect(draft.readVersion) {
        if (draft.readVersion != seenReadVersion[0]) {
            form.fillBlanksFrom(draft.fields)
            seenReadVersion[0] = draft.readVersion
        }
    }
    DisposableEffect(key) { onDispose { flush() } }

    fun pick(kind: AttachmentKind, uri: Uri?) {
        if (uri == null) return
        scope.launch {
            val item = withContext(Dispatchers.IO) { Incoming.copyUri(context, uri) }
            if (item == null) {
                notice = "That file can't be used. Choose a PDF or a photo."
            } else {
                flush()
                // Remember where it came from, so she can be asked about deleting the original once the month is done.
                store.attach(key, kind, item.file, item.mime, SourceFiles.rememberForDelete(context, uri))
                ReaderService.start(store, key, kind)
            }
        }
    }
    val pickInvoice = rememberLauncherForActivityResult(PickDocumentFrom(StartFolder.DOWNLOADS)) { pick(AttachmentKind.INVOICE, it) }
    val pickMeter = rememberLauncherForActivityResult(PickDocumentFrom(StartFolder.WHATSAPP_PICTURES)) { pick(AttachmentKind.METER, it) }

    fun moveTo(target: MonthKey) {
        if (store.occupant(target.key) != Occupant.NONE) {
            notice = "${target.label()} already has an entry."
            return
        }
        if (!form.prevEdited) form.prev = store.previousReadingFor(target.key).value.plain()
        flush()
        if (store.renameDraft(key, target.key)) onKeyChanged(target.key)
    }

    val check = DraftChecker.check(form.toFields(), daysInMonth)
    val invoiceInfo = draft.invoiceInfo
    val meterInfo = draft.meterInfo
    val invoiceReading = ReaderService.isReading(key, AttachmentKind.INVOICE)
    val meterReading = ReaderService.isReading(key, AttachmentKind.METER)
    val invoiceStage = stageOf(draft.invoice != null, check.invoiceCaptured)
    val meterStage = stageOf(draft.meter != null, check.meterCaptured)
    val invoiceFilled = DraftChecker.INVOICE_FIELDS - check.invoiceMissing.size
    // Numbers read automatically must be checked by a person before they count.
    val invoiceChecked = !invoiceInfo.fromReader || invoiceInfo.confirmed
    val meterChecked = !meterInfo.fromReader || meterInfo.confirmed
    val canSend = check.ready && invoiceChecked && meterChecked && !invoiceReading && !meterReading

    // Typing in any box also opens the editor, so the boxes never collapse under her while she types.
    fun inv(set: (String) -> Unit): (String) -> Unit = { v ->
        set(v)
        editInvoice = true
    }
    fun mtr(set: (String) -> Unit): (String) -> Unit = { v ->
        set(v)
        editMeter = true
    }

    // Finishing the draft: the files stay in the app; the originals on the phone (if she picked them from there) are
    // only deleted after she says so.
    fun finish(deleteOriginals: Boolean) {
        val invoiceSource = draft.invoice?.sourceUri
        val meterSource = draft.meter?.sourceUri
        val record = buildRecord(
            store, key, form.toFields(), check, daysInMonth,
            draft.invoice?.copy(sourceUri = null), draft.meter?.copy(sourceUri = null),
        ) ?: return
        store.completeDraft(record)
        val app = context.applicationContext
        DriveBackup.backupAsync(app) // refreshes the Google Drive backup (does nothing until a folder was chosen)
        if (deleteOriginals) {
            // Own scope: this screen is left the moment the month is saved.
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                val failed = listOfNotNull(invoiceSource, meterSource).count { !SourceFiles.delete(app, it) }
                if (failed > 0) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(app, "Some files couldn't be deleted. You can remove them in your Files app.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            listOfNotNull(invoiceSource, meterSource).forEach { runCatching { SourceFiles.forget(app, it) } }
        }
        onReview(key)
    }

    ScreenColumn(title = "Draft", onBack = onBack) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = { moveTo(month.previous()) }) { Text("‹") }
            Text(month.label(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = { moveTo(month.next()) }) { Text("›") }
        }

        Text(
            "Share the municipal invoice and the meter photo to this app. The numbers below come from them: " +
                "check that they match, then tap Review & send.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        notice?.let { Panel(container = MaterialTheme.colorScheme.errorContainer) { Text(it, color = MaterialTheme.colorScheme.onErrorContainer) } }

        // ---------------- 1. invoice ----------------
        Panel {
            SectionTitle("1. Municipal invoice")
            AttachmentArea(store, key, AttachmentKind.INVOICE, draft.invoice, onView) {
                pickInvoice.launch(arrayOf("application/pdf", "image/*"))
            }
            if (invoiceReading) {
                ReadingProgress("Reading the invoice…")
            } else {
                StageBanner(
                    stage = invoiceStage,
                    waiting = "The invoice has not been added yet, so do you want to type in the invoice numbers manually?",
                    received = "Invoice received – numbers not filled in yet ($invoiceFilled of ${DraftChecker.INVOICE_FIELDS})",
                    captured = when {
                        invoiceInfo.fromReader && !invoiceInfo.confirmed -> "Read from the invoice – check each number against it"
                        invoiceInfo.fromReader -> "Checked against the invoice"
                        else -> "Numbers captured – check they match the invoice"
                    },
                )
            }
            check.invoiceProblems.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            ReadNotes(invoiceInfo.notes) { moveTo(it) }

            val showInvoiceFields = !invoiceReading && (editInvoice || invoiceStage == Stage.RECEIVED)
            if (invoiceReading) {
                // wait: the numbers are about to appear
            } else if (invoiceStage == Stage.CAPTURED && !showInvoiceFields) {
                SummaryRow("Property water use", Parse.decimal(form.totalKl)?.let { Fmt.kl(it) })
                SummaryRow("Water", rand(form.water))
                SummaryRow("Water availability charge", rand(form.avail))
                SummaryRow("Sewerage", rand(form.sewer))
                SummaryRow("Sewerage availability", rand(form.sewAvail))
                SummaryRow("Refuse charge", rand(form.refuse))
                SummaryRow("General rates", rand(form.rates))
                if (form.interest.isNotBlank()) SummaryRow("Interest", rand(form.interest))
                if (form.adjustments.isNotBlank()) SummaryRow("Balance brought forward / adjustment", rand(form.adjustments))
                if (form.payable.isNotBlank()) SummaryRow("Total payable on the invoice", rand(form.payable))
                if (invoiceInfo.fromReader && !invoiceInfo.confirmed) {
                    Button(onClick = { store.confirmReading(key, AttachmentKind.INVOICE) }, modifier = Modifier.fillMaxWidth()) {
                        Text("These numbers match the invoice")
                    }
                }
                OutlinedButton(onClick = { editInvoice = true }) { Text("Correct a number") }
            } else if (showInvoiceFields) {
                if (invoiceStage == Stage.RECEIVED) {
                    ReaderHelp(
                        info = invoiceInfo,
                        configured = ReaderService.isConfigured(AttachmentKind.INVOICE),
                        what = "invoice",
                        onTry = { ReaderService.start(store, key, AttachmentKind.INVOICE) },
                        onSettings = onSettings,
                    )
                }
                Text(
                    if (invoiceStage == Stage.CAPTURED) "Change any number that doesn't match the invoice." else "Type the numbers from the invoice, before VAT.",
                    style = MaterialTheme.typography.bodySmall,
                )
                NumberField("Property water use (kL)", form.totalKl, inv { form.totalKl = it }, "The \"CONS.\" number next to the water meter on the invoice")
                NumberField("Water (R)", form.water, inv { form.water = it })
                NumberField("Water availability charge (R)", form.avail, inv { form.avail = it })
                NumberField("Sewerage (R)", form.sewer, inv { form.sewer = it })
                NumberField("Sewerage availability (R)", form.sewAvail, inv { form.sewAvail = it })
                NumberField("Refuse charge (R)", form.refuse, inv { form.refuse = it })
                NumberField("General rates (R)", form.rates, inv { form.rates = it })
                NumberField("Interest (R) - if any", form.interest, inv { form.interest = it })
                NumberField(
                    "Balance brought forward / adjustment (R)",
                    form.adjustments,
                    inv { form.adjustments = it },
                    "Anything left over from last month. Type a minus sign for a credit, e.g. -18.10",
                    KeyboardType.Text,
                )
                NumberField("Total payable on the invoice (R)", form.payable, inv { form.payable = it }, "Optional, but lets the app double-check the numbers")
                if (invoiceStage == Stage.CAPTURED) {
                    Button(onClick = {
                        editInvoice = false
                        if (invoiceInfo.fromReader) store.confirmReading(key, AttachmentKind.INVOICE)
                    }) { Text("Done correcting") }
                }
            } else {
                // Nothing attached and nothing typed: keep the page quiet.
                FilledTonalButton(onClick = { editInvoice = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Type the numbers yourself")
                }
            }
        }

        // ---------------- 2. meter ----------------
        Panel {
            SectionTitle("2. Outbuilding water meter")
            AttachmentArea(store, key, AttachmentKind.METER, draft.meter, onView) { pickMeter.launch(arrayOf("image/*")) }
            if (meterReading) {
                ReadingProgress("Reading the meter photo…")
            } else {
                StageBanner(
                    stage = meterStage,
                    waiting = "The water meter photo has not been added yet, so do you want to type in the reading for this month manually?",
                    received = "Meter photo received – reading not filled in yet",
                    captured = when {
                        meterInfo.fromReader && !meterInfo.confirmed -> "Read from the photo – check it against the photo"
                        meterInfo.fromReader -> "Checked against the photo"
                        else -> "Reading captured – check it matches the photo"
                    },
                )
            }
            check.meterProblems.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            ReadNotes(meterInfo.notes) { moveTo(it) }

            val showMeterFields = !meterReading && (editMeter || meterStage == Stage.RECEIVED)
            if (meterReading) {
                // wait: the reading is about to appear
            } else if (meterStage == Stage.CAPTURED && !showMeterFields) {
                SummaryRow("Previous reading", Parse.decimal(form.prev)?.let { Fmt.kl(it) })
                SummaryRow("Reading now", Parse.decimal(form.curr)?.let { Fmt.kl(it) })
                if (form.replaced) SummaryRow("Meter replaced this month", "Yes")
                check.meterUse?.let { use ->
                    Text(
                        "Outbuilding used ${Fmt.kl(use.kl)} this month",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (meterInfo.fromReader && !meterInfo.confirmed) {
                    Button(onClick = { store.confirmReading(key, AttachmentKind.METER) }, modifier = Modifier.fillMaxWidth()) {
                        Text("This reading matches the photo")
                    }
                }
                OutlinedButton(onClick = { editMeter = true }) { Text("Correct the reading") }
            } else if (showMeterFields) {
                if (meterStage == Stage.RECEIVED) {
                    ReaderHelp(
                        info = meterInfo,
                        configured = ReaderService.isConfigured(AttachmentKind.METER),
                        what = "meter photo",
                        onTry = { ReaderService.start(store, key, AttachmentKind.METER) },
                        onSettings = onSettings,
                    )
                }
                Text(
                    if (meterStage == Stage.CAPTURED) "Change anything that doesn't match the photo." else "Type the digits shown on the meter in the photo.",
                    style = MaterialTheme.typography.bodySmall,
                )
                NumberField(
                    "Previous reading (kL)",
                    form.prev,
                    mtr {
                        form.prev = it
                        form.prevEdited = true
                    },
                    store.previousReadingFor(key).source.takeIf { !form.prevEdited },
                )
                NumberField("Reading now (kL)", form.curr, mtr { form.curr = it }, "The digits shown on the meter in the photo")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Switch(checked = form.replaced, onCheckedChange = { form.replaced = it; editMeter = true })
                    Text("The meter was replaced this month", Modifier.weight(1f))
                }
                if (form.replaced) {
                    NumberField("Last reading of the old meter (kL)", form.oldFinal, mtr { form.oldFinal = it })
                    NumberField("First reading of the new meter (kL)", form.newStart, mtr { form.newStart = it })
                }
                check.meterUse?.let { use ->
                    Text(
                        "Outbuilding used ${Fmt.kl(use.kl)} this month",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (meterStage == Stage.CAPTURED) {
                    Button(onClick = {
                        editMeter = false
                        if (meterInfo.fromReader) store.confirmReading(key, AttachmentKind.METER)
                    }) { Text("Done correcting") }
                }
            } else {
                FilledTonalButton(onClick = { editMeter = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Type the reading yourself")
                }
            }
        }

        Panel {
            SectionTitle("Who lived there")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = form.fullMonth, onCheckedChange = { form.fullMonth = it })
                Text("Both houses were occupied all month", Modifier.weight(1f))
            }
            if (!form.fullMonth) {
                NumberField("Days the outbuilding was occupied (of $daysInMonth)", form.outbDays, { form.outbDays = it }, keyboard = KeyboardType.Number)
                NumberField("Days the forebuilding was occupied (of $daysInMonth)", form.foreDays, { form.foreDays = it }, keyboard = KeyboardType.Number)
                Text(
                    "Empty days on the fixed charges are paid by you. Water and sewerage follow the meters.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        val hasInterest = (Parse.decimal(form.interest)?.signum() ?: 0) != 0
        val hasAdjustment = (Parse.decimal(form.adjustments)?.signum() ?: 0) != 0
        if (hasInterest || hasAdjustment) {
            Panel {
                SectionTitle("Who pays these?")
                if (hasInterest) {
                    Text("Interest")
                    PartyChips(form.interestTo) { form.interestTo = it }
                }
                if (hasAdjustment) {
                    Text("Balance brought forward / adjustment")
                    PartyChips(form.adjustmentsTo) { form.adjustmentsTo = it }
                }
            }
        }

        Panel(container = if (canSend) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
            SectionTitle("Ready to send?")
            ReadyLine("Invoice", "numbers", draft.invoice != null, check.invoiceCaptured, invoiceReading, !invoiceChecked)
            ReadyLine("Meter", "reading", draft.meter != null, check.meterCaptured, meterReading, !meterChecked)
            HorizontalDivider(color = Color.Gray.copy(alpha = 0.25f))
            Text("The statements go to", fontWeight = FontWeight.SemiBold)
            var anyMissing = false
            House.entries.forEach { house ->
                val days = if (house == House.OUTBUILDING) form.outbDays else form.foreDays
                val vacant = !form.fullMonth && days.trim() == "0"
                val tenant = store.tenantFor(house, key)
                if (tenant == null && !vacant) anyMissing = true
                Text(
                    house.label + ": " + when {
                        vacant -> "vacant this month"
                        tenant != null -> tenant.name
                        else -> "no tenant recorded for ${month.label()}"
                    },
                    color = if (tenant == null && !vacant) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (anyMissing) {
                TextButton(onClick = onTenants) { Text("Open Tenants to add them") }
            }
            check.otherProblems.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!canSend) {
                Text(
                    "The statements can be sent once the invoice numbers and the meter reading are captured and checked.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        val hasOriginals = draft.invoice?.sourceUri != null || draft.meter?.sourceUri != null
        Button(
            enabled = canSend,
            onClick = { if (hasOriginals) askCleanup = true else finish(deleteOriginals = false) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Review & send", style = MaterialTheme.typography.titleMedium) }
        TextButton(onClick = { askDelete = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Delete this draft", color = MaterialTheme.colorScheme.error)
        }
    }

    if (askCleanup) {
        val what = listOfNotNull(
            "invoice".takeIf { draft.invoice?.sourceUri != null },
            "meter photo".takeIf { draft.meter?.sourceUri != null },
        ).joinToString(" and the ")
        AlertDialog(
            onDismissRequest = { askCleanup = false },
            title = { Text("Delete the $what from your phone?") },
            text = {
                Text(
                    "Water Splitter keeps its own copy of the $what from now on, so the original files you attached can be removed " +
                        "from your phone to save space. They will be deleted from where you picked them (for example Downloads).",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askCleanup = false
                    finish(deleteOriginals = true)
                }) { Text("Yes, delete them") }
            },
            dismissButton = {
                TextButton(onClick = {
                    askCleanup = false
                    finish(deleteOriginals = false)
                }) { Text("No, keep them") }
            },
        )
    }

    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("Delete the ${month.label()} draft?") },
            text = { Text("The attached invoice, meter photo and everything typed will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    askDelete = false
                    store.deleteDraft(key)
                    onBack()
                }) { Text("Yes, delete") }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun rand(text: String): String? = Parse.decimal(text)?.let { Fmt.rand(it) }

@Composable
private fun ReadingProgress(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
        Text(text, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.tertiary)
    }
}

/** What to tell (and offer) when a file is attached but its numbers have not been captured. */
@Composable
private fun ReaderHelp(info: ReadInfo, configured: Boolean, what: String, onTry: () -> Unit, onSettings: () -> Unit) {
    when {
        info.failure != null -> {
            Text(info.failure, color = MaterialTheme.colorScheme.error)
            if (configured) OutlinedButton(onClick = onTry) { Text("Try reading the $what again") }
        }
        configured -> {
            Text("The $what hasn't been read yet.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onTry) { Text("Read the $what now") }
        }
        else -> {
            Text(
                "Automatic reading is switched off. Add a Claude key in Settings to have the app read the $what for you, or type the numbers below.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onSettings) { Text("Open Settings") }
        }
    }
}

/** The checks run on an automatic reading: warnings first, then reassurance. */
@Composable
private fun ReadNotes(notes: List<ReadingNote>, onMove: (MonthKey) -> Unit) {
    notes.sortedByDescending { it.warning }.forEach { n ->
        if (n.warning) {
            Text("⚠ ${n.text}", color = MaterialTheme.colorScheme.error)
        } else {
            Text("✓ ${n.text}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        n.suggestMonth?.let { m -> TextButton(onClick = { onMove(m) }) { Text("Change this draft to ${m.label()}") } }
    }
}

/** One line saying how far this item has got. */
@Composable
private fun StageBanner(stage: Stage, waiting: String, received: String, captured: String) {
    when (stage) {
        Stage.CAPTURED -> Text("✓ $captured", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        Stage.RECEIVED -> Text("◔ $received", color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold)
        Stage.WAITING -> Text("○ $waiting", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
    }
}

/** A read-only label and value, used to show captured numbers for checking. */
@Composable
private fun SummaryRow(label: String, value: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value ?: "–", fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider(color = Color.Gray.copy(alpha = 0.25f))
}

@Composable
private fun ReadyLine(name: String, noun: String, hasFile: Boolean, captured: Boolean, reading: Boolean, needsCheck: Boolean) {
    val text = when {
        reading -> "◔ $name: being read…"
        captured && needsCheck -> "○ $name: $noun read, please check them against the file"
        captured && hasFile -> "✓ $name: received and $noun captured"
        captured -> "✓ $name: $noun captured (no file attached)"
        hasFile -> "○ $name: received, $noun not captured yet"
        else -> "○ $name: not received yet"
    }
    val good = captured && !needsCheck && !reading
    Text(
        text,
        color = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (good) FontWeight.SemiBold else FontWeight.Normal,
    )
}

@Composable
private fun AttachmentArea(store: Store, key: String, kind: AttachmentKind, att: Attachment?, onView: (AttachmentKind) -> Unit, onPick: () -> Unit) {
    val file = att?.let { store.attachmentFile(key, it) }
    if (att != null && file != null && file.exists()) {
        AttachmentThumb(file, att.mime)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onView(kind) }) { Text("View full size") }
            OutlinedButton(onClick = onPick) { Text("Replace") }
        }
    } else {
        Text(
            if (kind == AttachmentKind.INVOICE) "Share the invoice from Gmail to this app, or attach it here."
            else "Share the meter photo from WhatsApp to this app, or attach it here.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (kind == AttachmentKind.INVOICE) "Attach invoice" else "Attach meter photo", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PartyChips(selected: Party, onSelect: (Party) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Party.OWNER, Party.OUTBUILDING, Party.FOREBUILDING, Party.SPLIT_EQUALLY).forEach { p ->
            FilterChip(selected = selected == p, onClick = { onSelect(p) }, label = { Text(p.label()) })
        }
    }
}

private fun buildRecord(
    store: Store,
    key: String,
    fields: DraftFields,
    check: DraftCheck,
    daysInMonth: Int,
    invoice: Attachment?,
    meter: Attachment?,
): MonthRecord? {
    val p = check.parsed ?: return null
    val outbTenant = store.tenantFor(House.OUTBUILDING, key)
    val foreTenant = store.tenantFor(House.FOREBUILDING, key)
    return MonthRecord(
        key = key,
        rules = RulesVersion.CURRENT,
        totalKl = p.totalKl,
        water = p.water,
        waterAvailability = p.waterAvailability,
        sewerage = p.sewerage,
        sewerageAvailability = p.sewerageAvailability,
        refuse = p.refuse,
        generalRates = p.generalRates,
        interest = p.interest,
        adjustments = p.adjustments,
        payable = p.payable,
        prevReading = p.prevReading,
        currReading = p.currReading,
        outbuildingKl = p.meterUse.kl,
        meterReplaced = fields.replaced,
        oldMeterFinal = p.oldMeterFinal,
        newMeterStart = p.newMeterStart,
        daysInPeriod = daysInMonth,
        outbuildingDays = p.outbuildingDays,
        forebuildingDays = p.forebuildingDays,
        ratesTo = Party.OWNER,
        interestTo = fields.interestTo,
        adjustmentsTo = fields.adjustmentsTo,
        outbuildingTenant = outbTenant?.name.orEmpty(),
        outbuildingWhatsapp = outbTenant?.whatsapp.orEmpty(),
        forebuildingTenant = foreTenant?.name.orEmpty(),
        forebuildingWhatsapp = foreTenant?.whatsapp.orEmpty(),
        importedFromSheet = false,
        invoice = invoice,
        meter = meter,
    )
}
