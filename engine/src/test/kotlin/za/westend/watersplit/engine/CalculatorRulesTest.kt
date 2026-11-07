package za.westend.watersplit.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CalculatorRulesTest {

    private fun d(s: String) = BigDecimal(s)

    /** The real August 2026 invoice (R1 311.10 payable, R18.10 credit brought forward). */
    private val august = MunicipalBill(
        totalWaterKl = d("8"),
        water = d("175.36"),
        waterAvailability = d("71.74"),
        sewerage = d("170.41"),
        sewerageAvailability = d("71.75"),
        refuse = d("175.35"),
        generalRates = d("564.90"),
        adjustments = d("-18.10"),
    )

    private fun input(
        outbKl: String = "1",
        occupancy: Occupancy = Occupancy.full(31),
        assignments: Assignments = Assignments(),
        bill: MunicipalBill = august,
    ) = MonthInput(bill, d(outbKl), occupancy, assignments, billTotalPayable = d("1311.10"))

    private fun MonthResult.line(party: Party, code: LineCode): InvoiceLine? {
        val s = when (party) {
            Party.OUTBUILDING -> outbuilding
            Party.FOREBUILDING -> forebuilding
            else -> owner
        }
        return s.lines.firstOrNull { it.code == code }
    }

    private fun MonthResult.sum() = outbuilding.total + forebuilding.total + owner.total

    @Test
    fun `sewerage follows water use under current rules`() {
        val r = Calculator.calculate(input(outbKl = "1"), Rules.CURRENT) // 1 of 8 kL = 12.5%
        assertEquals(d("21.92"), r.line(Party.OUTBUILDING, LineCode.WATER)!!.net)
        assertEquals(d("21.30"), r.line(Party.OUTBUILDING, LineCode.SEWERAGE)!!.net)
        assertEquals(d("149.11"), r.line(Party.FOREBUILDING, LineCode.SEWERAGE)!!.net)
        assertEquals(d("3.20"), r.line(Party.OUTBUILDING, LineCode.SEWERAGE)!!.vat)
    }

    @Test
    fun `fixed charges stay half and half and always sum to the bill`() {
        val r = Calculator.calculate(input(), Rules.CURRENT)
        val o = r.line(Party.OUTBUILDING, LineCode.SEWERAGE_AVAILABILITY)!!.net
        val f = r.line(Party.FOREBUILDING, LineCode.SEWERAGE_AVAILABILITY)!!.net
        assertEquals(d("71.75"), o + f)
        assertEquals(d("1311.10"), r.sum())
    }

    @Test
    fun `old rules still split sewerage fifty fifty`() {
        val r = Calculator.calculate(MonthInput(august, d("1")), Rules.SHEET_V1)
        val o = r.line(Party.OUTBUILDING, LineCode.SEWERAGE)!!.net
        assertEquals(0, o.compareTo(d("85.205")))
    }

    @Test
    fun `interest and adjustments default to the owner`() {
        val bill = august.copy(interest = d("20.00"))
        val r = Calculator.calculate(input(bill = bill).copy(billTotalPayable = null), Rules.CURRENT)
        assertEquals(d("20.00"), r.line(Party.OWNER, LineCode.INTEREST)!!.net)
        assertNull(r.line(Party.OUTBUILDING, LineCode.INTEREST))
        assertNull(r.line(Party.FOREBUILDING, LineCode.INTEREST))
    }

    @Test
    fun `interest can be assigned to the forebuilding`() {
        val bill = august.copy(interest = d("20.00"))
        val r = Calculator.calculate(
            input(bill = bill, assignments = Assignments(interest = Party.FOREBUILDING)).copy(billTotalPayable = null),
            Rules.CURRENT,
        )
        assertEquals(d("20.00"), r.line(Party.FOREBUILDING, LineCode.INTEREST)!!.net)
        assertEquals(d("0.00"), r.line(Party.FOREBUILDING, LineCode.INTEREST)!!.vat)
        assertNull(r.line(Party.OWNER, LineCode.INTEREST))
    }

    @Test
    fun `adjustment can be split equally with no cent lost`() {
        val bill = august.copy(adjustments = d("20.01"))
        val r = Calculator.calculate(
            input(bill = bill, assignments = Assignments(adjustments = Party.SPLIT_EQUALLY)).copy(billTotalPayable = null),
            Rules.CURRENT,
        )
        val o = r.line(Party.OUTBUILDING, LineCode.ADJUSTMENT)!!.net
        val f = r.line(Party.FOREBUILDING, LineCode.ADJUSTMENT)!!.net
        assertEquals(d("20.01"), o + f)
    }

    @Test
    fun `part month occupancy prorates fixed charges and the owner takes the empty days`() {
        val occ = Occupancy(daysInPeriod = 30, outbuildingDays = 15, forebuildingDays = 30)
        val r = Calculator.calculate(input(occupancy = occ), Rules.CURRENT)
        assertEquals(d("17.94"), r.line(Party.OUTBUILDING, LineCode.WATER_AVAILABILITY)!!.net)
        val ownerShare = r.owner.lines.first { it.code == LineCode.WATER_AVAILABILITY }
        assertEquals(d("17.93"), ownerShare.net)
        assertEquals("Outbuilding vacant 15 of 30 days", ownerShare.note)
        // water still follows the meter in full
        assertEquals(d("21.92"), r.line(Party.OUTBUILDING, LineCode.WATER)!!.net)
        assertEquals(d("1311.10"), r.sum())
    }

    @Test
    fun `a house empty for the whole month costs the tenant nothing`() {
        val occ = Occupancy(daysInPeriod = 30, outbuildingDays = 0, forebuildingDays = 30)
        val r = Calculator.calculate(input(outbKl = "0", occupancy = occ), Rules.CURRENT)
        assertTrue(r.outbuilding.lines.isEmpty())
        assertEquals(d("0.00"), r.outbuilding.total)
        assertTrue(r.owner.lines.any { it.code == LineCode.REFUSE && it.note == "Outbuilding vacant" })
        assertEquals(d("1311.10"), r.sum())
    }

    @Test
    fun `outbuilding use above property use is clamped and flagged`() {
        val r = Calculator.calculate(input(outbKl = "9"), Rules.CURRENT)
        assertTrue(r.warnings.any { it.code == WarningCode.OUTBUILDING_USE_EXCEEDS_PROPERTY })
        assertEquals(d("175.36"), r.line(Party.OUTBUILDING, LineCode.WATER)!!.net)
        assertEquals(d("0.00"), r.line(Party.FOREBUILDING, LineCode.WATER)!!.net)
        assertEquals(d("1311.10"), r.sum())
    }

    @Test
    fun `zero outbuilding use is flagged only while the house is occupied`() {
        val occupied = Calculator.calculate(input(outbKl = "0"), Rules.CURRENT)
        assertTrue(occupied.warnings.any { it.code == WarningCode.ZERO_OUTBUILDING_USE })
        val empty = Calculator.calculate(
            input(outbKl = "0", occupancy = Occupancy(31, 0, 31)),
            Rules.CURRENT,
        )
        assertTrue(empty.warnings.none { it.code == WarningCode.ZERO_OUTBUILDING_USE })
    }

    @Test
    fun `an invoice that does not add up is flagged`() {
        val r = Calculator.calculate(input().copy(billTotalPayable = d("1400.00")), Rules.CURRENT)
        assertTrue(r.warnings.any { it.code == WarningCode.TOTAL_MISMATCH })
    }
}
