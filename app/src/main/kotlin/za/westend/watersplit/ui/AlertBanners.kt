package za.westend.watersplit.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Button
import androidx.compose.ui.graphics.Color
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
import za.westend.watersplit.share.FileScanner
import za.westend.watersplit.update.Updater

/** Whether the app may post notifications, refreshed whenever the app comes to the front. */
object PermissionStatus {
    private const val PREFS = "permissions"

    var notificationsAllowed by mutableStateOf(true)
        private set

    /** "All files access", needed to list the PDFs and photos on the phone when attaching. */
    var filesAllowed by mutableStateOf(true)
        private set

    fun refresh(context: Context) {
        filesAllowed = FileScanner.hasAccess()
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
                onResult(error)
            }
        }
    }
    // The folder picker opens in the current backup location; she navigates to the folder that should hold the backups.
    return { picker.launch(BackupLocation.startUri(context)) }
}

/** What to do in the picker, in plain words. */
const val BACKUP_PICKER_HELP =
    "In the next screen, go to the folder where backups should be kept (use the menu at the top left to switch between this phone, Google " +
        "Drive, OneDrive...), then tap \"Use this folder\" and Allow. The very top of a drive can't be used."

/** The three alert banners' colours: light red (permissions), light green (updates), with a dark variant. */
object BannerColors {
    @Composable
    fun permission(): Color = if (isSystemInDarkTheme()) Color(0xFF5C2B29) else Color(0xFFFFDAD6)

    @Composable
    fun update(): Color = if (isSystemInDarkTheme()) Color(0xFF1F4A2A) else Color(0xFFD6F0D0)

    @Composable
    fun text(): Color = if (isSystemInDarkTheme()) Color(0xFFF1F1F1) else Color(0xFF1B1B1B)
}

/**
 * The permission alert banner (light red): every permission that is still missing, each with its own button, so she sees up front
 * how many things there are to do. Shows nothing when both are allowed.
 */
@Composable
fun PermissionAlertBanner() {
    val context = LocalContext.current
    val needInstall = !Updater.installAllowed
    val needNotifications = !PermissionStatus.notificationsAllowed
    val needFiles = !PermissionStatus.filesAllowed
    if (!needInstall && !needNotifications && !needFiles) return

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        PermissionStatus.refresh(context)
    }
    Panel(container = BannerColors.permission(), contentColor = BannerColors.text()) {
        SectionTitle("Permissions needed")
        if (needInstall) {
            Text("Let Water Splitter install its own updates.")
            Button(onClick = { context.startActivity(Updater.allowInstallIntent(context)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Allow installing updates")
            }
        }
        if (needFiles) {
            Text("Let Water Splitter find the invoice PDF and meter photo on this phone.")
            Button(onClick = { context.startActivity(FileScanner.accessIntent(context)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Allow access to files")
            }
        }
        if (needNotifications) {
            Text("Let Water Splitter send you reminders.")
            Button(
                onClick = {
                    if (PermissionStatus.alreadyAsked(context)) {
                        context.startActivity(PermissionStatus.notificationSettingsIntent(context))
                    } else {
                        PermissionStatus.markAsked(context)
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Allow notifications") }
        }
    }
}
