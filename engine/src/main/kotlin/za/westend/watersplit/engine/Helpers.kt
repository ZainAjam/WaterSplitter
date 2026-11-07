package za.westend.watersplit.engine

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** Reads numbers the way people type them in South Africa (comma or dot decimals, spaces, "R" prefix). */
object Parse {
    fun decimal(text: String): BigDecimal? {
        var s = text.trim().replace('−', '-') // unicode minus
        if (s.startsWith("R", ignoreCase = true)) s = s.substring(1)
        s = s.replace(" ", "").replace(" ", "")
        if (s.isEmpty()) return null
        s = if (s.contains(',') && s.contains('.')) s.replace(",", "") else s.replace(',', '.')
        return s.toBigDecimalOrNull()
    }
}

/** Display formatting: "R 1 234.56" with non-breaking spaces so amounts never wrap. */
object Fmt {
    private const val NBSP = " "

    fun amount(x: BigDecimal): String {
        val c = x.cents()
        val parts = c.abs().toPlainString().split('.')
        val grouped = parts[0].reversed().chunked(3).joinToString(NBSP).reversed()
        return (if (c.signum() < 0) "-" else "") + grouped + "." + parts[1]
    }

    fun rand(x: BigDecimal): String {
        val a = amount(x)
        return if (a.startsWith("-")) "-R$NBSP${a.drop(1)}" else "R$NBSP$a"
    }

    fun kl(x: BigDecimal): String = x.setScale(4, RoundingMode.HALF_UP).toPlainString() + " kL"
}

object Phone {
    /**
     * Digits-only international number for WhatsApp, or null when the input doesn't look like a phone number.
     * "082 123 4567", "+27 82 123 4567", "27821234567" and "821234567" all give "27821234567".
     */
    fun whatsappNumber(input: String, countryCode: String = "27"): String? {
        val hadPlus = input.trim().startsWith("+")
        var d = input.filter { it.isDigit() }
        if (d.startsWith("00")) d = d.drop(2)
        return when {
            d.isEmpty() -> null
            d.startsWith(countryCode) && d.length == countryCode.length + 9 -> d
            d.startsWith("0") && d.length == 10 -> countryCode + d.drop(1)
            d.length == 9 && !hadPlus -> countryCode + d
            d.length in 10..15 && !d.startsWith("0") && (hadPlus || input.trim().startsWith("00")) -> d
            else -> null
        }
    }
}

data class MonthKey(val year: Int, val month: Int) : Comparable<MonthKey> {
    init {
        require(month in 1..12) { "month must be 1..12" }
    }

    /** Sortable text form, e.g. "2026-09". */
    val key: String get() = String.format(Locale.ROOT, "%04d-%02d", year, month)

    fun label(): String = Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + year

    fun daysInMonth(): Int = YearMonth.of(year, month).lengthOfMonth()

    fun next(): MonthKey = if (month == 12) MonthKey(year + 1, 1) else MonthKey(year, month + 1)

    fun previous(): MonthKey = if (month == 1) MonthKey(year - 1, 12) else MonthKey(year, month - 1)

    override fun compareTo(other: MonthKey): Int = compareValuesBy(this, other, { it.year }, { it.month })

    companion object {
        /** This calendar month. */
        fun current(): MonthKey = java.time.LocalDate.now().let { MonthKey(it.year, it.monthValue) }

        fun parse(key: String): MonthKey {
            val p = key.split('-')
            require(p.size == 2) { "Bad month key: $key" }
            return MonthKey(p[0].toInt(), p[1].toInt())
        }
    }
}
