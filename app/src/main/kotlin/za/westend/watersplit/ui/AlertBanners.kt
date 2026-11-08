package za.westend.watersplit.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.westend.watersplit.data.BackupLocation
import za.westend.watersplit.update.Updater

/** Whether the app may post notifications, refreshed whenever the app comes to the front. */
object PermissionStatus {
    private const val PREFS = "permissions"

    var notificationsAllowed by mutableStateOf(true)
        private set

    fun refresh(context: Context) {
        notificationsAllowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    /** Android only shows its question once or twice; after that the person has to switch it on in the phone's settings. */
    fun alreadyAsked(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("notificationsAsked", false)

    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("notificationsAsked", true).apply()
    }

    fun notificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * Opens Android's folder picker so she can choose where backups go (phone storage, Google Drive, OneDrive...). Returns the function that
 * opens it. [onResult] gets a sentence to show when the chosen folder couldn't be used, or null when it worked (the first backup is
 * then made at once).
 */
@Composable
fun rememberBackupLocationPicker(onResult: (String?) -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch {
                val error = withContext(Dispatchers.IO) { BackupLocation.setUp(context, uri) }
                if (error == null) BackupLocation.backupAsync(context)
                onResult(error)
            }
        }
    }
    return { picker.launch(null) }
}

/** What to do in the picker, in plain words. */
const val BACKUP_PICKER_HELP =
    "In the next screen, open the menu at the top left and choose where the backup should live (this phone, Google Drive, OneDrive...). " +
        "Open or create a folder there, then tap \"Use this folder\" and Allow. The very top of a drive can't be used."

/**
 * The "permissions and settings alert banner": lists what is still outstanding (install permission, notification permission,
 * backup location) and has one button that deals with the next one each time it is tapped. Shows nothing when all is set.
 */
@Composable
fun PermissionsAlertBanner() {
    val context = LocalContext.current
    val needInstall = Updater.isConfigured && !Updater.installAllowed
    val needNotifications = !PermissionStatus.notificationsAllowed
    val needLocation = !BackupLocation.choiceMade
    if (!needInstall && !needNotifications && !needLocation) return

    var problem by remember { mutableStateOf<String?>(null) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        PermissionStatus.refresh(context)
    }
    val chooseLocation = rememberBackupLocationPicker { problem = it }

    val next = when {
        needInstall -> "Allow installing updates"
        needNotifications -> "Allow notifications"
        else -> "Choose backup location"
    }
    Panel(container = MaterialTheme.colorScheme.tertiaryContainer) {
        SectionTitle("Outstanding permissions or backup location")
        if (needInstall) Text("• Allow the app to install its own updates")
        if (needNotifications) Text("• Allow notifications (for reminders)")
        if (needLocation) Text("• Choose where backups are saved")
        Button(
            onClick = {
                problem = null
                when {
                    needInstall -> context.startActivity(Updater.allowInstallIntent(context))
                    needNotifications -> {
                        if (PermissionStatus.alreadyAsked(context)) {
                            context.startActivity(PermissionStatus.notificationSettingsIntent(context))
                        } else {
                            PermissionStatus.markAsked(context)
                            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    else -> chooseLocation()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(next) }
        if (!needInstall && !needNotifications && needLocation) {
            Text(BACKUP_PICKER_HELP, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { BackupLocation.useDefault(context) }, modifier = Modifier.fillMaxWidth()) {
                Text("Keep the default instead (choose each time)", fontWeight = FontWeight.Normal)
            }
        } else {
            Text("Tap the button for each item in turn.", style = MaterialTheme.typography.bodySmall)
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
