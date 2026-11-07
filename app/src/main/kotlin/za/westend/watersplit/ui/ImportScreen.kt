package za.westend.watersplit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import za.westend.watersplit.data.AttachmentKind
import za.westend.watersplit.data.Occupant
import za.westend.watersplit.data.Store
import za.westend.watersplit.engine.MonthKey
import za.westend.watersplit.reading.ReaderService
import za.westend.watersplit.share.SharedItem

/** The oldest draft still waiting for this kind of file, otherwise the next month with nothing yet. */
private fun defaultMonth(store: Store, kind: AttachmentKind): MonthKey {
    store.drafts.filter { it.attachment(kind) == null }.minByOrNull { it.key }?.let { return MonthKey.parse(it.key) }
    return store.nextFreeMonth()
}

/**
 * Shown when a file arrives from the share sheet: what is it, which month is it for, then it goes into that month's draft.
 */
@Composable
fun ImportScreen(store: Store, item: SharedItem, remaining: Int, onAdded: (String) -> Unit, onCancel: () -> Unit) {
    var kind by remember(item) {
        mutableStateOf(if (item.mime == "application/pdf") AttachmentKind.INVOICE else AttachmentKind.METER)
    }
    var monthEdited by remember(item) { mutableStateOf(false) }
    var month by remember(item) { mutableStateOf(defaultMonth(store, kind)) }
    var confirm by remember(item) { mutableStateOf<String?>(null) }

    fun chooseKind(k: AttachmentKind) {
        kind = k
        if (!monthEdited) month = defaultMonth(store, k)
    }

    fun add() {
        if (store.occupant(month.key) == Occupant.COMPLETE) store.deleteMonth(month.key)
        if (store.draft(month.key) == null) store.newDraft(month.key)
        store.attach(month.key, kind, item.file, item.mime)
        ReaderService.start(store, month.key, kind) // reads it in the background when a Claude key is saved
        onAdded(month.key)
    }

    val occupant = store.occupant(month.key)
    val existingDraft = store.draft(month.key)

    ScreenColumn(title = "Add to a month") {
        if (remaining > 1) Text("${remaining - 1} more file(s) waiting after this one.", style = MaterialTheme.typography.bodySmall)

        Panel {
            Text(item.displayName, fontWeight = FontWeight.SemiBold)
            AttachmentThumb(item.file, item.mime, height = 220.dp)
        }

        Panel {
            SectionTitle("What is this?")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KindButton("Municipal invoice", kind == AttachmentKind.INVOICE, Modifier.weight(1f)) { chooseKind(AttachmentKind.INVOICE) }
                KindButton("Outbuilding meter photo", kind == AttachmentKind.METER, Modifier.weight(1f)) { chooseKind(AttachmentKind.METER) }
            }
        }

        Panel {
            SectionTitle("Which month is it for?")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { monthEdited = true; month = month.previous() }) { Text("‹") }
                Text(month.label(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = { monthEdited = true; month = month.next() }) { Text("›") }
            }
            Text(
                when (occupant) {
                    Occupant.NONE -> "Nothing saved for this month yet. A new draft will be started."
                    Occupant.DRAFT -> "A draft for this month exists " + draftSummary(existingDraft?.invoice != null, existingDraft?.meter != null) + "."
                    Occupant.COMPLETE -> "This month is already calculated. Adding this will overwrite it."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (occupant == Occupant.COMPLETE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(
            onClick = {
                when {
                    occupant == Occupant.COMPLETE ->
                        confirm = "${month.label()} already exists with its calculated statements. Overwrite the month and all its data with this ${kind.label}?"
                    occupant == Occupant.DRAFT && existingDraft?.attachment(kind) != null ->
                        confirm = "${month.label()} already has a ${kind.label}. Replace it with this one?"
                    else -> add()
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Add to ${month.label()}", style = MaterialTheme.typography.titleMedium) }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }

    confirm?.let { message ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (occupant == Occupant.COMPLETE) "Overwrite ${month.label()}?" else "Replace the ${kind.label}?") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { confirm = null; add() }) {
                    Text(if (occupant == Occupant.COMPLETE) "Yes, overwrite" else "Yes, replace")
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

private fun draftSummary(hasInvoice: Boolean, hasMeter: Boolean): String = when {
    hasInvoice && hasMeter -> "(invoice and meter photo already attached)"
    hasInvoice -> "(invoice attached, no meter photo yet)"
    hasMeter -> "(meter photo attached, no invoice yet)"
    else -> "(nothing attached yet)"
}

@Composable
private fun KindButton(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier.height(64.dp)) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.height(64.dp)) { Text(text) }
    }
}
