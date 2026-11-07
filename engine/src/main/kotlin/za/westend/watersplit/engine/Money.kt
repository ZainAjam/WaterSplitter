package za.westend.watersplit.engine

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Working precision. Money is only rounded where the active [Rules] say so. */
internal val MC: MathContext = MathContext(34, RoundingMode.HALF_UP)

/** South African VAT. */
val VAT_RATE: BigDecimal = BigDecimal("0.15")

private val TWO: BigDecimal = BigDecimal(2)

/** Round to whole cents, half up (how the municipality and the old sheet display amounts). */
fun BigDecimal.cents(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

internal fun BigDecimal.half(): BigDecimal = divide(TWO, MC)
