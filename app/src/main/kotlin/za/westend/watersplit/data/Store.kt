package za.westend.watersplit.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import za.westend.watersplit.engine.DraftChecker
import za.westend.watersplit.engine.House
import za.westend.watersplit.engine.InvoiceReading
import za.westend.watersplit.engine.MeterReading
import za.westend.watersplit.engine.MonthKey
import za.westend.watersplit.engine.MonthStatus
import za.westend.watersplit.engine.Parse
import za.westend.watersplit.engine.Party
import za.westend.watersplit.engine.Tenancy
import za.westend.watersplit.engine.TenantBook
import za.westend.watersplit.engine.TenantSave
import java.io.File
import java.math.BigDecimal
import java.util.UUID

/** Where the "previous reading" suggestion came from, so the screen can say so. */
data class PreviousReading(val value: BigDecimal, val source: String)

/** What a given month currently is. A month can only be one of these at a time. */
enum class Occupant { NONE, DRAFT, COMPLETE }

/**
 * All app data in one JSON file in the app's private storage, plus the attached invoices/meter photos under
 * `attachments/<month>/` (both are included in Android's automatic backup).
 * Observable from Compose: [settings], [months] and [drafts] trigger recomposition when they change.
 */
class Store(private val context: Context) {
    private val file = File(context.filesDir, "data.json")

    var settings by mutableStateOf(Settings())
        private set

    /** Calculated months, oldest first. */
    var months by mutableStateOf<List<MonthRecord>>(emptyList())
        private set

    /** Months still being put together, oldest first. */
    var drafts by mutableStateOf<List<Draft>>(emptyList())
        private set

    /** Who lived in each house and when. A month's statements go to the tenant recorded for that month. */
    var tenancies by mutableStateOf<List<Tenancy>>(emptyList())
        private set

    init {
        load()
    }

    /** Reads everything again from the data file (after a backup was restored over it). */
    fun reloadFromDisk() {
        load()
    }

    // ---- tenants ----

    /** The tenant a month's statement goes to, or null when nobody is recorded for that house that month. */
    fun tenantFor(house: House, key: String): Tenancy? = TenantBook.tenantFor(tenancies, house, MonthKey.parse(key))

    /** Adds or edits a tenancy. Returns the outcome so the screen can show a note or the reason it was refused. */
    fun saveTenancy(candidate: Tenancy): TenantSave {
        val result = TenantBook.save(tenancies, candidate)
        if (result is TenantSave.Saved) {
            tenancies = result.tenancies
            persist()
        }
        return result
    }

    fun deleteTenancy(id: String) {
        tenancies = TenantBook.remove(tenancies, id)
        persist()
    }

    fun newTenancyId(): String = UUID.randomUUID().toString()

    /**
     * Older versions kept just one "current" tenant per house in Settings. Turn those into open-ended tenancies that
     * start at the first month worked on in the app (so nothing typed earlier is lost); they can be edited afterwards.
     */
    private fun migrateTenantsFromSettings(): List<Tenancy> {
        val start = (months.filter { !it.importedFromSheet }.map { it.key } + drafts.map { it.key }).minOrNull()
            ?.let { MonthKey.parse(it) } ?: nextFreeMonth()
        fun make(house: House, info: HouseInfo): Tenancy? =
            if (info.tenantName.isBlank() && info.whatsapp.isBlank()) {
                null
            } else {
                Tenancy(newTenancyId(), house, info.tenantName.ifBlank { "${house.label} tenant" }, info.whatsapp, start, null)
            }
        return listOfNotNull(make(House.OUTBUILDING, settings.outbuilding), make(House.FOREBUILDING, settings.forebuilding))
    }

    fun month(key: String): MonthRecord? = months.firstOrNull { it.key == key }

    fun draft(key: String): Draft? = drafts.firstOrNull { it.key == key }

    fun occupant(key: String): Occupant = when {
        month(key) != null -> Occupant.COMPLETE
        draft(key) != null -> Occupant.DRAFT
        else -> Occupant.NONE
    }

