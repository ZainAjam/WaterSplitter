package za.westend.watersplit.ui

import androidx.compose.foundation.layout.Arrangement
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
) {
    val months = store.months.asReversed()
    val drafts = store.drafts.asReversed()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Water Splitter",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
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
        if (drafts.isNotEmpty()) {
            item { SectionTitle("In progress") }
            items(drafts, key = { "draft-" + it.key }) { d -> DraftRow(d, onOpenDraft) }
        }
        item { SectionTitle("Previous months") }
        items(months, key = { it.key }) { m -> MonthRow(m, store, onOpen) }
    }
}

@Composable
private fun DraftRow(d: Draft, onOpen: (String) -> Unit) {
    val check = remember(d) { DraftChecker.check(d.fields, MonthKey.parse(d.key).daysInMonth()) }
    Card(onClick = { onOpen(d.key) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(d.label, fontWeight = FontWeight.SemiBold)
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

@Composable
private fun MonthRow(m: MonthRecord, store: Store, onOpen: (String) -> Unit) {
    val result = remember(m) { m.calculate() }
    Card(onClick = { onOpen(m.key) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
