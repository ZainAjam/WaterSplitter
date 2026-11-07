package za.westend.watersplit

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        Updater.checkIfDue(applicationContext)
    }

    /** The app is already open (or in the background) and something else is shared to it. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent == null) return
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
