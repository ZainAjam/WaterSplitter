package za.westend.watersplit.engine

import java.math.BigDecimal

enum class House(val label: String) {
    OUTBUILDING("Outbuilding"),
    FOREBUILDING("Forebuilding"),
}

/** Who ends up paying a charge. [SPLIT_EQUALLY] is only valid as an assignment, never as a statement owner. */
enum class Party { OWNER, OUTBUILDING, FOREBUILDING, SPLIT_EQUALLY }

enum class LineCode(val label: String, val vatable: Boolean = true) {
    WATER("Water consumed"),
    WATER_AVAILABILITY("Water availability charge"),
    SEWERAGE("Sewerage"),
    SEWERAGE_AVAILABILITY("Sewerage availability charge"),
    REFUSE("Refuse removal"),
    GENERAL_RATES("General rates", vatable = false),
    INTEREST("Interest", vatable = false),

    /** Balance brought forward / small overpayment carry-over / any municipal discount. Negative = credit. */
    ADJUSTMENT("Balance brought forward / adjustment", vatable = false),

    /** Cents left over so that the three statements add up exactly to the municipal total. Owner only. */
    ROUNDING("Rounding", vatable = false),
}

/**
 * The amounts on one municipal invoice. All amounts are in rand and EXCLUDE VAT
 * (VAT is recalculated at 15% on every vatable line, as the old sheet did).
 */
data class MunicipalBill(
    /** Kilolitres billed on the property meter this month. */
    val totalWaterKl: BigDecimal,
    val water: BigDecimal,
    val waterAvailability: BigDecimal,
    val sewerage: BigDecimal,
    val sewerageAvailability: BigDecimal,
    val refuse: BigDecimal,
    val generalRates: BigDecimal = BigDecimal.ZERO,
    val interest: BigDecimal = BigDecimal.ZERO,
    /** Balance brought forward net of payments, discounts, etc. Negative = credit. */
    val adjustments: BigDecimal = BigDecimal.ZERO,
) {
    val vatableNet: BigDecimal get() = water + waterAvailability + sewerage + sewerageAvailability + refuse

    /** What the invoice should add up to, from its parts. */
    fun computedTotal(): BigDecimal =
        vatableNet.multiply(BigDecimal.ONE + VAT_RATE) + generalRates + interest + adjustments
}

/** Days each house was occupied during the billing period (used only when [Rules.prorateFixedCharges]). */
data class Occupancy(val daysInPeriod: Int, val outbuildingDays: Int, val forebuildingDays: Int) {
    init {
        require(daysInPeriod > 0) { "daysInPeriod must be positive" }
        require(outbuildingDays in 0..daysInPeriod) { "outbuildingDays must be between 0 and $daysInPeriod" }
        require(forebuildingDays in 0..daysInPeriod) { "forebuildingDays must be between 0 and $daysInPeriod" }
    }

    fun days(house: House): Int = when (house) {
        House.OUTBUILDING -> outbuildingDays
        House.FOREBUILDING -> forebuildingDays
    }

    companion object {
        fun full(daysInPeriod: Int = 30) = Occupancy(daysInPeriod, daysInPeriod, daysInPeriod)
    }
}

/** Who pays the lines that are not tied to a house. Everything defaults to the owner. */
data class Assignments(
    val generalRates: Party = Party.OWNER,
    val interest: Party = Party.OWNER,
    val adjustments: Party = Party.OWNER,
)

enum class SewerageBasis {
    /** Old sheet: sewerage split 50/50. */
    HALF_EACH,

    /** Current: sewerage follows the same water-use share as the water charge. */
    BY_WATER_USE,
}

enum class RoundingPolicy {
    /** Old sheet: keep full precision on every line and round only the final totals. */
    ROUND_TOTALS_ONLY,

    /** Current: round every printed line (and its VAT) to cents so the invoice adds up exactly. */
    ROUND_EACH_LINE,
}

data class Rules(
    val sewerageBasis: SewerageBasis,
    val rounding: RoundingPolicy,
    /** Prorate availability/refuse charges by days occupied; empty days go to the owner. */
    val prorateFixedCharges: Boolean,
) {
    companion object {
        /** Exactly what the Google Sheet did until October 2026. Kept so history can be replayed. */
        val SHEET_V1 = Rules(SewerageBasis.HALF_EACH, RoundingPolicy.ROUND_TOTALS_ONLY, prorateFixedCharges = false)

        /** Rules for all new months. */
        val CURRENT = Rules(SewerageBasis.BY_WATER_USE, RoundingPolicy.ROUND_EACH_LINE, prorateFixedCharges = true)
    }
}

data class MonthInput(
    val bill: MunicipalBill,
    /** Kilolitres used through the outbuilding's own meter this month. */
    val outbuildingKl: BigDecimal,
    val occupancy: Occupancy = Occupancy.full(),
    val assignments: Assignments = Assignments(),
    /** "Total payable" printed on the municipal invoice, if known. Used to tie out and to absorb rounding. */
    val billTotalPayable: BigDecimal? = null,
)

data class InvoiceLine(
    val code: LineCode,
    val net: BigDecimal,
    val vat: BigDecimal,
    val note: String? = null,
) {
    val gross: BigDecimal get() = net + vat
}

data class PartyStatement(val party: Party, val lines: List<InvoiceLine>) {
    val total: BigDecimal get() = lines.sumOf { it.gross }.cents()
}

enum class WarningCode {
    OUTBUILDING_USE_EXCEEDS_PROPERTY,
    NEGATIVE_METER_USE,
    ZERO_OUTBUILDING_USE,
    TOTAL_MISMATCH,
    ROUNDING_LARGE,
    READING_LOWER_THAN_PREVIOUS,
    REPLACEMENT_READINGS_INCONSISTENT,
}

data class CalcWarning(val code: WarningCode, val message: String)

data class MonthResult(
    val outbuilding: PartyStatement,
    val forebuilding: PartyStatement,
    val owner: PartyStatement,
    /** Municipal total these three statements add up to. */
    val invoiceTotal: BigDecimal,
    /** Outbuilding's share of the water (0..1). */
    val outbuildingFraction: BigDecimal,
    val warnings: List<CalcWarning>,
)