    /** The month after the latest one we have (calculated or draft). */
    fun nextFreeMonth(): MonthKey {
        val latest = (months.map { it.key } + drafts.map { it.key }).maxOrNull()
        return latest?.let { MonthKey.parse(it).next() } ?: MonthKey(2026, 11)
    }

    // ---- drafts ----

    fun newDraft(key: String): Draft {
        val d = Draft(key)
        saveDraft(d)
        return d
    }

    fun saveDraft(d: Draft) {
        drafts = (drafts.filter { it.key != d.key } + d).sortedBy { it.key }
        persist()
    }

    fun deleteDraft(key: String) {
        drafts = drafts.filter { it.key != key }
        attachmentDir(key).deleteRecursively()
        persist()
    }

    /** Moves a draft to another (free) month, taking its attachments along. */
    fun renameDraft(old: String, new: String): Boolean {
        val d = draft(old) ?: return false
        if (old == new || occupant(new) != Occupant.NONE) return false
        val from = attachmentDir(old)
        if (from.exists() && !from.renameTo(attachmentDir(new))) return false
        drafts = (drafts.filter { it.key != old } + d.copy(key = new)).sortedBy { it.key }
        persist()
        return true
    }

    /** The draft is complete: it becomes a calculated month (attachments stay with it). */
    fun completeDraft(record: MonthRecord) {
        months = (months.filter { it.key != record.key } + record).sortedBy { it.key }
        drafts = drafts.filter { it.key != record.key }
        persist()
    }

    /** "Edit the numbers": a calculated month goes back to being a draft with its numbers and attachments. */
    fun reopenAsDraft(key: String): Draft? {
        val r = month(key) ?: return null
        if (r.importedFromSheet) return null
        val d = Draft(key, r.invoice, r.meter, r.toFields())
        months = months.filter { it.key != key }
        drafts = (drafts.filter { it.key != key } + d).sortedBy { it.key }
        persist()
        return d
    }

    /**
     * Puts a month from a months file into this phone, replacing a calculated month or a draft of the same name. [files] is the folder
     * with that month's invoice / photo (or null). Callers ask the person before replacing anything.
     */
    fun importMonth(record: MonthRecord, files: File?) {
        attachmentDir(record.key).deleteRecursively()
        if (files != null && files.exists()) files.copyRecursively(attachmentDir(record.key), overwrite = true)
        months = (months.filter { it.key != record.key } + record).sortedBy { it.key }
        drafts = drafts.filter { it.key != record.key }
        persist()
    }

    /** The same for a month that was still in progress when it was shared: it arrives as a draft, with its attachments. */
    fun importDraft(draft: Draft, files: File?) {
        attachmentDir(draft.key).deleteRecursively()
        if (files != null && files.exists()) files.copyRecursively(attachmentDir(draft.key), overwrite = true)
        months = months.filter { it.key != draft.key }
        drafts = (drafts.filter { it.key != draft.key } + draft).sortedBy { it.key }
        persist()
    }

    /** Removes a calculated month and everything attached to it. */
    fun deleteMonth(key: String) {
        months = months.filter { it.key != key }
        attachmentDir(key).deleteRecursively()
        persist()
    }

    // ---- attachments ----

    private fun attachmentDir(key: String) = File(context.filesDir, "attachments/$key")

    fun attachmentFile(key: String, att: Attachment): File = File(attachmentDir(key), att.fileName)

    /**
     * Stores [source] (an invoice or a meter photo) in the draft for [key], replacing any earlier one of the same kind.
     * The draft must exist. [source] is moved into the app's storage.
     */
    fun attach(key: String, kind: AttachmentKind, source: File, mime: String, sourceUri: String? = null): Draft {
        val d = draft(key) ?: newDraft(key)
        val dir = attachmentDir(key).apply { mkdirs() }
        d.attachment(kind)?.let { File(dir, it.fileName).delete() }
        val name = "${kind.name.lowercase()}.${extensionFor(mime)}"
        source.copyTo(File(dir, name), overwrite = true)
        source.delete()
        val att = Attachment(name, mime, sourceUri)
        val updated = if (kind == AttachmentKind.INVOICE) d.copy(invoice = att) else d.copy(meter = att)
        saveDraft(updated)
        return updated
    }

