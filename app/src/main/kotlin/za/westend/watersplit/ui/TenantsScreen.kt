package za.westend.watersplit.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

/** Who lived in each house, and when. Tap a tenant to change their details or dates. */
@Composable
fun TenantsScreen(store: Store, onBack: () -> Unit, onEdit: (id: String?, house: House) -> Unit) {
    val now = remember { MonthKey.current() }
    ScreenColumn(title = "Tenants", onBack = onBack) {
        Text(
            "Each month's statements go to the tenant recorded for that month, so keep these dates right when someone moves in or out.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        House.entries.forEach { house ->
            val list = TenantBook.forHouse(store.tenancies, house)
            val current = TenantBook.tenantFor(store.tenancies, house, now)
            Panel {
                SectionTitle(house.label)
                Text(
                    if (current != null) "Now: ${current.name}" else "No tenant recorded for ${now.label()}",
                    color = if (current != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                list.forEach { t -> TenancyCard(t, now) { onEdit(t.id, house) } }
                Button(onClick = { onEdit(null, house) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (list.isEmpty()) "Add the ${house.label.lowercase()} tenant" else "Add another tenant")
                }
            }
        }
    }
}

@Composable
fun TenancyCard(t: Tenancy, now: MonthKey, onClick: () -> Unit) {
    val number = Phone.whatsappNumber(t.whatsapp)
    val months = t.monthsUpTo(now)
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(t.name, fontWeight = FontWeight.SemiBold)
            Text(t.rangeLabel())
            Text(
                (if (months == 1) "1 month" else "$months months") + if (t.to == null) " so far" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                when {
                    number != null -> "WhatsApp: +$number"
                    t.whatsapp.isBlank() -> "No WhatsApp number saved"
                    else -> "WhatsApp: ${t.whatsapp} (doesn't look like a valid number)"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (number == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Add a tenant, or change / remove an existing one. [id] is null for a new tenant. */
@Composable
fun TenantEditScreen(store: Store, id: String?, house: House, earlier: Boolean = false, onDone: () -> Unit) {
    val context = LocalContext.current
    val existing = remember(id) { id?.let { i -> store.tenancies.firstOrNull { it.id == i } } }
    // An "earlier tenant" starts out as someone who moved out the month before the current tenant arrived.
    val earlierLast = remember(id) {
        (TenantBook.forHouse(store.tenancies, house).firstOrNull { it.to == null }?.from ?: MonthKey.current()).previous()
    }
    var name by remember(id) { mutableStateOf(existing?.name ?: "") }
    var whatsapp by remember(id) { mutableStateOf(existing?.whatsapp ?: "") }
    var from by remember(id) { mutableStateOf(existing?.from ?: if (earlier) earlierLast else MonthKey.current()) }
    var stillThere by remember(id) { mutableStateOf(if (existing == null && earlier) false else existing?.to == null) }
    var last by remember(id) { mutableStateOf(existing?.to ?: if (earlier) earlierLast else MonthKey.current()) }
    var problem by remember(id) { mutableStateOf<String?>(null) }
    var askDelete by remember(id) { mutableStateOf(false) }

    val number = Phone.whatsappNumber(whatsapp)

    ScreenColumn(
        title = when {
            existing != null -> "Tenant details"
            earlier -> "Earlier ${house.label.lowercase()} tenant"
            else -> "Add ${house.label.lowercase()} tenant"
        },
        onBack = onDone,
    ) {
        Panel {
            SectionTitle("Who")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = whatsapp,
                onValueChange = { whatsapp = it },
                label = { Text("WhatsApp number") },
                singleLine = true,
                isError = whatsapp.isNotBlank() && number == null,
                supportingText = {
                    Text(
                        when {
                            whatsapp.isBlank() -> "e.g. 082 123 4567"
                            number == null -> "This doesn't look like a valid number"
                            else -> "Will send to +$number"
                        },
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            FindInContactsButton { contactName, contactNumber ->
                whatsapp = contactNumber
                if (name.isBlank()) name = contactName
            }
        }

        Panel {
            SectionTitle("Which months did they live in the ${house.label.lowercase()}?")
            Text("First month", style = MaterialTheme.typography.bodySmall)
            MonthStepper(from) { from = it }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = stillThere, onCheckedChange = { stillThere = it })
                Text("Still lives there", Modifier.weight(1f))
            }
            if (!stillThere) {
                Text("Last month", style = MaterialTheme.typography.bodySmall)
                MonthStepper(last) { last = it }
            }
            Text(
                "A month counts for the tenant who was there in it. If someone moves in part-way through a month, record the " +
                    "month for whoever should be invoiced.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        problem?.let { Panel(container = MaterialTheme.colorScheme.errorContainer) { Text(it, color = MaterialTheme.colorScheme.onErrorContainer) } }

        Button(
            onClick = {
                val candidate = Tenancy(
                    id = existing?.id ?: store.newTenancyId(),
                    house = house,
                    name = name.trim(),
                    whatsapp = whatsapp.trim(),
                    from = from,
                    to = if (stillThere) null else last,
                )
                when (val r = store.saveTenancy(candidate)) {
                    is TenantSave.Rejected -> problem = r.message
                    is TenantSave.Saved -> {
                        r.note?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                        onDone()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Save", style = MaterialTheme.typography.titleMedium) }

        if (existing != null) {
            TextButton(onClick = { askDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Remove this tenant", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (askDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("Remove ${existing.name}?") },
            text = { Text("Their months will show \"tenant not recorded\" until another tenant is added for those months. Statements already made are not changed.") },
            confirmButton = {
                TextButton(onClick = {
                    askDelete = false
                    store.deleteTenancy(existing.id)
                    onDone()
                }) { Text("Yes, remove") }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Cancel") } },
        )
    }
}
