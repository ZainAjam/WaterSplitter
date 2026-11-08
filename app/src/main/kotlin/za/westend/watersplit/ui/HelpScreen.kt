package za.westend.watersplit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** A short walk-through of the whole monthly routine, in the order she does it. */
@Composable
fun HelpScreen(onBack: () -> Unit) {
    ScreenColumn(title = "How to use Water Splitter", onBack = onBack) {
        HelpStep(
            number = 1,
            text = "Find the municipality email and press the download button next to the invoice attachment of the email.",
        )
        HelpStep(
            number = 2,
            text = "Fully open the meter photo inside WhatsApp and press the SAVE download arrow at the top of the app.",
        )
        HelpStep(
            number = 3,
            text = "Open this Water Splitter app and start a new month, then follow the instructions in the app.",
        )
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Got it", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun HelpStep(number: Int, text: String) {
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Step $number", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
