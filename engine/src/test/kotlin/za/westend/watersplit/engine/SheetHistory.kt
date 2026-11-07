package za.westend.watersplit.engine

import org.junit.jupiter.api.Assertions.assertEquals
import java.math.BigDecimal

/** One month copied from the old sheet, with what the sheet displayed. */
data class HistoryRow(
    val month: String,
    val outbKl: BigDecimal,
    val totalKl: BigDecimal,
    val water: BigDecimal,
    val avail: BigDecimal,
    val sewer: BigDecimal,
    val sewAvail: BigDecimal,
    val refuse: BigDecimal,
    val genRates: BigDecimal,
    val interest: BigDecimal,
    val discounts: BigDecimal,
    val expInvoice: BigDecimal,
    val expOutb: BigDecimal,
    val expFore: BigDecimal,
    val expOwner: BigDecimal,
) {
    fun toBill() = MunicipalBill(
        totalWaterKl = totalKl,
        water = water,
        waterAvailability = avail,
        sewerage = sewer,
        sewerageAvailability = sewAvail,
        refuse = refuse,
        generalRates = genRates,
        interest = interest,
        adjustments = discounts,
    )

    fun toInput(billTotalPayable: BigDecimal? = null) =
        MonthInput(bill = toBill(), outbuildingKl = outbKl, billTotalPayable = billTotalPayable)
}

object SheetHistory {
    val rows: List<HistoryRow> by lazy {
        // The real file is private (not in the repository): without it these checks simply have nothing to replay.
        val stream = SheetHistory::class.java.getResourceAsStream("/sheet-history.csv") ?: return@lazy emptyList()
        val text = stream.bufferedReader().use { it.readText() }
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .drop(1) // header
            .map { line ->
                val c = line.split(',').map { it.trim() }
                require(c.size == 15) { "Bad history line: $line" }
                HistoryRow(
                    c[0], d(c[1]), d(c[2]), d(c[3]), d(c[4]), d(c[5]), d(c[6]), d(c[7]),
                    d(c[8]), d(c[9]), d(c[10]), d(c[11]), d(c[12]), d(c[13]), d(c[14]),
                )
            }
            .toList()
    }

    private fun d(s: String) = BigDecimal(s)
}

fun assertCents(expected: BigDecimal, actual: BigDecimal, what: String) {
    assertEquals(0, expected.compareTo(actual), "$what: expected $expected but was $actual")
}
