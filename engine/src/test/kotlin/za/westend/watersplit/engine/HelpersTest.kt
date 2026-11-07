package za.westend.watersplit.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class HelpersTest {
    private fun d(s: String) = BigDecimal(s)

    @Test
    fun `parses numbers as typed in South Africa`() {
        assertEquals(0, d("1234.56").compareTo(Parse.decimal("1234.56")))
        assertEquals(0, d("1234.56").compareTo(Parse.decimal("1234,56")))
        assertEquals(0, d("1234.56").compareTo(Parse.decimal("R 1 234,56")))
        assertEquals(0, d("1234.56").compareTo(Parse.decimal("1,234.56")))
        assertEquals(0, d("-18.10").compareTo(Parse.decimal("-18,10")))
        assertEquals(0, d("-18.10").compareTo(Parse.decimal("−18.10")))
        assertNull(Parse.decimal(""))
        assertNull(Parse.decimal("  "))
        assertNull(Parse.decimal("abc"))
        assertNull(Parse.decimal("1.234.56"))
    }

    @Test
    fun `formats rand with grouped thousands`() {
        assertEquals("R 1 234.56", Fmt.rand(d("1234.56")))
        assertEquals("R 316.92", Fmt.rand(d("316.92")))
        assertEquals("-R 18.10", Fmt.rand(d("-18.1")))
        assertEquals("R 0.00", Fmt.rand(BigDecimal.ZERO))
        assertEquals("1 000 000.00", Fmt.amount(d("1000000")))
        assertEquals("1.4123 kL", Fmt.kl(d("1.41230")))
    }

    @Test
    fun `normalises South African WhatsApp numbers`() {
        assertEquals("27821234567", Phone.whatsappNumber("082 123 4567"))
        assertEquals("27821234567", Phone.whatsappNumber("+27 82 123 4567"))
        assertEquals("27821234567", Phone.whatsappNumber("27821234567"))
        assertEquals("27821234567", Phone.whatsappNumber("0027 82 123 4567"))
        assertEquals("27821234567", Phone.whatsappNumber("821234567"))
        assertEquals("447700900123", Phone.whatsappNumber("+44 7700 900123"))
        assertNull(Phone.whatsappNumber(""))
        assertNull(Phone.whatsappNumber("12345"))
        assertNull(Phone.whatsappNumber("not a number"))
    }

    @Test
    fun `month keys sort label and roll over`() {
        val m = MonthKey.parse("2026-09")
        assertEquals("2026-09", m.key)
        assertEquals("September 2026", m.label())
        assertEquals(30, m.daysInMonth())
        assertEquals(MonthKey(2026, 10), m.next())
        assertEquals(MonthKey(2027, 1), MonthKey(2026, 12).next())
        assertEquals(MonthKey(2025, 12), MonthKey(2026, 1).previous())
        assertEquals(29, MonthKey(2028, 2).daysInMonth())
        assert(MonthKey(2026, 9) < MonthKey(2026, 10))
    }
}
