package za.westend.watersplit.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.core.content.ContextCompat
import za.westend.watersplit.reminders.ReminderNotifier
import za.westend.watersplit.reminders.ReminderScheduler
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.westend.watersplit.data.Backup
import za.westend.watersplit.data.Store
import za.westend.watersplit.engine.Versions
import za.westend.watersplit.update.UpdateFailure
import za.westend.watersplit.update.Updater
import java.time.LocalDate
import za.westend.watersplit.engine.Parse
import za.westend.watersplit.reading.ReaderService

/** Check for a newer version published on GitHub, download it and hand it to Android's installer. */
@Composable
private fun UpdatesPanel() {
    val context = LocalContext.current
    val release = Updater.available
    val progress = Updater.progress

    Panel {
        SectionTitle("App updates")
        Text("Installed version: ${Updater.installedVersion()}")
        if (!Updater.isConfigured) {
            Text("Updates aren't set up for this build yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            OutlinedButton(
                onClick = { Updater.check(context) },
                enabled = !Updater.checking && progress == null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (Updater.checking) "Checking…" else "Check for updates") }
            Updater.lastResult?.let { Text(it, fontWeight = FontWeight.SemiBold) }

            if (release != null) {
                Text("Version ${release.version} (${Versions.size(release.sizeBytes)})", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                if (release.notes.isNotBlank()) Text(release.notes.take(400), style = MaterialTheme.typography.bodySmall)
                UpdateActions()
            }
            if (!Updater.installAllowed) InstallPermissionActions()
        }
    }
}

