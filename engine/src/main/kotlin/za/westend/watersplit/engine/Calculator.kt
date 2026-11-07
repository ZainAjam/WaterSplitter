package za.westend.watersplit.engine

import java.math.BigDecimal
import java.math.BigDecimal.ONE
import java.math.BigDecimal.ZERO

/** Splits one municipal invoice between the outbuilding, the forebuilding and the owner. Pure and side-effect free. */
object Calculator {

    private val MISMATCH_LIMIT = BigDecimal("0.05")
    private val ROUNDING_LIMIT = BigDecimal("0.10")

    fun calculate(input: MonthInput, rules: Rules): MonthResult {
        val bill = input.bill
        val occ = input.occupancy
        val perLine = rules.rounding == RoundingPolicy.ROUND_EACH_LINE
        val warnings = mutableListOf<CalcWarning>()
        val fraction = outbuildingFraction(input, warnings)

        val lines: Map<Party, MutableList<InvoiceLine>> = mapOf(
            Party.OUTBUILDING to mutableListOf(),
            Party.FOREBUILDING to mutableListOf(),
            Party.OWNER to mutableListOf(),
        )

        fun round(x: BigDecimal): BigDecimal = if (perLine) x.cents() else x

        fun line(code: LineCode, net: BigDecimal, note: String? = null): InvoiceLine {
            val n = round(net)
            val vat = round(if (code.vatable) n.multiply(VAT_RATE) else ZERO)
            return InvoiceLine(code, n, vat, note)
        }

        /** Splits [total] into (outbuilding, forebuilding) so the two always add up to [total]. */
        fun split(total: BigDecimal, outbuildingShare: BigDecimal): Pair<BigDecimal, BigDecimal> {
            val o = round(outbuildingShare)
            return Pair(o, total - o)
        }

        fun addTenantShare(house: House, code: LineCode, share: BigDecimal) {
            val party = if (house == House.OUTBUILDING) Party.OUTBUILDING else Party.FOREBUILDING
            val days = occ.days(house)
            val factor = tenantFactor(code, days, occ.daysInPeriod, rules)
            val tenantNet = if (factor.compareTo(ONE) == 0) share else round(share.multiply(factor, MC))
            val ownerNet = share - tenantNet
            if (factor.signum() != 0) lines.getValue(party).add(line(code, tenantNet))
            if (ownerNet.signum() != 0) {
                lines.getValue(Party.OWNER).add(line(code, ownerNet, vacancyNote(house, days, occ.daysInPeriod)))
            }
        }

        val byUse = { total: BigDecimal -> split(total, total.multiply(fraction, MC)) }
        val halves = { total: BigDecimal -> split(total, total.half()) }

        val charges = listOf(
            LineCode.WATER to byUse(bill.water),
            LineCode.WATER_AVAILABILITY to halves(bill.waterAvailability),
            LineCode.SEWERAGE to
                if (rules.sewerageBasis == SewerageBasis.BY_WATER_USE) byUse(bill.sewerage) else halves(bill.sewerage),
            LineCode.SEWERAGE_AVAILABILITY to halves(bill.sewerageAvailability),
            LineCode.REFUSE to halves(bill.refuse),
        )
        for ((code, shares) in charges) {
            addTenantShare(House.OUTBUILDING, code, shares.first)
            addTenantShare(House.FOREBUILDING, code, shares.second)
        }

        fun assign(code: LineCode, amount: BigDecimal, party: Party) {
            if (amount.signum() == 0) return
            if (party == Party.SPLIT_EQUALLY) {
                val (o, f) = split(amount, amount.half())
                lines.getValue(Party.OUTBUILDING).add(line(code, o))
                lines.getValue(Party.FOREBUILDING).add(line(code, f))
            } else {
                lines.getValue(party).add(line(code, amount))
            }
        }
        assign(LineCode.GENERAL_RATES, bill.generalRates, input.assignments.generalRates)
        assign(LineCode.INTEREST, bill.interest, input.assignments.interest)
        assign(LineCode.ADJUSTMENT, bill.adjustments, input.assignments.adjustments)

        val computed = bill.computedTotal().cents()
        val payable = input.billTotalPayable
        if (payable != null && (payable - computed).abs() > MISMATCH_LIMIT) {
            warnings.add(
                CalcWarning(
                    WarningCode.TOTAL_MISMATCH,
                    "The invoice lines add up to R$computed but the invoice says R$payable. Please check the numbers.",
                ),
            )
        }
        val invoiceTotal = payable ?: computed

        if (perLine) {
            val allocated = lines.values.sumOf { l -> l.sumOf { it.gross } }
            val diff = invoiceTotal - allocated
            if (diff.signum() != 0) lines.getValue(Party.OWNER).add(InvoiceLine(LineCode.ROUNDING, diff, ZERO.cents()))
            if (diff.abs() > ROUNDING_LIMIT) {
                warnings.add(CalcWarning(WarningCode.ROUNDING_LARGE, "Rounding difference of R$diff is larger than expected."))
            }
        }

        return MonthResult(
            outbuilding = PartyStatement(Party.OUTBUILDING, lines.getValue(Party.OUTBUILDING).toList()),
            forebuilding = PartyStatement(Party.FOREBUILDING, lines.getValue(Party.FOREBUILDING).toList()),
            owner = PartyStatement(Party.OWNER, lines.getValue(Party.OWNER).toList()),
            invoiceTotal = invoiceTotal,
            outbuildingFraction = fraction,
            warnings = warnings.toList(),
        )
    }

    /** Outbuilding's share of the property's water, clamped to 0..1 with a warning when the data looks wrong. */
    private fun outbuildingFraction(input: MonthInput, warnings: MutableList<CalcWarning>): BigDecimal {
        val total = input.bill.totalWaterKl
        val use = input.outbuildingKl
        if (use.signum() < 0) {
            warnings.add(CalcWarning(WarningCode.NEGATIVE_METER_USE, "Outbuilding meter use is negative."))
            return ZERO
        }
        if (total.signum() <= 0) {
            if (use.signum() > 0) {
                warnings.add(
                    CalcWarning(WarningCode.OUTBUILDING_USE_EXCEEDS_PROPERTY, "Property water use is zero but the outbuilding meter shows $use kL."),
                )
            }
            return ZERO
        }
        val f = use.divide(total, MC)
        if (f > ONE) {
            warnings.add(
                CalcWarning(
                    WarningCode.OUTBUILDING_USE_EXCEEDS_PROPERTY,
                    "Outbuilding used $use kL but the property was billed only $total kL. Check the meter reading.",
                ),
            )
            return ONE
        }
        if (use.signum() == 0 && input.occupancy.outbuildingDays > 0) {
            warnings.add(
                CalcWarning(WarningCode.ZERO_OUTBUILDING_USE, "Outbuilding meter shows no use at all this month. Was the reading updated?"),
            )
        }
        return f
    }

    /** Share (0..1) of a house's charge that the tenant pays; the rest goes to the owner. */
    private fun tenantFactor(code: LineCode, days: Int, period: Int, rules: Rules): BigDecimal = when {
        !rules.prorateFixedCharges -> ONE
        days == period -> ONE
        days == 0 -> ZERO
        code == LineCode.WATER || code == LineCode.SEWERAGE -> ONE // follows the meter, not the calendar
        else -> days.toBigDecimal().divide(period.toBigDecimal(), MC)
    }

    private fun vacancyNote(house: House, days: Int, period: Int): String =
        if (days == 0) "${house.label} vacant" else "${house.label} vacant ${period - days} of $period days"
}
