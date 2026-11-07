package za.westend.watersplit.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** How far a month has got. */
enum class MonthStatus {
    /** Nothing at all for this month. */
    NONE,
    NEEDS_INVOICE,
    NEEDS_METER,
    NEEDS_BOTH,

    /** Numbers are in but still need checking (or something is wrong) before sending. */
    NEEDS_CHECK,

    /** Checked: ready to review and send. */
    READY,

    /** Calculated (and sent or sendable from the month's page). */
    COMPLETE,
}

sealed interface Reminder {
    val month: MonthKey

    /** The municipal invoice should have arrived but nothing has been started for the month. */
    data class InvoiceExpected(override val month: MonthKey) : Reminder

    /** A month has been started but is waiting for something. */
    data class Waiting(override val month: MonthKey, val status: MonthStatus) : Reminder
}

object Reminders {
    /** The municipality's statement date is around the 24th, so the invoice is expected from the 25th. */
    const val EXPECTED_FROM_DAY = 25

    /**
     * The most important reminder for [today], or null when there is nothing to nag about.
     * [fromDay] is the day of the month the person chose to be reminded from (a short month counts its last day).
     */
    fun due(today: LocalDate, fromDay: Int = EXPECTED_FROM_DAY, statusOf: (MonthKey) -> MonthStatus): Reminder? {
        val thisMonth = MonthKey(today.year, today.monthValue)
        val previous = thisMonth.previous()
        val from = fromDay.coerceIn(1, 31).coerceAtMost(today.lengthOfMonth())
        // Months already started and waiting, oldest first. This month only counts once its invoice is due.
        for (m in listOf(previous.previous(), previous, thisMonth)) {
            val s = statusOf(m)
            val waiting = s != MonthStatus.NONE && s != MonthStatus.COMPLETE
            if (waiting && (m != thisMonth || today.dayOfMonth >= from)) return Reminder.Waiting(m, s)
        }
        if (today.dayOfMonth >= from && statusOf(thisMonth) == MonthStatus.NONE) return Reminder.InvoiceExpected(thisMonth)
        // Last month's invoice came and went with nothing started.
        if (today.dayOfMonth <= 10 && today.dayOfMonth < from && statusOf(previous) == MonthStatus.NONE) return Reminder.InvoiceExpected(previous)
        return null
    }

    /** (title, message) for the notification. */
    fun text(r: Reminder): Pair<String, String> = when (r) {
        is Reminder.InvoiceExpected ->
            "${r.month.label()} invoice" to "The municipal invoice should have arrived. Share it to this app to start the statements."
        is Reminder.Waiting -> r.month.label() to when (r.status) {
            MonthStatus.NEEDS_BOTH -> "Waiting for the invoice and the meter photo."
            MonthStatus.NEEDS_INVOICE -> "Waiting for the municipal invoice."
            MonthStatus.NEEDS_METER -> "Waiting for the meter photo."
            MonthStatus.NEEDS_CHECK -> "The numbers need checking before the statements can be sent."
            MonthStatus.READY -> "The statements are ready to review and send."
            else -> "Open the app to finish this month."
        }
    }

    /** A stable id for "this reminder about this month in this state", used to avoid repeating it every day. */
    fun id(r: Reminder): String = when (r) {
        is Reminder.InvoiceExpected -> "expected-${r.month.key}"
        is Reminder.Waiting -> "waiting-${r.month.key}-${r.status.name}"
    }

    /** Repeat the same reminder at most every other day. */
    fun shouldNotify(lastNotified: LocalDate?, today: LocalDate): Boolean =
        lastNotified == null || ChronoUnit.DAYS.between(lastNotified, today) >= 2
}