/** "Download and install" (with progress and any problem). Without Android's permission it sends her to that setting instead. */
@Composable
fun UpdateActions() {
    val context = LocalContext.current
    val progress = Updater.progress
    if (progress != null) {
        if (progress >= 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        Button(
            onClick = {
                if (Updater.installAllowed) Updater.downloadAndInstall(context) else context.startActivity(Updater.allowInstallIntent(context))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (Updater.installAllowed) "Download and install" else "Allow installing, then download") }
    }
    Updater.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

/** Explains Android's "install unknown apps" switch and opens it. */
@Composable
fun InstallPermissionActions() {
    val context = LocalContext.current
    Text(
        "Android needs your OK before this app can install its own updates. Tap below, switch on \"Allow from this source\", then press Back.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedButton(onClick = { context.startActivity(Updater.allowInstallIntent(context)) }, modifier = Modifier.fillMaxWidth()) {
        Text("Allow installing updates")
    }
}
/** A notification when the invoice should have arrived, or when a month is still waiting. */
@Composable
fun RemindersPanel(store: Store) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    val on = store.settings.remindersOn

    fun turnOn() {
        store.updateSettings(store.settings.copy(remindersOn = true))
        ReminderScheduler.apply(context, true)
    }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            turnOn()
            message = null
        } else {
            message = "Notifications are blocked for this app. Allow them in the phone's settings to get reminders."
        }
    }

    Panel {
        SectionTitle("Reminders")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(
                checked = on,
                onCheckedChange = { wantOn ->
                    if (!wantOn) {
                        store.updateSettings(store.settings.copy(remindersOn = false))
                        ReminderScheduler.apply(context, false)
                        message = null
                    } else if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        turnOn()
                        message = null
                    }
                },
            )
            Text("Remind me about the invoice", Modifier.weight(1f))
        }
        var dayText by remember { mutableStateOf(store.settings.reminderDay.toString()) }
        val dayOk = dayText.toIntOrNull()?.let { it in 1..31 } == true
        NumberField(
            label = "Remind me on day (1-31) of the month",
            value = dayText,
            onChange = { t ->
                val digits = t.filter { it.isDigit() }.take(2)
                dayText = digits
                digits.toIntOrNull()?.takeIf { it in 1..31 }?.let { store.updateSettings(store.settings.copy(reminderDay = it)) }
            },
            hint = if (dayOk) {
                "The municipal invoice usually arrives around the 24th. From day $dayText, and while a month is still " +
                    "waiting for its invoice, meter photo or checking, the app sends a notification (at most every other day)."
            } else {
                "Type a day between 1 and 31."
            },
            keyboard = KeyboardType.Number,
            isError = !dayOk,
        )
        if (on) {
            OutlinedButton(
                onClick = {
                    val sent = ReminderNotifier.sendTest(context, store)
                    message = sent?.let { "Sent: $it" } ?: "Notifications are switched off for this app on the phone."
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Send a test reminder now") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
    }
}

/** Save a copy of everything to a file (e.g. in Google Drive), or put such a copy back. */
@Composable
private fun BackupPanel(store: Store) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val stamp = remember { LocalDate.now().toString() }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val out = context.contentResolver.openOutputStream(uri) ?: error("no output")
                        Backup.write(context, out)
                    }
                }
                message = result.fold({ "Backup saved as one file." }, { "The backup couldn't be saved. Please try another location." })
            }
        }
    }
    val restore = rememberRestoreFromBackup(store, confirm = true) { message = it }

    Panel {
        SectionTitle("Backup")
        Text(
            "Your months, tenants and the attached invoices and photos live on this phone. Android also backs them up to your " +
                "Google account on its own, but it's wise to save a copy yourself now and then (for example to Google Drive). " +
                "The Claude key is not included.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = { save.launch("water-splitter-backup-$stamp.zip") }, modifier = Modifier.fillMaxWidth()) { Text("Save a backup") }
        OutlinedButton(onClick = restore, modifier = Modifier.fillMaxWidth()) { Text("Restore from a backup") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
    }
}

/**
 * Lets the person pick a backup file and put it back. Returns the function that opens the file picker.
 * [confirm] asks first (on a phone that already has data); [onResult] gets a sentence to show, and [onRestored] fires on success.
 */
@Composable
fun rememberRestoreFromBackup(
    store: Store,
    confirm: Boolean,
    onRestored: () -> Unit = {},
    onResult: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Uri?>(null) }

    fun run(uri: Uri) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)!!.use { Backup.restore(context, it) } }
                    .getOrElse { Backup.RestoreResult.Failed("The file couldn't be opened.") }
            }
            when (result) {
                is Backup.RestoreResult.Done -> {
                    store.reloadFromDisk()
                    onResult("Backup restored.")
                    onRestored()
                }
                is Backup.RestoreResult.Failed -> onResult(result.reason)
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) if (confirm) pending = uri else run(uri)
    }

    pending?.let { uri ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Restore this backup?") },
            text = { Text("Everything on this phone (months, drafts, tenants and attached files) will be replaced with what is in the backup. The current data is set aside first, in case you need it.") },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    run(uri)
                }) { Text("Yes, restore") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
    return { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
}
/** Save, show or remove the Claude API key that lets the app read invoices and meter photos. */
@Composable
fun ClaudeKeyPanel(onChanged: () -> Unit = {}) {
    val keys = ReaderService.keyStore
    var masked by remember { mutableStateOf(keys.masked()) }
    var newKey by remember { mutableStateOf("") }
    var keyProblem by remember { mutableStateOf<String?>(null) }

    Panel {
        SectionTitle("Automatic reading (Claude key)")
        Text(
            "The app can read the municipal invoice and the meter photo for you, using Claude. When switched on, the file is sent " +
                "over the internet to Anthropic (the company behind Claude) to be read. That costs roughly R1-2 a month. " +
                "Without a key, you type the numbers in yourself.",
            style = MaterialTheme.typography.bodyMedium,
        )
        val saved = masked
        if (saved != null) {
            Text("A Claude key is saved on this phone: $saved", color = MaterialTheme.colorScheme.primary)
            OutlinedButton(
                onClick = {
                    keys.clear()
                    masked = null
                    onChanged()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Remove the key (switch reading off)") }
        } else {
            OutlinedTextField(
                value = newKey,
                onValueChange = {
                    newKey = it
                    keyProblem = null
                },
                label = { Text("Claude API key") },
                singleLine = true,
                isError = keyProblem != null,
                supportingText = { Text(keyProblem ?: "Starts with sk-ant-. Create one at console.anthropic.com") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val k = newKey.trim()
                    if (!k.startsWith("sk-ant-") || k.length < 30) {
                        keyProblem = "That doesn't look like a Claude API key."
                    } else {
                        keys.set(k)
                        masked = keys.masked()
                        newKey = ""
                        onChanged()
                    }
                },
                enabled = newKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save the key") }
        }
        Text(
            "The key is kept encrypted on this phone only. It is never shown again in full, never sent anywhere except to Claude, " +
                "and never put in the app's data.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The two groups of settings. */
@Composable
fun SettingsScreen(onBack: () -> Unit, onTenants: () -> Unit, onService: () -> Unit, onApp: () -> Unit) {
    ScreenColumn(title = "Settings", onBack = onBack) {
        Panel {
            SectionTitle("Tenants")
            Text("Names, WhatsApp numbers and the months each tenant lived in each house.")
            Button(onClick = onTenants, modifier = Modifier.fillMaxWidth()) { Text("Open Tenants") }
        }
        Panel {
            SectionTitle("Service Settings")
            Text("The municipal account and meter, what appears on the statements and the last water meter reading.")
            Button(onClick = onService, modifier = Modifier.fillMaxWidth()) { Text("Open Service Settings") }
        }
        Panel {
            SectionTitle("App Settings")
            Text(
                if (Updater.available != null) {
                    "The Claude key, reminders, backup and app updates. A new version of the app is available."
                } else {
                    "The Claude key, reminders, backup and app updates."
                },
            )
            Button(onClick = onApp, modifier = Modifier.fillMaxWidth()) { Text("Open App Settings") }
        }
    }
}

/** Everything about the property and the municipal service: tenants, account, meter and what the statements say. */
@Composable
fun ServiceSettingsScreen(store: Store, onBack: () -> Unit) {
    val s = store.settings
    var address by remember { mutableStateOf(s.propertyAddress) }
    var issuer by remember { mutableStateOf(s.issuerName) }
    var lastReading by remember { mutableStateOf(s.lastReading.stripTrailingZeros().toPlainString()) }
    var account by remember { mutableStateOf(s.municipalAccount) }
    var serial by remember { mutableStateOf(s.meterSerial) }

    ScreenColumn(title = "Service Settings", onBack = onBack) {
        Panel {
            SectionTitle("Municipal account")
            OutlinedTextField(
                value = account,
                onValueChange = { account = it },
                label = { Text("Municipal account number") },
                singleLine = true,
                supportingText = { Text("An invoice for a different account is flagged.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Panel {
            SectionTitle("Outbuilding meter")
            OutlinedTextField(
                value = serial,
                onValueChange = { serial = it },
                label = { Text("Meter serial number") },
                singleLine = true,
                supportingText = { Text("Printed on the meter's label. A photo of a different meter is flagged.") },
                modifier = Modifier.fillMaxWidth(),
            )
            NumberField(
                label = "Last known reading (kL)",
                value = lastReading,
                onChange = { lastReading = it },
                hint = "Used as the starting reading until a month is saved in the app.",
            )
        }

        Panel {
            SectionTitle("On the statements")
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Property address") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = issuer,
                onValueChange = { issuer = it },
                label = { Text("From (your name, optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(
            onClick = {
                store.updateSettings(
                    store.settings.copy(
                        propertyAddress = address.trim(),
                        issuerName = issuer.trim(),
                        lastReading = Parse.decimal(lastReading) ?: s.lastReading,
                        municipalAccount = account.trim(),
                        meterSerial = serial.trim(),
                    ),
                )
                onBack()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
    }
}

/** How the app itself behaves: Claude key, reminders, backup and updates. Each part saves as soon as it is changed. */
@Composable
fun AppSettingsScreen(store: Store, onBack: () -> Unit) {
    ScreenColumn(title = "App Settings", onBack = onBack) {
        ClaudeKeyPanel()
        RemindersPanel(store)
        BackupPanel(store)
        UpdatesPanel()
    }
}
