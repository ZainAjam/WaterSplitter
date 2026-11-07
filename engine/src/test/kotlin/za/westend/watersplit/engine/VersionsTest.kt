package za.westend.watersplit.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionsTest {
    @Test
    fun `versions are read from tags and names`() {
        assertEquals(listOf(1, 2, 3), Versions.parse("v1.2.3"))
        assertEquals(listOf(1, 2), Versions.parse("1.2"))
        assertEquals(listOf(0, 3, 0), Versions.parse("0.3.0-beta+7"))
        assertNull(Versions.parse("latest"))
        assertNull(Versions.parse(""))
    }

    @Test
    fun `newer means strictly higher`() {
        assertTrue(Versions.isNewer("v0.2.0", "0.1.0"))
        assertTrue(Versions.isNewer("1.0.0", "0.9.9"))
        assertTrue(Versions.isNewer("0.10.0", "0.9.0")) // numbers, not text
        assertTrue(Versions.isNewer("1.0.1", "1.0"))
        assertFalse(Versions.isNewer("1.0", "1.0.0"))
        assertFalse(Versions.isNewer("0.1.0", "0.1.0"))
        assertFalse(Versions.isNewer("0.0.9", "0.1.0"))
        assertFalse(Versions.isNewer("garbage", "0.1.0"))
        assertFalse(Versions.isNewer("1.0.0", "garbage"))
    }

    @Test
    fun `sizes read well`() {
        assertEquals("12.3 MB", Versions.size(12_300_000))
        assertEquals("850 KB", Versions.size(850_000))
        assertEquals("120 bytes", Versions.size(120))
    }
}
