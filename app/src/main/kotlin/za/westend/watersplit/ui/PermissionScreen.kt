package za.westend.watersplit.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import za.westend.watersplit.update.Updater

/**
 * Shown once, the first time the app opens after being installed: asks Android's "install unknown apps" permission so the
 * app can install its own updates. It comes before the setup wizard (and so before a backup can be restored).
 */
@Composable
fun InstallPermissionScreen() {
    val context = LocalContext.current
    ScreenColumn(title = "Welcome to Water Splitter") {
        Text(
            "Water Splitter updates itself from the internet. For that, Android needs your OK to let this app install its own updates.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Panel {
            SectionTitle("What happens next")
            Text("1. Tap the button below.")
            Text("2. Switch on \"Allow from this source\".")
            Text("3. Press Back to return here.")
        }
        Button(
            onClick = {
                Updater.markPermissionAsked(context)
                context.startActivity(Updater.allowInstallIntent(context))
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Allow updates", style = MaterialTheme.typography.titleMedium) }
        TextButton(onClick = { Updater.markPermissionAsked(context) }, modifier = Modifier.fillMaxWidth()) {
            Text("Not now")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "If you skip this, a reminder stays at the top of the main screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