    private fun extensionFor(mime: String): String = when (mime) {
        "application/pdf" -> "pdf"
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/heic", "image/heif" -> "heic"
        else -> "bin"
    }

    /** How far a month has got, for the reminder notification. */
    fun monthStatus(key: String): MonthStatus {
        if (month(key) != null) return MonthStatus.COMPLETE
        val d = draft(key) ?: return MonthStatus.NONE
        val check = DraftChecker.check(d.fields, MonthKey.parse(key).daysInMonth())
        val unchecked = (d.invoiceInfo.fromReader && !d.invoiceInfo.confirmed) || (d.meterInfo.fromReader && !d.meterInfo.confirmed)
        return when {
            !check.invoiceCaptured && !check.meterCaptured -> MonthStatus.NEEDS_BOTH
            !check.invoiceCaptured -> MonthStatus.NEEDS_INVOICE
            !check.meterCaptured -> MonthStatus.NEEDS_METER
            unchecked || !check.ready -> MonthStatus.NEEDS_CHECK
            else -> MonthStatus.READY
        }
    }

    // ---- automatic reading results ----

    private fun withInfo(d: Draft, kind: AttachmentKind, info: ReadInfo): Draft =
        if (kind == AttachmentKind.INVOICE) d.copy(invoiceInfo = info) else d.copy(meterInfo = info)

    /** Puts a reading of the invoice into the draft (only into empty boxes) and keeps the checks for the person to see. */
    fun applyInvoiceReading(key: String, reading: InvoiceReading) {
        val d = draft(key) ?: return
        if (reading.isEmpty) {
            saveDraft(withInfo(d, AttachmentKind.INVOICE, ReadInfo(failure = "The numbers couldn't be found on that file. Please type them in from the invoice.")))
            return
        }
        val filled = reading.fillInto(d.fields)
        val notes = reading.checks(settings.municipalAccount, MonthKey.parse(key))
        val changed = filled != d.fields
        saveDraft(
            withInfo(d.copy(fields = filled, readVersion = d.readVersion + if (changed) 1 else 0), AttachmentKind.INVOICE, ReadInfo(fromReader = changed, notes = notes)),
        )
    }

    /** Puts a reading of the meter photo into the draft (only if "reading now" is still empty). */
    fun applyMeterReading(key: String, reading: MeterReading) {
        val d = draft(key) ?: return
        if (reading.reading == null) {
            saveDraft(withInfo(d, AttachmentKind.METER, ReadInfo(failure = "The reading couldn't be made out in the photo. Please type it in.")))
            return
        }
        val filled = reading.fillInto(d.fields)
        val previous = Parse.decimal(filled.prev) ?: previousReadingFor(key).value
        val notes = reading.checks(previous, settings.meterSerial, d.fields.replaced)
        val changed = filled != d.fields
        saveDraft(
            withInfo(d.copy(fields = filled, readVersion = d.readVersion + if (changed) 1 else 0), AttachmentKind.METER, ReadInfo(fromReader = changed, notes = notes)),
        )
    }

    fun recordReadFailure(key: String, kind: AttachmentKind, message: String) {
        val d = draft(key) ?: return
        saveDraft(withInfo(d, kind, d.info(kind).copy(failure = message)))
    }

    fun clearReadFailure(key: String, kind: AttachmentKind) {
        val d = draft(key) ?: return
        if (d.info(kind).failure != null) saveDraft(withInfo(d, kind, d.info(kind).copy(failure = null)))
    }

    /** The person has checked automatically read numbers against the invoice / photo. */
    fun confirmReading(key: String, kind: AttachmentKind) {
        val d = draft(key) ?: return
        saveDraft(withInfo(d, kind, d.info(kind).copy(confirmed = true)))
    }

    // ---- settings and reading chain ----

    fun updateSettings(new: Settings) {
        settings = new
        persist()
    }

