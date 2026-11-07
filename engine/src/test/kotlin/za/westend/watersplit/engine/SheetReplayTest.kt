package za.westend.watersplit.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal

class SheetReplayTest {

    @Test
    fun `history file loaded`() {
        // The private golden file is kept out of the public repository; on a machine that has it, it must be complete.
        org.junit.jupiter.api.Assumptions.assumeTrue(SheetHistory.rows.isNotEmpty(), "sheet-history.csv is not on this machine")
        assertEquals(38, SheetHistory.rows.size)
    }

    @TestFactory
    fun `old rules reproduce every month of the sheet to the cent`() = SheetHistory.rows.map { row ->
        dynamicTest(row.month) {
            val r = Calculator.calculate(row.toInput(), Rules.SHEET_V1)
            assertCents(row.expInvoice, r.invoiceTotal, "invoice total")
            assertCents(row.expOutb, r.outbuilding.total, "outbuilding total")
            assertCents(row.expFore, r.forebuilding.total, "forebuilding total")
            assertCents(row.expOwner, r.owner.total, "owner total")
        }
    }

    @TestFactory
    fun `current rules always add up exactly to the municipal total`() = SheetHistory.rows.map { row ->
        dynamicTest(row.month) {
            val r = Calculator.calculate(row.toInput(billTotalPayable = row.expInvoice), Rules.CURRENT)
            val sum = r.outbuilding.total + r.forebuilding.total + r.owner.total
            assertCents(row.expInvoice, sum, "sum of the three statements")
            val rounding = r.owner.lines.filter { it.code == LineCode.ROUNDING }.sumOf { it.net }
            assertTrue(rounding.abs() <= BigDecimal("0.10"), "rounding line too large: $rounding")
            assertTrue(r.warnings.none { it.code == WarningCode.TOTAL_MISMATCH }, "unexpected TOTAL_MISMATCH")
        }
    }
}
