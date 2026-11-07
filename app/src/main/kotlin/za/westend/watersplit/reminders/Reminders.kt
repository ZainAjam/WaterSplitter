package za.westend.watersplit.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import za.westend.watersplit.MainActivity
import za.westend.watersplit.R
import za.westend.watersplit.data.Store
import za.westend.watersplit.engine.Reminders
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Posts the "invoice due / month waiting" notification. What counts as due is decided in the engine (`Reminders`). */
object ReminderNotifier {
    private const val CHANNEL = "reminders"
    private const val PREFS = "reminders"

    fun canNotify(context: Context): Boolean {
        val permitted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permitted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When the municipal invoice is due, or a month is waiting to be finished"
                },
            )
        }
    }

    private fun post(context: Context, id: Int, title: String, text: String) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission") // checked by canNotify() before every call
        NotificationManagerCompat.from(context).notify(id, n)
    }

    /**
     * Posts the reminder that is due today, if any (and not already posted in the last two days unless [force]).
     * @return the text that was posted, or null when nothing was due / allowed.
     */
    fun checkAndNotify(context: Context, store: Store, force: Boolean, today: LocalDate = LocalDate.now()): String? {
        if (!canNotify(context)) return null
        val reminder = Reminders.due(today, store.settings.reminderDay) { store.monthStatus(it.key) } ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = Reminders.id(reminder)
        val last = prefs.getString(id, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (!force && !Reminders.shouldNotify(last, today)) return null
        val (title, text) = Reminders.text(reminder)
        post(context, 1001, title, text)
        prefs.edit().putString(id, today.toString()).apply()
        return "$title: $text"
    }

    /** For the "send a test" button: the real reminder if one is due, otherwise a friendly confirmation. */
    fun sendTest(context: Context, store: Store): String? {
        if (!canNotify(context)) return null
        checkAndNotify(context, store, force = true)?.let { return it }
        val text = "Reminders are on. Nothing needs doing right now."
        post(context, 1002, "Water Splitter", text)
        return text
    }
}

/** Runs once a day in the background and posts a reminder when one is due. */
class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val store = Store(applicationContext)
        if (store.settings.remindersOn) ReminderNotifier.checkAndNotify(applicationContext, store, force = false)
        return Result.success()
    }
}

object ReminderScheduler {
    private const val WORK = "daily-reminder-check"

    /** Turns the daily check on or off. Safe to call on every app start. */
    fun apply(context: Context, on: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!on) {
            wm.cancelUniqueWork(WORK)
            return
        }
        // Aim for about 09:00 so the notification arrives at a sensible hour.
        val now = LocalDateTime.now()
        var first = now.toLocalDate().atTime(LocalTime.of(9, 0))
        if (!first.isAfter(now)) first = first.plusDays(1)
        val delay = Duration.between(now, first).toMillis()
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS).setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
