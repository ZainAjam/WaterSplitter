package za.westend.watersplit.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TenantBookTest {
    private fun m(y: Int, mo: Int) = MonthKey(y, mo)
    private fun t(id: String, house: House, from: MonthKey, to: MonthKey? = null, name: String = id) =
        Tenancy(id, house, name, "0820000000", from, to)

    private val anna = t("anna", House.OUTBUILDING, m(2023, 7), m(2025, 3), "Anna")
    private val ben = t("ben", House.OUTBUILDING, m(2025, 4), null, "Ben")
    private val cara = t("cara", House.FOREBUILDING, m(2020, 1), null, "Cara")

    @Test
    fun `a month is invoiced to the tenant who covers it`() {
        val all = listOf(anna, ben, cara)
        assertEquals("Anna", TenantBook.tenantFor(all, House.OUTBUILDING, m(2024, 1))?.name)
        assertEquals("Anna", TenantBook.tenantFor(all, House.OUTBUILDING, m(2025, 3))?.name)
        assertEquals("Ben", TenantBook.tenantFor(all, House.OUTBUILDING, m(2025, 4))?.name)
        assertEquals("Ben", TenantBook.tenantFor(all, House.OUTBUILDING, m(2030, 1))?.name)
        assertEquals("Cara", TenantBook.tenantFor(all, House.FOREBUILDING, m(2024, 1))?.name)
        assertNull(TenantBook.tenantFor(all, House.OUTBUILDING, m(2023, 6)))
    }

    @Test
    fun `months occupied are counted inclusively and never past today`() {
        assertEquals(21, anna.monthsUpTo(m(2026, 11)))
        assertEquals(20, ben.monthsUpTo(m(2026, 11)))
        assertEquals(1, ben.monthsUpTo(m(2025, 4)))
        assertEquals(0, ben.monthsUpTo(m(2025, 3)))
        assertEquals("July 2023 – March 2025", anna.rangeLabel())
        assertEquals("April 2025 – now", ben.rangeLabel())
    }

    @Test
    fun `a new tenant closes the previous open-ended stay`() {
        val newcomer = t("dan", House.OUTBUILDING, m(2026, 11), null, "Dan")
        val r = TenantBook.save(listOf(anna, ben, cara), newcomer) as TenantSave.Saved
        val benNow = r.tenancies.first { it.id == "ben" }
        assertEquals(m(2026, 10), benNow.to)
        assertTrue(r.note!!.contains("Ben"))
        assertEquals("Dan", TenantBook.tenantFor(r.tenancies, House.OUTBUILDING, m(2026, 11))?.name)
        assertEquals("Ben", TenantBook.tenantFor(r.tenancies, House.OUTBUILDING, m(2026, 10))?.name)
    }

    @Test
    fun `stays in the same house may not overlap`() {
        val clash = t("eve", House.OUTBUILDING, m(2024, 1), m(2024, 6), "Eve")
        val r = TenantBook.save(listOf(anna, ben), clash)
        assertTrue(r is TenantSave.Rejected)
        assertTrue((r as TenantSave.Rejected).message.contains("Anna"))
        // same dates in the other house are fine
        assertTrue(TenantBook.save(listOf(anna, ben, cara), t("eve2", House.FOREBUILDING, m(2019, 1), m(2019, 12))) is TenantSave.Saved)
    }

    @Test
    fun `history can be filled in for earlier tenants`() {
        val old = t("old", House.OUTBUILDING, m(2022, 1), m(2023, 6), "Old")
        val r = TenantBook.save(listOf(anna, ben), old)
        assertTrue(r is TenantSave.Saved)
    }

    @Test
    fun `editing a tenant keeps its id and checks the dates`() {
        val edited = anna.copy(name = "Anna K", to = m(2025, 2))
        val r = TenantBook.save(listOf(anna, ben), edited) as TenantSave.Saved
        assertEquals(2, r.tenancies.size)
        assertEquals("Anna K", r.tenancies.first { it.id == "anna" }.name)
        assertTrue(TenantBook.save(listOf(anna), anna.copy(to = m(2023, 1))) is TenantSave.Rejected)
        assertTrue(TenantBook.save(listOf(anna), anna.copy(name = "  ")) is TenantSave.Rejected)
    }

    @Test
    fun `an open-ended stay that starts before a later one is rejected, not silently shortened`() {
        // adding an open-ended 2020 stay would swallow Anna and Ben
        val swallow = t("fay", House.OUTBUILDING, m(2020, 1), null, "Fay")
        assertTrue(TenantBook.save(listOf(anna, ben), swallow) is TenantSave.Rejected)
    }

    @Test
    fun `houses are listed newest stay first`() {
        val list = TenantBook.forHouse(listOf(anna, ben, cara), House.OUTBUILDING)
        assertEquals(listOf("ben", "anna"), list.map { it.id })
        assertFalse(list.any { it.house != House.OUTBUILDING })
    }
}