    fun previousReadingFor(key: String): PreviousReading {
        val last = months.filter { it.key < key && it.currReading != null }.maxByOrNull { it.key }
        return if (last != null) {
            PreviousReading(last.currReading!!, "Reading saved for ${last.label}")
        } else {
            PreviousReading(settings.lastReading, "Last known reading (${MonthKey.parse(settings.lastReadingMonth).label()})")
        }
    }

    // ---- persistence ----

    private fun load() {
        if (!file.exists()) {
            seed()
            return
        }
        try {
            val root = JSONObject(file.readText())
            settings = settingsFromJson(root.getJSONObject("settings"))
            val arr = root.getJSONArray("months")
            months = (0 until arr.length()).map { monthFromJson(arr.getJSONObject(it)) }.sortedBy { it.key }
            val dr = root.optJSONArray("drafts")
            drafts = if (dr == null) emptyList() else (0 until dr.length()).map { draftFromJson(dr.getJSONObject(it)) }.sortedBy { it.key }
            val tn = root.optJSONArray("tenancies")
            if (tn != null) {
                tenancies = (0 until tn.length()).map { tenancyFromJson(tn.getJSONObject(it)) }
            } else {
                tenancies = migrateTenantsFromSettings()
                persist()
            }
            // Someone who already has tenants recorded (an older version of the app) doesn't need the first-time wizard.
            if (!settings.setupDone && tenancies.isNotEmpty()) {
                settings = settings.copy(setupDone = true)
                persist()
            }
        } catch (e: Exception) {
            // Never lose data silently: keep the unreadable file next to the new one.
            file.renameTo(File(context.filesDir, "data.json.unreadable-${System.currentTimeMillis()}"))
            seed()
        }
    }

    private fun seed() {
        settings = Settings()
        months = HistoryImport.load(context)
        drafts = emptyList()
        tenancies = emptyList()
        persist()
    }

    private fun persist() {
        val root = JSONObject()
            .put("version", 3)
            .put("settings", settings.toJson())
            .put("months", JSONArray().also { a -> months.forEach { a.put(it.toJson()) } })
            .put("drafts", JSONArray().also { a -> drafts.forEach { a.put(it.toJson()) } })
            .put("tenancies", JSONArray().also { a -> tenancies.forEach { a.put(it.toJson()) } })
        val tmp = File(context.filesDir, "data.json.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(root.toString())
            tmp.delete()
        }
    }
}

/** Imports the old sheet's history (bundled copy of the golden CSV) as read-only months worked out with the old rules. */
object HistoryImport {
    /** Empty when the file isn't bundled (public release builds don't carry the private history; a backup brings it back). */
    fun load(context: Context): List<MonthRecord> {
        val stream = try { context.assets.open("sheet-history.csv") } catch (e: java.io.FileNotFoundException) { return emptyList() }
        return stream.bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .drop(1) // header
                .map { parse(it) }
                .toList()
        }.sortedBy { it.key }
    }

    private fun parse(line: String): MonthRecord {
        val c = line.split(',').map { it.trim() }
        fun d(i: Int) = BigDecimal(c[i])
        val key = c[0]
        val days = MonthKey.parse(key).daysInMonth()
        return MonthRecord(
            key = key,
            rules = RulesVersion.SHEET_V1,
            totalKl = d(2),
            water = d(3),
            waterAvailability = d(4),
            sewerage = d(5),
            sewerageAvailability = d(6),
            refuse = d(7),
            generalRates = d(8),
            interest = d(9),
            adjustments = d(10),
            payable = null,
            prevReading = null,
            currReading = null,
            outbuildingKl = d(1),
            meterReplaced = false,
            oldMeterFinal = null,
            newMeterStart = null,
            daysInPeriod = days,
            outbuildingDays = days,
            forebuildingDays = days,
            ratesTo = Party.OWNER,
            interestTo = Party.OWNER,
            adjustmentsTo = Party.OWNER,
            outbuildingTenant = "",
            outbuildingWhatsapp = "",
            forebuildingTenant = "",
            forebuildingWhatsapp = "",
            importedFromSheet = true,
        )
    }
}
