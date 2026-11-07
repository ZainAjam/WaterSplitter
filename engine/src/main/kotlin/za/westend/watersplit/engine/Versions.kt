package za.westend.watersplit.engine

object Versions {
    /** "v1.2.3", "1.2" or "1.2.3-beta" become [1,2,3] / [1,2] / [1,2,3]; anything not numeric gives null. */
    fun parse(version: String): List<Int>? {
        val core = version.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
        if (core.isEmpty()) return null
        val parts = core.split('.').map { it.toIntOrNull() ?: return null }
        return parts
    }

    /** True when [candidate] is a higher version than [installed]. Unreadable versions are never "newer". */
    fun isNewer(candidate: String, installed: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(installed) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** "12.3 MB" for a byte count. */
    fun size(bytes: Long): String = when {
        bytes >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(java.util.Locale.ROOT, "%d KB", bytes / 1_000)
        else -> "$bytes bytes"
    }
}
