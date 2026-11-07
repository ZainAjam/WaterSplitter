package za.westend.watersplit.engine

import java.math.BigDecimal

/** Everything typed for one month while it is still a draft. Kept as text exactly as typed. */
data class DraftFields(
    val totalKl: String = "",
    val water: String = "",
    val avail: String = "",
    val sewer: String = "",
    val sewAvail: String = "",
    val refuse: String = "",
    val rates: String = "",
    val interest: String = "",
    val adjustments: String = "",
    val payable: String = "",
    val prev: String = "",
    /** True once the previous reading was typed by hand (so it is no longer auto-suggested). */
    val prevEdited: Boolean = false,
    val curr: String = "",
    val replaced: Boolean = false,
    val oldFinal: String = "",
    val newStart: String = "",
    val fullMonth: Boolean = true,
    val outbDays: String = "",
    val foreDays: String = "",
    val interestTo: Party = Party.OWNER,
    val adjustmentsTo: Party = Party.OWNER,
)

/** A fully valid draft, ready to become a calculated month. */
data class ParsedDraft(
    val totalKl: BigDecimal,
    val water: BigDecimal,
    val waterAvailability: BigDecimal,
    val sewerage: BigDecimal,
    val sewerageAvailability: BigDecimal,
    val refuse: BigDecimal,
    val generalRates: BigDecimal,
    val interest: BigDecimal,
    val adjustments: BigDecimal,
    val payable: BigDecimal?,
    val prevReading: BigDecimal,
    val currReading: BigDecimal,
    val meterUse: MeterUse,
    val oldMeterFinal: BigDecimal?,
    val newMeterStart: BigDecimal?,
    val outbuildingDays: Int,
    val forebuildingDays: Int,
)

/** What is still missing or wrong in a draft. Statements may only be sent once [ready]. */
data class DraftCheck(
    val invoiceMissing: List<String>,
    val invoiceProblems: List<String>,
    val meterMissing: List<String>,
    val meterProblems: List<String>,
    val otherProblems: List<String>,
    /** Outbuilding use so far, when both readings are filled in (even if the rest is not). */
    val meterUse: MeterUse?,
    val parsed: ParsedDraft?,
) {
    val invoiceCaptured: Boolean get() = invoiceMissing.isEmpty() && invoiceProblems.isEmpty()
    val meterCaptured: Boolean get() = meterMissing.isEmpty() && meterProblems.isEmpty()
    val ready: Boolean get() = parsed != null

    /** Things that are wrong (as opposed to merely not filled in yet). */
    fun problems(): List<String> = invoiceProblems + meterProblems + otherProblems
}

object DraftChecker {
    /** How many invoice numbers must be captured (property kL, water, 2 availability charges, sewerage, refuse, rates). */
    const val INVOICE_FIELDS = 7

    fun check(f: DraftFields, daysInMonth: Int): DraftCheck {
        val invoiceMissing = mutableListOf<String>()
        val invoiceProblems = mutableListOf<String>()
        val meterMissing = mutableListOf<String>()
        val meterProblems = mutableListOf<String>()
        val other = mutableListOf<String>()

        fun required(into: MutableList<String>, label: String, text: String): BigDecimal? =
            Parse.decimal(text).also { if (it == null) into.add(label) }

        fun optional(label: String, text: String): BigDecimal {
            if (text.isBlank()) return BigDecimal.ZERO
            return Parse.decimal(text) ?: BigDecimal.ZERO.also { invoiceProblems.add("\"$label\" is not a number.") }
        }

        val totalKl = required(invoiceMissing, "property water use", f.totalKl)
        val water = required(invoiceMissing, "water", f.water)
        val avail = required(invoiceMissing, "water availability", f.avail)
        val sewer = required(invoiceMissing, "sewerage", f.sewer)
        val sewAvail = required(invoiceMissing, "sewerage availability", f.sewAvail)
        val refuse = required(invoiceMissing, "refuse", f.refuse)
        val rates = required(invoiceMissing, "general rates", f.rates)
        val interest = optional("Interest", f.interest)
        val adjustments = optional("Balance brought forward", f.adjustments)
        val payable: BigDecimal? = if (f.payable.isBlank()) {
            null
        } else {
            Parse.decimal(f.payable).also { if (it == null) invoiceProblems.add("\"Total payable\" is not a number.") }
        }

        val prev = required(meterMissing, "previous meter reading", f.prev)
        val curr = required(meterMissing, "meter reading now", f.curr)
        val oldFinal = if (f.replaced) required(meterMissing, "last reading of the old meter", f.oldFinal) else null
        val newStart = if (f.replaced) required(meterMissing, "first reading of the new meter", f.newStart) else null

        var meterUse: MeterUse? = null
        if (prev != null && curr != null && (!f.replaced || (oldFinal != null && newStart != null))) {
            val use = if (f.replaced) {
                Meter.consumption(prev, curr, MeterReplacement(oldFinal!!, newStart!!))
            } else {
                Meter.consumption(prev, curr)
            }
            meterUse = use
            if (use.warnings.any { it.code == WarningCode.READING_LOWER_THAN_PREVIOUS }) {
                meterProblems.add("The meter reading is lower than the previous one. If the meter was replaced, switch that on; otherwise check the reading.")
            }
            if (use.warnings.any { it.code == WarningCode.REPLACEMENT_READINGS_INCONSISTENT }) {
                meterProblems.add("The readings around the meter replacement don't add up.")
            }
        }

        var outbDays = daysInMonth
        var foreDays = daysInMonth
        if (!f.fullMonth) {
            val o = f.outbDays.trim().toIntOrNull()
            val fo = f.foreDays.trim().toIntOrNull()
            if (o == null || o !in 0..daysInMonth || fo == null || fo !in 0..daysInMonth) {
                other.add("Enter the days occupied as whole numbers from 0 to $daysInMonth.")
            } else {
                outbDays = o
                foreDays = fo
            }
        }

        val allClear = invoiceMissing.isEmpty() && invoiceProblems.isEmpty() && meterMissing.isEmpty() &&
            meterProblems.isEmpty() && other.isEmpty()
        val parsed = if (allClear) {
            ParsedDraft(
                totalKl = totalKl!!,
                water = water!!,
                waterAvailability = avail!!,
                sewerage = sewer!!,
                sewerageAvailability = sewAvail!!,
                refuse = refuse!!,
                generalRates = rates!!,
                interest = interest,
                adjustments = adjustments,
                payable = payable,
                prevReading = prev!!,
                currReading = curr!!,
                meterUse = meterUse!!,
                oldMeterFinal = oldFinal,
                newMeterStart = newStart,
                outbuildingDays = outbDays,
                forebuildingDays = foreDays,
            )
        } else {
            null
        }

        return DraftCheck(invoiceMissing, invoiceProblems, meterMissing, meterProblems, other, meterUse, parsed)
    }
}
