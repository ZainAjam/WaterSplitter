package za.westend.watersplit.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RemindersTest {
    private fun m(y: Int, mo: Int) = MonthKey(y, mo)
    private fun day(y: Int, mo: Int, d: Int) = LocalDate.of(y, mo, d)

    private fun due(today: LocalDate, vararg known: Pair<MonthKey, MonthStatus>): Reminder? {
        val map = known.toMap()
        return Reminders.due(today) { map[it] ?: MonthStatus.NONE }
    }

    @Test
    fun `nothing to say mid month when everything is done`() {
        assertNull(due(day(2026, 11, 14), m(2026, 10) to MonthStatus.COMPLETE))
    }

    @Test
    fun `from the 25th an invoice is expected when the month has not been started`() {
        val r = due(day(2026, 11, 25), m(2026, 10) to MonthStatus.COMPLETE)
        assertEquals(Reminder.InvoiceExpected(m(2026, 11)), r)
        assertNull(due(day(2026, 11, 24), m(2026, 10) to MonthStatus.COMPLETE))
        assertNull(due(day(2026, 11, 25), m(2026, 10) to MonthStatus.COMPLETE, m(2026, 11) to MonthStatus.NEEDS_BOTH).takeIf { it is Reminder.InvoiceExpected })
    }

    @Test
    fun `the chosen day replaces the 25th`() {
        assertNull(Reminders.due(day(2026, 11, 14), 15) { if (it == m(2026, 10)) MonthStatus.COMPLETE else MonthStatus.NONE })
        assertEquals(
            Reminder.InvoiceExpected(m(2026, 11)),
            Reminders.due(day(2026, 11, 15), 15) { if (it == m(2026, 10)) MonthStatus.COMPLETE else MonthStatus.NONE },
        )
        // A late chosen day, after the invoice normally arrives, still works; the old default would already have fired.
        assertNull(Reminders.due(day(2026, 11, 26), 28) { if (it == m(2026, 10)) MonthStatus.COMPLETE else MonthStatus.NONE })
    }

    @Test
    fun `a day after the end of a short month counts as its last day`() {
        val r = Reminders.due(day(2026, 2, 28), 31) { if (it == m(2026, 1)) MonthStatus.COMPLETE else MonthStatus.NONE }
        assertEquals(Reminder.InvoiceExpected(m(2026, 2)), r)
    }

    @Test
    fun `a started month that is still waiting is reminded about`() {
        val r = due(day(2026, 11, 7), m(2026, 10) to MonthStatus.NEEDS_METER)
        assertEquals(Reminder.Waiting(m(2026, 10), MonthStatus.NEEDS_METER), r)
        assertEquals("Waiting for the meter photo.", Reminders.text(r!!).second)
    }

    @Test
    fun `this month's draft only nags once its invoice is due`() {
        assertNull(due(day(2026, 11, 7), m(2026, 10) to MonthStatus.COMPLETE, m(2026, 11) to MonthStatus.NEEDS_BOTH))
        assertEquals(
            Reminder.Waiting(m(2026, 11), MonthStatus.NEEDS_BOTH),
            due(day(2026, 11, 26), m(2026, 10) to MonthStatus.COMPLETE, m(2026, 11) to MonthStatus.NEEDS_BOTH),
        )
    }

    @Test
    fun `the oldest unfinished month comes first`() {
        val r = due(day(2026, 11, 7), m(2026, 9) to MonthStatus.NEEDS_CHECK, m(2026, 10) to MonthStatus.NEEDS_METER)
        assertEquals(m(2026, 9), r!!.month)
    }

    @Test
    fun `a ready month is a reminder to send`() {
        val r = due(day(2026, 11, 3), m(2026, 10) to MonthStatus.READY)!!
        assertTrue(Reminders.text(r).second.contains("ready to review and send"))
    }

    @Test
    fun `a month that was skipped entirely is flagged in the first days of the next`() {
        assertEquals(Reminder.InvoiceExpected(m(2026, 10)), due(day(2026, 11, 5), m(2026, 9) to MonthStatus.COMPLETE))
        assertNull(due(day(2026, 11, 12), m(2026, 9) to MonthStatus.COMPLETE))
    }

    @Test
    fun `the same reminder is repeated at most every other day`() {
        assertTrue(Reminders.shouldNotify(null, day(2026, 11, 7)))
        assertFalse(Reminders.shouldNotify(day(2026, 11, 7), day(2026, 11, 8)))
        assertTrue(Reminders.shouldNotify(day(2026, 11, 7), day(2026, 11, 9)))
        assertEquals("expected-2026-11", Reminders.id(Reminder.InvoiceExpected(m(2026, 11))))
        assertEquals("waiting-2026-10-READY", Reminders.id(Reminder.Waiting(m(2026, 10), MonthStatus.READY)))
    }
}
