package za.westend.watersplit.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class ReadingTest {
    private fun d(s: String) = BigDecimal(s)

    /** What the August 2026 municipal invoice says, as the reader would return it. */
    private val august = mapOf(
        "statementDate" to "2026-08-24",
        "accountNumber" to "600000000001",
        "propertyKl" to "8.0",
        "water" to "175.36",
        "waterAvailability" to "71.74",
        "sewerage" to "170.41",
        "sewerageAvailability" to "71.75",
        "refuse" to "175.35",
        "generalRates" to "564.90",
        "interest" to "0",
        "broughtForward" to "1 266.40",
        "payments" to "1 284.50",
        "otherAdjustments" to "0",
        "totalPayable" to "1 311.10",
        "unsure" to "",
        "notes" to null,
    )

    @Test
    fun `the real August invoice reads into the right fields`() {
        val r = InvoiceReading.fromStrings(august)
        assertEquals(LocalDate.of(2026, 8, 24), r.statementDate)
        assertEquals(0, d("-18.10").compareTo(r.adjustments))
        val f = r.fillInto(DraftFields())
        assertEquals("8", f.totalKl)
        assertEquals("175.36", f.water)
        assertEquals("71.74", f.avail)
        assertEquals("-18.1", f.adjustments)
        assertEquals("", f.interest) // zero interest is left blank
        assertEquals("1311.1", f.payable)
        assertTrue(DraftChecker.check(f.copy(prev = "72", curr = "73"), 31).invoiceCaptured)
    }

    @Test
    fun `typed numbers are never overwritten`() {
        val typed = DraftFields(water = "999", refuse = "")
        val f = InvoiceReading.fromStrings(august).fillInto(typed)
        assertEquals("999", f.water)
        assertEquals("175.35", f.refuse)
    }

    @Test
    fun `lines that add up to the invoice total are confirmed`() {
        val notes = InvoiceReading.fromStrings(august).checks("600000000001", MonthKey(2026, 8))
        assertTrue(notes.none { it.warning })
        assertTrue(notes.any { it.text.startsWith("Checked") })
    }

    @Test
    fun `a misread number is caught by the total`() {
        val bad = InvoiceReading.fromStrings(august + ("water" to "175.86"))
        val warn = bad.checks("600000000001", MonthKey(2026, 8)).first { it.warning }
        assertTrue(warn.text.contains("add up"))
    }

    @Test
    fun `an invoice for another month suggests that month`() {
        val notes = InvoiceReading.fromStrings(august).checks("600000000001", MonthKey(2026, 10))
        val n = notes.first { it.suggestMonth != null }
        assertEquals(MonthKey(2026, 8), n.suggestMonth)
        assertTrue(n.text.contains("24 August 2026"))
    }

    @Test
    fun `an invoice for another account is flagged`() {
        val notes = InvoiceReading.fromStrings(august + ("accountNumber" to "600099999999")).checks("600000000001", MonthKey(2026, 8))
        assertTrue(notes.any { it.warning && it.text.contains("account") })
        // no expected account configured: nothing to compare
        assertTrue(InvoiceReading.fromStrings(august).checks(null, MonthKey(2026, 8)).none { it.text.contains("account") })
    }

    @Test
    fun `fields the reader was unsure about are listed`() {
        val r = InvoiceReading.fromStrings(august + ("unsure" to "water, refuse"))
        assertEquals(listOf("water", "refuse"), r.unsure)
        assertTrue(r.checks(null, MonthKey(2026, 8)).any { it.warning && it.text.contains("water, refuse") })
    }

    @Test
    fun `an unreadable invoice is reported as empty`() {
        val r = InvoiceReading.fromStrings(mapOf("notes" to "page is blank"))
        assertTrue(r.isEmpty)
        assertNull(r.adjustments)
        assertEquals(DraftFields(), r.fillInto(DraftFields()))
    }

    @Test
    fun `numbers arrive in South African formats`() {
        val r = InvoiceReading.fromStrings(august + ("broughtForward" to "R 1 266,40") + ("payments" to "1,284.50"))
        assertEquals(0, d("-18.10").compareTo(r.adjustments))
    }

    @Test
    fun `the meter photo reading fills the reading and is checked against last month`() {
        val m = MeterReading.fromStrings(mapOf("reading" to "73.806", "serial" to "SN000000001", "confidence" to "high", "lastWheelUncertain" to "true"))
        assertEquals("73.806", m.fillInto(DraftFields()).curr)
        assertEquals("1", m.fillInto(DraftFields(curr = "1")).curr)
        val ok = m.checks(d("72.3937"), "SN000000001", false)
        assertTrue(ok.none { it.warning })
        assertTrue(ok.any { it.text.contains("between two numbers") })
    }

    @Test
    fun `a lower reading a different meter or a big jump are flagged`() {
        val m = MeterReading.fromStrings(mapOf("reading" to "70", "serial" to "SN999", "confidence" to "low"))
        val notes = m.checks(d("73.806"), "SN000000001", false)
        assertTrue(notes.any { it.text.contains("hard to read") })
        assertTrue(notes.any { it.text.contains("SN999") })
        assertTrue(notes.any { it.text.contains("lower than") })
        // replaced meter: lower is fine
        assertFalse(m.checks(d("73.806"), "SN999", true).any { it.text.contains("lower than") })
        val jump = MeterReading.fromStrings(mapOf("reading" to "173.5")).checks(d("73.806"), null, false)
        assertTrue(jump.any { it.text.contains("more than last month") })
    }

    @Test
    fun `no reading means type it in`() {
        val m = MeterReading.fromStrings(mapOf("reading" to "null"))
        assertNull(m.reading)
        assertTrue(m.checks(d("73.806"), null, false).single().text.contains("type it in"))
    }
}
