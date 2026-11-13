package za.westend.watersplit

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import za.westend.watersplit.data.BackupLocation
import za.westend.watersplit.data.Packages
import za.westend.watersplit.ui.PermissionStatus
import za.westend.watersplit.data.Store
import za.westend.watersplit.reading.ReaderService
import za.westend.watersplit.reminders.ReminderScheduler
import za.westend.watersplit.share.Incoming
import za.westend.watersplit.share.ShareInbox
import za.westend.watersplit.update.Updater
import za.westend.watersplit.ui.AppRoot
import za.westend.watersplit.ui.WaterSplitTheme
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private val inbox = ShareInbox()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = Store(applicationContext)
        ReaderService.init(applicationContext)
        ReminderScheduler.apply(applicationContext, store.settings.remindersOn)
        BackupLocation.load(applicationContext)
        PermissionStatus.refresh(applicationContext)
        Updater.refreshPermission(applicationContext)
        Updater.checkIfDue(applicationContext)
        Incoming.cleanUp(applicationContext)
        // A recreated activity gets the same intent again; only a fresh start should file the shared files.
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            WaterSplitTheme {
                AppRoot(store, inbox)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // She may have just switched on "install unknown apps" in Android's settings, or the app was left open for days.
        Updater.refreshPermission(applicationContext)
        PermissionStatus.refresh(applicationContext)
        Updater.checkIfDue(applicationContext)
    }

    /** The app is already open (or in the background) and something else is shared to it. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent == null) return
        val app = applicationContext
        // A tapped .wsbackup / .wsmonths file (or one shared to the app): look inside, whatever it is called.
        val single = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java)
            else -> null
        }
        if (single != null) {
            val viewing = intent.action == Intent.ACTION_VIEW
            thread {
                val pkg = Packages.receive(app, single)
                runOnUiThread {
                    when {
                        pkg != null -> inbox.pending = pkg
                        viewing -> Toast.makeText(this, "That file can't be opened by Water Splitter.", Toast.LENGTH_LONG).show()
                        else -> handleShareFiles(intent)
                    }
                }
            }
            setIntent(Intent(this, MainActivity::class.java))
            return
        }
        handleShareFiles(intent)
    }

    private fun handleShareFiles(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
        val app = applicationContext
        // Copy on a background thread: the sender's content URI may be slow to open.
        thread {
            val items = Incoming.fromIntent(app, intent)
            runOnUiThread {
                if (items.isEmpty()) {
                    Toast.makeText(this, "That file can't be used. Share a PDF or a photo.", Toast.LENGTH_LONG).show()
                } else {
                    inbox.items.addAll(items)
                }
            }
        }
        // Don't file the same share twice if the activity is recreated.
        setIntent(Intent(this, MainActivity::class.java))
    }
}
