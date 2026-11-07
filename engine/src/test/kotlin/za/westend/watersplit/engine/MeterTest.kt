package za.westend.watersplit.engine

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MeterTest {
    private fun d(s: String) = BigDecimal(s)

    @Test
    fun `normal month`() {
        val u = Meter.consumption(d("72.3937"), d("73.8060"))
        assertCents(d("1.4123"), u.kl, "kL")
        assertTrue(u.warnings.isEmpty())
    }

    @Test
    fun `reading lower than last month is flagged and treated as zero`() {
        val u = Meter.consumption(d("75.0000"), d("73.8060"))
        assertCents(d("0"), u.kl, "kL")
        assertTrue(u.warnings.any { it.code == WarningCode.READING_LOWER_THAN_PREVIOUS })
    }

    @Test
    fun `meter replaced mid month adds the old and new parts`() {
        val u = Meter.consumption(
            previous = d("1382.8716"),
            current = d("2.5000"),
            replacement = MeterReplacement(oldMeterFinalReading = d("1382.9000"), newMeterStartReading = d("0.0000")),
        )
        assertCents(d("2.5284"), u.kl, "kL")
        assertTrue(u.warnings.isEmpty())
    }

    @Test
    fun `inconsistent replacement readings are flagged`() {
        val u = Meter.consumption(
            previous = d("1382.8716"),
            current = d("0.2000"),
            replacement = MeterReplacement(oldMeterFinalReading = d("1380.0000"), newMeterStartReading = d("0.0000")),
        )
        assertTrue(u.warnings.any { it.code == WarningCode.REPLACEMENT_READINGS_INCONSISTENT })
    }
}
