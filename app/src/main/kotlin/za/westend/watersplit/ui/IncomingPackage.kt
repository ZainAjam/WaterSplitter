package za.westend.watersplit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import za.westend.watersplit.data.Occupant
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.westend.watersplit.data.Backup
import za.westend.watersplit.data.MonthsFile
import za.westend.watersplit.data.PackageKind
import za.westend.watersplit.data.Packages
import za.westend.watersplit.data.PendingPackage
import za.westend.watersplit.data.Store
import za.westend.watersplit.share.ShareInbox

/**
 * What happens when somebody taps a .wsbackup or .wsmonths file anywhere on the phone: the app opens and asks here.
 * A backup starts a restore; months are imported, asking first before any month that is already here is replaced.
 */
@Composable
fun IncomingPackageDialogs(store: Store, inbox: ShareInbox) {
    val pkg = inbox.pending ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember(pkg) { mutableStateOf<String?>(null) }

    fun close() {
        pkg.file.delete()
        inbox.pending = null
    }

    result?.let { message ->
        AlertDialog(
            onDismissRequest = { close() },
            title = { Text("Water Splitter") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { close() }) { Text("OK") } },
        )
        return
    }

    when (pkg.kind) {
        PackageKind.BACKUP -> AlertDialog(
            onDismissRequest = { close() },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "Everything on this phone (months, drafts, tenants and attached files) will be replaced with what is in the backup. " +
                        "The current data is set aside first, in case you need it.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching { pkg.file.inputStream().use { Backup.restore(context, it) } }
                                .getOrElse { Backup.RestoreResult.Failed("The file couldn't be opened.") }
                        }
                        result = when (outcome) {
                            is Backup.RestoreResult.Done -> {
                                store.reloadFromDisk()
                                "Backup restored."
                            }
                            is Backup.RestoreResult.Failed -> outcome.reason
                        }
                    }
                }) { Text("Yes, restore") }
            },
            dismissButton = { TextButton(onClick = { close() }) { Text("Cancel") } },
        )
        PackageKind.MONTHS -> MonthsImport(store, pkg, onResult = { result = it }, onClose = { close() })
    }
}

/** One month (finished or still in progress) found in a months file. */
private class Entry(val key: String, val label: String, val month: za.westend.watersplit.data.MonthRecord?, val draft: za.westend.watersplit.data.Draft?)

@Composable
private fun MonthsImport(store: Store, pkg: PendingPackage, onResult: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var parsed by remember(pkg) { mutableStateOf<MonthsFile?>(null) }
    var failed by remember(pkg) { mutableStateOf(false) }
    LaunchedEffect(pkg) {
        val read = withContext(Dispatchers.IO) { Packages.readMonths(context, pkg.file) }
        if (read == null || (read.months.isEmpty() && read.drafts.isEmpty())) failed = true else parsed = read
    }
    if (failed) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Water Splitter") },
            text = { Text("That file couldn't be read as shared months.") },
            confirmButton = { TextButton(onClick = onClose) { Text("OK") } },
        )
        return
    }
    val file = parsed ?: return
    val months = (
        file.months.map { Entry(it.key, it.label, it, null) } +
            file.drafts.map { Entry(it.key, it.label + " (in progress)", null, it) }
        ).sortedBy { it.key }
    // Which months to bring in. A month that is new starts ticked; one that is already here starts as "Skip" until she says "Replace".
    val take = remember(pkg) { mutableStateMapOf<String, Boolean>().also { m -> months.forEach { m[it.key] = store.occupant(it.key) == Occupant.NONE } } }
    val count = months.count { take[it.key] == true }
    val replacing = months.count { take[it.key] == true && store.occupant(it.key) != Occupant.NONE }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Choose the months to import") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This file has ${months.size} ${if (months.size == 1) "month" else "months"}.")
                val existing = months.filter { store.occupant(it.key) != Occupant.NONE }
                if (existing.isNotEmpty()) {
                    Text("${existing.size} of them ${if (existing.size == 1) "is" else "are"} already in the app:", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { existing.forEach { take[it.key] = false } }) { Text("Skip all") }
                        OutlinedButton(onClick = { existing.forEach { take[it.key] = true } }) { Text("Replace all") }
                    }
                }
                months.forEach { m ->
                    val here = store.occupant(m.key)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (here == Occupant.NONE) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = take[m.key] == true, onCheckedChange = { take[m.key] = it })
                                Column {
                                    Text(m.label, fontWeight = FontWeight.SemiBold)
                                    Text("New", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        } else {
                            Text(m.label, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (here == Occupant.DRAFT) "Already here, still in progress. Replacing it removes that draft."
                                else "Already in the app.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = take[m.key] != true, onClick = { take[m.key] = false }, label = { Text("Skip") })
                                FilterChip(selected = take[m.key] == true, onClick = { take[m.key] = true }, label = { Text("Replace mine") })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = count > 0,
                onClick = {
                    months.filter { take[it.key] == true }.forEach { e ->
                        val folder = java.io.File(file.dir, e.key)
                        e.month?.let { store.importMonth(it, folder) }
                        e.draft?.let { store.importDraft(it, folder) }
                    }
                    onResult(
                        "Imported $count ${if (count == 1) "month" else "months"}" +
                            (if (replacing > 0) " ($replacing replaced)." else "."),
                    )
                },
            ) { Text(if (count == 0) "Import" else "Import $count") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
