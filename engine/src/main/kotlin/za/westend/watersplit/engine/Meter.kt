package za.westend.watersplit.engine

import java.math.BigDecimal
import java.math.BigDecimal.ZERO

/** Set when the outbuilding meter was swapped during the month. */
data class MeterReplacement(
    /** Last reading taken from the old meter. */
    val oldMeterFinalReading: BigDecimal,
    /** First reading of the new meter (usually 0, but new meters sometimes start higher). */
    val newMeterStartReading: BigDecimal,
)

data class MeterUse(val kl: BigDecimal, val warnings: List<CalcWarning>)

object Meter {
    /** Kilolitres used between two readings of the outbuilding meter. */
    fun consumption(previous: BigDecimal, current: BigDecimal, replacement: MeterReplacement? = null): MeterUse {
        val warnings = mutableListOf<CalcWarning>()
        if (replacement == null) {
            if (current < previous) {
                warnings.add(
                    CalcWarning(
                        WarningCode.READING_LOWER_THAN_PREVIOUS,
                        "The new reading ($current) is lower than last month's ($previous). Was the meter replaced?",
                    ),
                )
                return MeterUse(ZERO, warnings)
            }
            return MeterUse(current - previous, warnings)
        }
        val oldPart = replacement.oldMeterFinalReading - previous
        val newPart = current - replacement.newMeterStartReading
        if (oldPart.signum() < 0 || newPart.signum() < 0) {
            warnings.add(
                CalcWarning(
                    WarningCode.REPLACEMENT_READINGS_INCONSISTENT,
                    "The readings around the meter replacement don't add up. Please check them.",
                ),
            )
        }
        return MeterUse(oldPart.max(ZERO) + newPart.max(ZERO), warnings)
    }
}
