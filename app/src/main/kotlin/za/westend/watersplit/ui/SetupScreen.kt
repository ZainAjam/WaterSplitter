package za.westend.watersplit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import za.westend.watersplit.update.Updater
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import za.westend.watersplit.data.Store
import za.westend.watersplit.engine.House
import za.westend.watersplit.engine.MonthKey
import za.westend.watersplit.engine.Phone
import za.westend.watersplit.engine.Tenancy
import za.westend.watersplit.engine.TenantBook
import za.westend.watersplit.engine.TenantSave

const val SETUP_STEPS = 6

/** What is typed for one house in step 1, before it is saved. */
private class TenantForm(open: Tenancy?) {
    val id: String? = open?.id
    var lives by mutableStateOf(true)
    var name by mutableStateOf(open?.name ?: "")
    var whatsapp by mutableStateOf(open?.whatsapp ?: "")
    var from by mutableStateOf(open?.from ?: MonthKey.current())
}

/**
 * The first-time wizard: 1 allow updates, 2 restore a backup, 3 current tenants, 4 earlier tenants (optional), 5 reminders, 6 Claude key.
 * The tenants are saved when she presses Next, so going back and forth loses nothing.
 */
@Composable
fun SetupScreen(
    store: Store,
    step: Int,
    onStep: (Int) -> Unit,
    onAddEarlier: (House) -> Unit,
    onEditTenancy: (id: String, house: House) -> Unit,
    onFinish: () -> Unit,
) {
    // Bumped after a backup is restored so the typed-in forms are rebuilt from the restored tenants.
    var generation by remember { mutableIntStateOf(0) }
    val forms = remember(generation) {
        House.entries.associateWith { h -> TenantForm(TenantBook.forHouse(store.tenancies, h).firstOrNull { it.to == null }) }
    }
    var problem by remember { mutableStateOf<String?>(null) }
    var restoreMessage by remember { mutableStateOf<String?>(null) }
    val restore = rememberRestoreFromBackup(
        store,
        confirm = false,
        onRestored = {
            problem = null
            // The backup brings the tenants, months and settings; the Claude key is never in a backup, so go on to that step.
            if (store.tenancies.isNotEmpty()) {
                onStep(5)
            } else {
                generation++
                onStep(2)
            }
        },
        onResult = { restoreMessage = it },
    )
    val now = remember { MonthKey.current() }
    val context = LocalContext.current

    fun saveCurrentTenants(): Boolean {
        problem = null
        for ((house, f) in forms) {
            if (!f.lives) continue
            if (f.name.isBlank()) {
                problem = "Please type the ${house.label.lowercase()} tenant's name, or switch off \"Someone lives here now\"."
                return false
            }
            if (Phone.whatsappNumber(f.whatsapp) == null) {
                problem = "Please add a valid WhatsApp number for ${f.name.trim()} (for example 082 123 4567)."
                return false
            }
        }
        for ((house, f) in forms) {
            val open = TenantBook.forHouse(store.tenancies, house).firstOrNull { it.to == null }
            if (!f.lives) {
                open?.let { store.deleteTenancy(it.id) }
                continue
            }
            val candidate = Tenancy(
                id = open?.id ?: store.newTenancyId(),
                house = house,
                name = f.name.trim(),
                whatsapp = f.whatsapp.trim(),
                from = f.from,
                to = null,
            )
            val r = store.saveTenancy(candidate)
            if (r is TenantSave.Rejected) {
                problem = r.message
                return false
            }
        }
        return true
    }

    ScreenColumn(title = "Set up Water Splitter", onBack = if (step > 0) ({ onStep(step - 1) }) else null) {
        Text(
            "Step ${step + 1} of $SETUP_STEPS",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        when (step) {
            0 -> {
                Text(
                    "Water Splitter updates itself from the internet. For that, Android needs your OK to let this app install its own updates.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Panel {
                    SectionTitle("Allow updates")
                    if (Updater.installAllowed) {
                        Text("✓ Updates are allowed.", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    } else {
                        Text("1. Tap the button below.")
                        Text("2. Switch on \"Allow from this source\".")
                        Text("3. Press Back to return here.")
                        Button(
                            onClick = { context.startActivity(Updater.allowInstallIntent(context)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Allow updates") }
                    }
                }
                if (!Updater.installAllowed) {
                    Text(
                        "You can skip this now. A reminder stays at the top of the main screen until it is allowed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = { onStep(1) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(if (Updater.installAllowed) "Next" else "Skip for now", style = MaterialTheme.typography.titleMedium)
                }
            }

            1 -> {
                Text(
                    "Moving from another phone? If you saved a backup there (Settings, App Settings, Backup), put it back here and skip " +
                        "typing everything in.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Panel {
                    SectionTitle("Restore a backup")
                    Button(onClick = restore, modifier = Modifier.fillMaxWidth()) { Text("Restore from a backup") }
                    restoreMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
                }
                OutlinedButton(onClick = { onStep(2) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("I don't have a backup", style = MaterialTheme.typography.titleMedium)
                }
            }

            2 -> {
                Text(
                    "Who lives in the two houses now? Statements are sent to the WhatsApp number you give here.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                House.entries.forEach { house ->
                    val f = forms.getValue(house)
                    Panel {
                        SectionTitle(house.label)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Switch(checked = f.lives, onCheckedChange = { f.lives = it })
                            Text("Someone lives here now", Modifier.weight(1f))
                        }
                        if (f.lives) {
                            val number = Phone.whatsappNumber(f.whatsapp)
                            OutlinedTextField(
                                value = f.name,
                                onValueChange = { f.name = it },
                                label = { Text("Tenant's name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = f.whatsapp,
                                onValueChange = { f.whatsapp = it },
                                label = { Text("WhatsApp number") },
                                singleLine = true,
                                isError = f.whatsapp.isNotBlank() && number == null,
                                supportingText = {
                                    Text(
                                        when {
                                            f.whatsapp.isBlank() -> "e.g. 082 123 4567"
                                            number == null -> "This doesn't look like a valid number"
                                            else -> "Will send to +$number"
                                        },
                                    )
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            FindInContactsButton { contactName, contactNumber ->
                                f.whatsapp = contactNumber
                                if (f.name.isBlank()) f.name = contactName
                            }
                            Text("Month they moved in", style = MaterialTheme.typography.bodySmall)
                            MonthStepper(f.from) { f.from = it }
                            Text(
                                "Statements for months before this one won't show a tenant name. If they have lived there longer, step back to the first month.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Text("This house will show as vacant until you add a tenant in Settings.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                problem?.let { Panel(container = MaterialTheme.colorScheme.errorContainer) { Text(it, color = MaterialTheme.colorScheme.onErrorContainer) } }
                Button(
                    onClick = { if (saveCurrentTenants()) onStep(3) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("Next", style = MaterialTheme.typography.titleMedium) }
            }

            3 -> {
                Text(
                    "If you want the earlier months to show who was invoiced, add previous tenants too. This is optional: " +
                        "you can add them any time under Settings, Service Settings, Tenants.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                House.entries.forEach { house ->
                    val earlierOnes = TenantBook.forHouse(store.tenancies, house).filter { it.to != null }
                    Panel {
                        SectionTitle(house.label)
                        if (earlierOnes.isEmpty()) {
                            Text("No earlier tenants added.", style = MaterialTheme.typography.bodySmall)
                        }
                        earlierOnes.forEach { t -> TenancyCard(t, now) { onEditTenancy(t.id, house) } }
                        OutlinedButton(onClick = { onAddEarlier(house) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Add an earlier tenant")
                        }
                    }
                }
                Button(onClick = { onStep(4) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Next", style = MaterialTheme.typography.titleMedium)
                }
            }

            4 -> {
                Text(
                    "The municipal invoice arrives by email around the 24th. Choose the day of the month you want a reminder from.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                RemindersPanel(store)
                Button(onClick = { onStep(5) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Next", style = MaterialTheme.typography.titleMedium)
                }
            }

            else -> {
                Text(
                    "To read the municipal invoice and the meter photo for you, the app needs a Claude API key. " +
                        "You can also skip this now and type the numbers yourself, or add the key later under Settings, App Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ClaudeKeyPanel()
                Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Finish", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "You can change everything you entered under Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (step in 3..4) {
            TextButton(onClick = { onStep(step + 1) }, modifier = Modifier.fillMaxWidth()) {
                Text("Skip this step", fontWeight = FontWeight.Normal)
            }
        }
    }
}
