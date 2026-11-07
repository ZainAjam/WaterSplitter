package za.westend.watersplit.engine

/**
 * One tenant's stay in one house, in whole months (first and last month inclusive).
 * [to] = null means they still live there. A month's statement goes to the tenancy that covers that month.
 */
data class Tenancy(
    val id: String,
    val house: House,
    val name: String,
    val whatsapp: String,
    val from: MonthKey,
    val to: MonthKey? = null,
) {
    fun covers(month: MonthKey): Boolean {
        val end = to
        return month >= from && (end == null || month <= end)
    }

    fun overlaps(other: Tenancy): Boolean {
        val a = to
        val b = other.to
        return (a == null || other.from <= a) && (b == null || from <= b)
    }

    /** How many months of the stay have happened up to and including [now]. */
    fun monthsUpTo(now: MonthKey): Int {
        val end = to?.let { if (it > now) now else it } ?: now
        return if (end < from) 0 else (end.year - from.year) * 12 + (end.month - from.month) + 1
    }

    fun rangeLabel(): String = "${from.label()} – ${to?.label() ?: "now"}"
}

sealed interface TenantSave {
    data class Saved(val tenancies: List<Tenancy>, val note: String?) : TenantSave
    data class Rejected(val message: String) : TenantSave
}

object TenantBook {
    /** The tenant a month's statement goes to, or null when nobody is recorded for that month. */
    fun tenantFor(tenancies: List<Tenancy>, house: House, month: MonthKey): Tenancy? =
        tenancies.filter { it.house == house && it.covers(month) }.maxByOrNull { it.from }

    /** A house's tenancies, newest first. */
    fun forHouse(tenancies: List<Tenancy>, house: House): List<Tenancy> =
        tenancies.filter { it.house == house }.sortedByDescending { it.from }

    /**
     * Adds or replaces [candidate] (matched by id). Stays in one house may not overlap. The one exception: when a new
     * tenant starts after an open-ended stay began, that earlier stay is closed the month before (and a note says so).
     */
    fun save(existing: List<Tenancy>, candidate: Tenancy): TenantSave {
        if (candidate.name.isBlank()) return TenantSave.Rejected("Please enter the tenant's name.")
        val end = candidate.to
        if (end != null && end < candidate.from) {
            return TenantSave.Rejected("The last month is before the first month.")
        }
        var note: String? = null
        val others = existing.filter { it.id != candidate.id }.map { other ->
            if (other.house == candidate.house && other.overlaps(candidate)) {
                if (other.to == null && other.from < candidate.from) {
                    val newEnd = candidate.from.previous()
                    note = "${other.name}'s stay now ends in ${newEnd.label()}."
                    other.copy(to = newEnd)
                } else {
                    return TenantSave.Rejected(
                        "The ${candidate.house.label.lowercase()} already has ${other.name} for ${other.rangeLabel()}. " +
                            "Change the dates so the stays don't overlap.",
                    )
                }
            } else {
                other
            }
        }
        return TenantSave.Saved((others + candidate).sortedWith(compareBy({ it.house }, { it.from })), note)
    }

    fun remove(existing: List<Tenancy>, id: String): List<Tenancy> = existing.filter { it.id != id }
}
