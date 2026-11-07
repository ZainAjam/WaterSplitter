package za.westend.watersplit.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class DraftCheckTest {
    private val invoice = DraftFields(
        totalKl = "8", water = "175.36", avail = "71.74", sewer = "170.41",
        sewAvail = "71,75", refuse = "175.35", rates = "564.90", adjustments = "-18.10", payable = "1311.10",
    )
    private val meter = DraftFields(prev = "73.806", curr = "74,806")
    private val full = invoice.copy(prev = meter.prev, curr = meter.curr)

    @Test
    fun `an empty draft has nothing captured`() {
        val c = DraftChecker.check(DraftFields(), 30)
        assertFalse(c.invoiceCaptured)
        assertFalse(c.meterCaptured)
        assertFalse(c.ready)
        assertEquals(DraftChecker.INVOICE_FIELDS, c.invoiceMissing.size)
        assertEquals(2, c.meterMissing.size)
        assertNull(c.meterUse)
    }

    @Test
    fun `invoice and meter are tracked separately`() {
        val onlyInvoice = DraftChecker.check(invoice, 31)
        assertTrue(onlyInvoice.invoiceCaptured)
        assertFalse(onlyInvoice.meterCaptured)
        assertFalse(onlyInvoice.ready)

        val onlyMeter = DraftChecker.check(meter, 31)
        assertFalse(onlyMeter.invoiceCaptured)
        assertTrue(onlyMeter.meterCaptured)
        assertFalse(onlyMeter.ready)
        assertEquals(0, BigDecimal("1").compareTo(onlyMeter.meterUse!!.kl))
    }

    @Test
    fun `sending is only possible once both are captured`() {
        val c = DraftChecker.check(full, 31)
        assertTrue(c.ready)
        val p = c.parsed!!
        assertEquals(0, BigDecimal("71.75").compareTo(p.sewerageAvailability))
        assertEquals(0, BigDecimal("-18.10").compareTo(p.adjustments))
        assertEquals(0, BigDecimal("1311.10").compareTo(p.payable))
        assertEquals(31, p.outbuildingDays)
    }

    @Test
    fun `payable is optional`() {
        val c = DraftChecker.check(full.copy(payable = ""), 31)
        assertTrue(c.ready)
        assertNull(c.parsed!!.payable)
    }

    @Test
    fun `a reading lower than the previous one blocks the draft`() {
        val c = DraftChecker.check(full.copy(curr = "70"), 31)
        assertFalse(c.meterCaptured)
        assertFalse(c.ready)
        assertTrue(c.problems().any { it.contains("lower") })
    }

    @Test
    fun `a replaced meter is accepted with both extra readings`() {
        val f = full.copy(prev = "1382.8716", curr = "2.5", replaced = true, oldFinal = "1382.9", newStart = "0")
        val c = DraftChecker.check(f, 31)
        assertTrue(c.ready)
        assertEquals(0, BigDecimal("2.5284").compareTo(c.parsed!!.meterUse.kl))
        assertFalse(DraftChecker.check(f.copy(newStart = ""), 31).meterCaptured)
    }

    @Test
    fun `part month days must be whole numbers within the month`() {
        val part = full.copy(fullMonth = false, outbDays = "15", foreDays = "31")
        val ok = DraftChecker.check(part, 31)
        assertTrue(ok.ready)
        assertEquals(15, ok.parsed!!.outbuildingDays)
        assertFalse(DraftChecker.check(part.copy(outbDays = "40"), 31).ready)
        assertFalse(DraftChecker.check(part.copy(foreDays = ""), 31).ready)
    }

    @Test
    fun `text that is not a number is flagged`() {
        val c = DraftChecker.check(full.copy(interest = "abc"), 31)
        assertFalse(c.invoiceCaptured)
        assertNotNull(c.invoiceProblems.firstOrNull { it.contains("Interest") })
    }
}
