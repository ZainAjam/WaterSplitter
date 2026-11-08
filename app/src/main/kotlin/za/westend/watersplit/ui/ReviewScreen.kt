package za.westend.watersplit.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import za.westend.watersplit.data.AttachmentKind
import za.westend.watersplit.data.Store
import za.westend.watersplit.engine.Fmt
import za.westend.watersplit.engine.House
import za.westend.watersplit.engine.PartyStatement
import za.westend.watersplit.engine.Phone
import za.westend.watersplit.share.StatementImage
import za.westend.watersplit.share.WhatsApp
import java.math.BigDecimal
import java.math.RoundingMode

private class SendRequest(val house: House, val name: String, val statement: PartyStatement, val phone: String, val remember: Boolean)

@Composable
fun ReviewScreen(
    store: Store,
    key: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onView: (AttachmentKind) -> Unit,
    onPreview: (House) -> Unit,
    onTenants: () -> Unit,
) {
    val record = store.month(key)
    if (record == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val result = remember(record) { record.calculate() }
    val context = LocalContext.current
    val settings = store.settings

    // The tenant recorded for this month; older months fall back to the name saved with them.
    val outbTenant = store.tenantFor(House.OUTBUILDING, key)
    val foreTenant = store.tenantFor(House.FOREBUILDING, key)
    val outbName = outbTenant?.name ?: record.outbuildingTenant
    val foreName = foreTenant?.name ?: record.forebuildingTenant
    val outbSaved = outbTenant?.whatsapp ?: record.outbuildingWhatsapp
    val foreSaved = foreTenant?.whatsapp ?: record.forebuildingWhatsapp
    var outbPhone by remember(key) { mutableStateOf(outbSaved) }
    var forePhone by remember(key) { mutableStateOf(foreSaved) }
    var outbRemember by remember(key) { mutableStateOf(false) }
    var foreRemember by remember(key) { mutableStateOf(false) }
    var notice by remember(key) { mutableStateOf<String?>(null) }
    var pending by remember(key) { mutableStateOf<SendRequest?>(null) }

    fun send(r: SendRequest) {
        try {
            val image = StatementImage.create(context, r.house, r.name, record.label, record.key, r.statement, settings)
            val message = "Water, sewerage & refuse statement for ${record.label}: ${Fmt.rand(r.statement.total)}"
            val opened = WhatsApp.send(context, image, r.phone, message)
            notice = if (opened) null else "WhatsApp isn't installed on this phone, so the share menu was opened instead."
            if (r.remember && r.phone.isNotBlank()) {
                val tenant = if (r.house == House.OUTBUILDING) outbTenant else foreTenant
                tenant?.let { store.saveTenancy(it.copy(whatsapp = r.phone.trim())) }
            }
        } catch (e: ActivityNotFoundException) {
            notice = "Couldn't open sharing on this phone."
        }
    }

    fun heading(house: House, name: String, days: Int) = house.label + when {
        days == 0 -> " - vacant"
        name.isBlank() -> " - tenant not recorded"
        else -> " - $name"
    }

    ScreenColumn(title = record.label, onBack = onBack) {
        if (record.importedFromSheet) {
            Panel { Text("This month comes from the old spreadsheet and was worked out with the old rules.") }
        }

        if (result.warnings.isNotEmpty()) {
            Panel(container = MaterialTheme.colorScheme.errorContainer) {
                Text("Please check", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                result.warnings.forEach { Text(it.message, color = MaterialTheme.colorScheme.onErrorContainer) }
            }
        }

        Panel {
            val pct = result.outbuildingFraction.multiply(BigDecimal(100)).setScale(1, RoundingMode.HALF_UP).toPlainString()
            Text(
                "Outbuilding used ${Fmt.kl(record.outbuildingKl)} of the property's ${record.totalKl.stripTrailingZeros().toPlainString()} kL ($pct%)",
            )
            if (record.invoice != null || record.meter != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (record.invoice != null) OutlinedButton(onClick = { onView(AttachmentKind.INVOICE) }) { Text("View invoice") }
                    if (record.meter != null) OutlinedButton(onClick = { onView(AttachmentKind.METER) }) { Text("View meter photo") }
                }
            }
        }

        StatementCard(
            heading = heading(House.OUTBUILDING, outbName, record.outbuildingDays),
            statement = result.outbuilding,
            phone = outbPhone,
            onPhoneChange = { outbPhone = it },
            canRemember = outbTenant != null && outbPhone.trim() != outbSaved.trim() && Phone.whatsappNumber(outbPhone) != null,
            rememberLabel = "Save this number for ${outbName.ifBlank { "this tenant" }}",
            remember = outbRemember,
            onRememberChange = { outbRemember = it },
            showTenantHint = outbTenant == null && record.outbuildingDays > 0 && outbName.isBlank(),
            onTenants = onTenants,
            onPreview = { onPreview(House.OUTBUILDING) },
            onSend = { pending = SendRequest(House.OUTBUILDING, outbName, result.outbuilding, outbPhone, outbRemember) },
        )
        StatementCard(
            heading = heading(House.FOREBUILDING, foreName, record.forebuildingDays),
            statement = result.forebuilding,
            phone = forePhone,
            onPhoneChange = { forePhone = it },
            canRemember = foreTenant != null && forePhone.trim() != foreSaved.trim() && Phone.whatsappNumber(forePhone) != null,
            rememberLabel = "Save this number for ${foreName.ifBlank { "this tenant" }}",
            remember = foreRemember,
            onRememberChange = { foreRemember = it },
            showTenantHint = foreTenant == null && record.forebuildingDays > 0 && foreName.isBlank(),
            onTenants = onTenants,
            onPreview = { onPreview(House.FOREBUILDING) },
            onSend = { pending = SendRequest(House.FOREBUILDING, foreName, result.forebuilding, forePhone, foreRemember) },
        )

        notice?.let { Panel { Text(it) } }

        if (!record.importedFromSheet) DriveBackupSection(onlyIfSetUp = true)

        Panel {
            SectionTitle("You pay")
            result.owner.lines.forEach { line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        line.code.label + (line.note?.let { " ($it)" } ?: ""),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(Fmt.rand(line.gross))
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Your total", fontWeight = FontWeight.SemiBold)
                Text(Fmt.rand(result.owner.total), fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Municipal total: ${Fmt.rand(result.invoiceTotal)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (!record.importedFromSheet) {
            OutlinedButton(onClick = { onEdit(key) }, modifier = Modifier.fillMaxWidth()) { Text("Edit the numbers") }
        }
    }

    pending?.let { r ->
        val number = Phone.whatsappNumber(r.phone)
        val who = r.name.ifBlank { r.house.label }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Send the ${r.house.label} statement?") },
            text = {
                Text(
                    "${Fmt.rand(r.statement.total)} for ${record.label}, to $who" +
                        if (number != null) " (+$number)." else ". WhatsApp will ask who to send it to.",
                )
            },
            confirmButton = { TextButton(onClick = { pending = null; send(r) }) { Text("Yes, send") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatementCard(
    heading: String,
    statement: PartyStatement,
    phone: String,
    onPhoneChange: (String) -> Unit,
    canRemember: Boolean,
    rememberLabel: String,
    remember: Boolean,
    onRememberChange: (Boolean) -> Unit,
    showTenantHint: Boolean,
    onTenants: () -> Unit,
    onPreview: () -> Unit,
    onSend: () -> Unit,
) {
    Panel(container = MaterialTheme.colorScheme.secondaryContainer) {
        Text(heading, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (showTenantHint) {
            Text(
                "No tenant is recorded for this house in this month. Add one in Tenants so this and future months show who was invoiced.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onTenants) { Text("Open Tenants") }
        }
        statement.lines.forEach { line ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(line.code.label, modifier = Modifier.weight(1f))
                Text(Fmt.rand(line.gross))
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Total", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(Fmt.rand(statement.total), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        val number = Phone.whatsappNumber(phone)
        OutlinedTextField(
            value = phone,
            onValueChange = onPhoneChange,
            label = { Text("WhatsApp number") },
            singleLine = true,
            isError = phone.isNotBlank() && number == null,
            supportingText = {
                Text(
                    when {
                        phone.isBlank() -> "No number saved - WhatsApp will ask who to send it to"
                        number == null -> "This doesn't look like a valid number"
                        else -> "Will send to +$number"
                    },
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        FindInContactsButton { _, picked -> onPhoneChange(picked) }
        if (canRemember) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = remember, onCheckedChange = onRememberChange)
                Text(rememberLabel)
            }
        }
        OutlinedButton(onClick = onPreview, modifier = Modifier.fillMaxWidth()) { Text("Preview the statement") }
        Button(onClick = onSend, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Send on WhatsApp") }
    }
}
