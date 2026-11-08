package za.westend.watersplit.data

import org.json.JSONObject
import za.westend.watersplit.engine.Assignments
import za.westend.watersplit.engine.Calculator
import za.westend.watersplit.engine.DraftFields
import za.westend.watersplit.engine.House
import za.westend.watersplit.engine.MonthInput
import za.westend.watersplit.engine.MonthKey
import za.westend.watersplit.engine.MonthResult
import za.westend.watersplit.engine.MunicipalBill
import za.westend.watersplit.engine.Occupancy
import za.westend.watersplit.engine.Party
import za.westend.watersplit.engine.ReadingNote
import za.westend.watersplit.engine.Rules
import za.westend.watersplit.engine.Tenancy
import java.math.BigDecimal

/** Which set of calculation rules a saved month was worked out with. Old months keep their old rules. */
enum class RulesVersion {
    SHEET_V1,
    CURRENT,
    ;

    fun toRules(): Rules = when (this) {
        SHEET_V1 -> Rules.SHEET_V1
        CURRENT -> Rules.CURRENT
    }
}

enum class AttachmentKind(val label: String) {
    INVOICE("municipal invoice"),
    METER("meter photo"),
}

/** A file kept with a month (stored under attachments/<month>/<fileName> in the app's private storage). */
/**
 * [sourceUri] is where the file was picked from on the phone (e.g. Downloads), kept only while the person may still be asked
 * whether to delete that original. Files shared in through the share sheet have none.
 */
data class Attachment(val fileName: String, val mime: String, val sourceUri: String? = null) {
    val isPdf: Boolean get() = mime == "application/pdf"
}

/**
 * A month that is still being put together. Many drafts can exist at once, one per month.
 * It becomes a calculated [MonthRecord] only when the invoice numbers and the meter reading are both captured.
 */
data class Draft(
    val key: String,
    val invoice: Attachment? = null,
    val meter: Attachment? = null,
    val fields: DraftFields = DraftFields(),
    val invoiceInfo: ReadInfo = ReadInfo(),
    val meterInfo: ReadInfo = ReadInfo(),
    /** Goes up each time automatic reading fills in numbers, so an open screen knows to pick them up. */
    val readVersion: Int = 0,
) {
    val label: String get() = MonthKey.parse(key).label()
    fun attachment(kind: AttachmentKind): Attachment? = if (kind == AttachmentKind.INVOICE) invoice else meter
    fun info(kind: AttachmentKind): ReadInfo = if (kind == AttachmentKind.INVOICE) invoiceInfo else meterInfo
}

/** What automatic reading did for one part of a draft (the invoice or the meter photo). */
data class ReadInfo(
    /** The numbers were filled in by automatic reading (rather than typed). */
    val fromReader: Boolean = false,
    /** The person has checked them against the invoice / photo. */
    val confirmed: Boolean = false,
    /** The last attempt failed with this (plain-language) reason. */
    val failure: String? = null,
    val notes: List<ReadingNote> = emptyList(),
)

data class HouseInfo(val tenantName: String = "", val whatsapp: String = "")

data class Settings(
    val outbuilding: HouseInfo = HouseInfo(),
    val forebuilding: HouseInfo = HouseInfo(),
    val propertyAddress: String = "",
    /** Optional "from" line printed on statements (owner's name). */
    val issuerName: String = "",
    /**
     * Last known closing reading of the outbuilding meter (kL). Used as "previous reading" until a month is saved
     * in the app. Seeded from the old sheet (end of September 2026).
     */
    val lastReading: BigDecimal = BigDecimal("73.8060"),
    val lastReadingMonth: String = "2026-09",
    /** The municipal account the invoices are for; automatic reading warns when an invoice shows another one. */
    val municipalAccount: String = "",
    /** Serial number printed on the outbuilding meter; a photo of a different meter is flagged. */
    val meterSerial: String = "",
    /** Send a notification when the invoice should have arrived or a month is waiting. */
    val remindersOn: Boolean = false,
    /** Day of the month the person wants to be reminded from (the invoice usually arrives around the 24th). */
    val reminderDay: Int = 25,
    /** The first-time wizard (current tenants, reminder day, Claude key) has been completed or skipped through. */
    val setupDone: Boolean = false,
)

/** A calculated month, plus a snapshot of who the tenants were at the time. */
data class MonthRecord(
    /** Statement month, "yyyy-MM". */
    val key: String,
    val rules: RulesVersion,
    // municipal invoice, rand excluding VAT
    val totalKl: BigDecimal,
    val water: BigDecimal,
    val waterAvailability: BigDecimal,
    val sewerage: BigDecimal,
    val sewerageAvailability: BigDecimal,
    val refuse: BigDecimal,
    val generalRates: BigDecimal,
    val interest: BigDecimal,
    val adjustments: BigDecimal,
    val payable: BigDecimal?,
    // outbuilding meter
    val prevReading: BigDecimal?,
    val currReading: BigDecimal?,
    val outbuildingKl: BigDecimal,
    val meterReplaced: Boolean,
    val oldMeterFinal: BigDecimal?,
    val newMeterStart: BigDecimal?,
    // occupancy
    val daysInPeriod: Int,
    val outbuildingDays: Int,
    val forebuildingDays: Int,
    // who pays what is not tied to a house
    val ratesTo: Party,
    val interestTo: Party,
    val adjustmentsTo: Party,
    // tenants at the time
    val outbuildingTenant: String,
    val outbuildingWhatsapp: String,
    val forebuildingTenant: String,
    val forebuildingWhatsapp: String,
    val importedFromSheet: Boolean,
    val invoice: Attachment? = null,
    val meter: Attachment? = null,
) {
    val label: String get() = MonthKey.parse(key).label()

    fun toInput() = MonthInput(
        bill = MunicipalBill(
            totalWaterKl = totalKl,
            water = water,
            waterAvailability = waterAvailability,
            sewerage = sewerage,
            sewerageAvailability = sewerageAvailability,
            refuse = refuse,
            generalRates = generalRates,
            interest = interest,
            adjustments = adjustments,
        ),
        outbuildingKl = outbuildingKl,
        occupancy = Occupancy(daysInPeriod, outbuildingDays, forebuildingDays),
        assignments = Assignments(ratesTo, interestTo, adjustmentsTo),
        billTotalPayable = payable,
    )

    fun calculate(): MonthResult = Calculator.calculate(toInput(), rules.toRules())

    fun attachment(kind: AttachmentKind): Attachment? = if (kind == AttachmentKind.INVOICE) invoice else meter
}

fun BigDecimal?.plain(): String = this?.stripTrailingZeros()?.toPlainString() ?: ""

private fun BigDecimal?.plainUnlessZero(): String = if (this == null || signum() == 0) "" else plain()

/** Turns a calculated month back into editable text (used when she taps "Edit the numbers"). */
fun MonthRecord.toFields(): DraftFields = DraftFields(
    totalKl = totalKl.plain(),
    water = water.plain(),
    avail = waterAvailability.plain(),
    sewer = sewerage.plain(),
    sewAvail = sewerageAvailability.plain(),
    refuse = refuse.plain(),
    rates = generalRates.plain(),
    interest = interest.plainUnlessZero(),
    adjustments = adjustments.plainUnlessZero(),
    payable = payable.plain(),
    prev = prevReading.plain(),
    prevEdited = true,
    curr = currReading.plain(),
    replaced = meterReplaced,
    oldFinal = oldMeterFinal.plain(),
    newStart = newMeterStart.plain(),
    fullMonth = outbuildingDays == daysInPeriod && forebuildingDays == daysInPeriod,
    outbDays = outbuildingDays.toString(),
    foreDays = forebuildingDays.toString(),
    interestTo = interestTo,
    adjustmentsTo = adjustmentsTo,
)

// ---- JSON (org.json is built into Android; amounts are stored as exact strings) ----

private fun JSONObject.putBig(name: String, v: BigDecimal?) {
    put(name, v?.toPlainString() ?: JSONObject.NULL)
}

private fun JSONObject.big(name: String): BigDecimal? =
    if (!has(name) || isNull(name)) null else getString(name).toBigDecimalOrNull()

private fun JSONObject.bigOr0(name: String): BigDecimal = big(name) ?: BigDecimal.ZERO

private inline fun <reified E : Enum<E>> JSONObject.enumOr(name: String, default: E): E =
    optString(name).let { s -> enumValues<E>().firstOrNull { it.name == s } ?: default }

fun Attachment.toJson(): JSONObject = JSONObject().put("fileName", fileName).put("mime", mime).also { if (sourceUri != null) it.put("sourceUri", sourceUri) }

fun attachmentFromJson(o: JSONObject?): Attachment? =
    if (o == null) null else Attachment(o.getString("fileName"), o.optString("mime", "application/octet-stream"), o.optString("sourceUri").ifBlank { null })

private fun JSONObject.attachment(name: String): Attachment? =
    if (!has(name) || isNull(name)) null else attachmentFromJson(optJSONObject(name))

fun DraftFields.toJson(): JSONObject = JSONObject().apply {
    put("totalKl", totalKl)
    put("water", water)
    put("avail", avail)
    put("sewer", sewer)
    put("sewAvail", sewAvail)
    put("refuse", refuse)
    put("rates", rates)
    put("interest", interest)
    put("adjustments", adjustments)
    put("payable", payable)
    put("prev", prev)
    put("prevEdited", prevEdited)
    put("curr", curr)
    put("replaced", replaced)
    put("oldFinal", oldFinal)
    put("newStart", newStart)
    put("fullMonth", fullMonth)
    put("outbDays", outbDays)
    put("foreDays", foreDays)
    put("interestTo", interestTo.name)
    put("adjustmentsTo", adjustmentsTo.name)
}

fun draftFieldsFromJson(o: JSONObject): DraftFields = DraftFields(
    totalKl = o.optString("totalKl"),
    water = o.optString("water"),
    avail = o.optString("avail"),
    sewer = o.optString("sewer"),
    sewAvail = o.optString("sewAvail"),
    refuse = o.optString("refuse"),
    rates = o.optString("rates"),
    interest = o.optString("interest"),
    adjustments = o.optString("adjustments"),
    payable = o.optString("payable"),
    prev = o.optString("prev"),
    prevEdited = o.optBoolean("prevEdited", false),
    curr = o.optString("curr"),
    replaced = o.optBoolean("replaced", false),
    oldFinal = o.optString("oldFinal"),
    newStart = o.optString("newStart"),
    fullMonth = o.optBoolean("fullMonth", true),
    outbDays = o.optString("outbDays"),
    foreDays = o.optString("foreDays"),
    interestTo = o.enumOr("interestTo", Party.OWNER),
    adjustmentsTo = o.enumOr("adjustmentsTo", Party.OWNER),
)

fun ReadInfo.toJson(): JSONObject = JSONObject().apply {
    put("fromReader", fromReader)
    put("confirmed", confirmed)
    put("failure", failure ?: JSONObject.NULL)
    put(
        "notes",
        org.json.JSONArray().also { a ->
            notes.forEach { n ->
                a.put(
                    JSONObject()
                        .put("warning", n.warning)
                        .put("text", n.text)
                        .put("suggest", n.suggestMonth?.key ?: JSONObject.NULL),
                )
            }
        },
    )
}

fun readInfoFromJson(o: JSONObject?): ReadInfo {
    if (o == null) return ReadInfo()
    val arr = o.optJSONArray("notes")
    val notes = if (arr == null) emptyList() else (0 until arr.length()).map { i ->
        val n = arr.getJSONObject(i)
        ReadingNote(
            warning = n.optBoolean("warning", false),
            text = n.optString("text"),
            suggestMonth = if (n.isNull("suggest")) null else MonthKey.parse(n.getString("suggest")),
        )
    }
    return ReadInfo(
        fromReader = o.optBoolean("fromReader", false),
        confirmed = o.optBoolean("confirmed", false),
        failure = if (!o.has("failure") || o.isNull("failure")) null else o.getString("failure"),
        notes = notes,
    )
}

fun Draft.toJson(): JSONObject = JSONObject().apply {
    put("key", key)
    put("invoice", invoice?.toJson() ?: JSONObject.NULL)
    put("meter", meter?.toJson() ?: JSONObject.NULL)
    put("fields", fields.toJson())
    put("invoiceInfo", invoiceInfo.toJson())
    put("meterInfo", meterInfo.toJson())
    put("readVersion", readVersion)
}

fun draftFromJson(o: JSONObject): Draft = Draft(
    key = o.getString("key"),
    invoice = o.attachment("invoice"),
    meter = o.attachment("meter"),
    fields = o.optJSONObject("fields")?.let { draftFieldsFromJson(it) } ?: DraftFields(),
    invoiceInfo = readInfoFromJson(o.optJSONObject("invoiceInfo")),
    meterInfo = readInfoFromJson(o.optJSONObject("meterInfo")),
    readVersion = o.optInt("readVersion", 0),
)

fun MonthRecord.toJson(): JSONObject = JSONObject().apply {
    put("key", key)
    put("rules", rules.name)
    putBig("totalKl", totalKl)
    putBig("water", water)
    putBig("waterAvailability", waterAvailability)
    putBig("sewerage", sewerage)
    putBig("sewerageAvailability", sewerageAvailability)
    putBig("refuse", refuse)
    putBig("generalRates", generalRates)
    putBig("interest", interest)
    putBig("adjustments", adjustments)
    putBig("payable", payable)
    putBig("prevReading", prevReading)
    putBig("currReading", currReading)
    putBig("outbuildingKl", outbuildingKl)
    put("meterReplaced", meterReplaced)
    putBig("oldMeterFinal", oldMeterFinal)
    putBig("newMeterStart", newMeterStart)
    put("daysInPeriod", daysInPeriod)
    put("outbuildingDays", outbuildingDays)
    put("forebuildingDays", forebuildingDays)
    put("ratesTo", ratesTo.name)
    put("interestTo", interestTo.name)
    put("adjustmentsTo", adjustmentsTo.name)
    put("outbuildingTenant", outbuildingTenant)
    put("outbuildingWhatsapp", outbuildingWhatsapp)
    put("forebuildingTenant", forebuildingTenant)
    put("forebuildingWhatsapp", forebuildingWhatsapp)
    put("importedFromSheet", importedFromSheet)
    put("invoice", invoice?.toJson() ?: JSONObject.NULL)
    put("meter", meter?.toJson() ?: JSONObject.NULL)
}

fun monthFromJson(o: JSONObject): MonthRecord = MonthRecord(
    key = o.getString("key"),
    rules = o.enumOr("rules", RulesVersion.CURRENT),
    totalKl = o.bigOr0("totalKl"),
    water = o.bigOr0("water"),
    waterAvailability = o.bigOr0("waterAvailability"),
    sewerage = o.bigOr0("sewerage"),
    sewerageAvailability = o.bigOr0("sewerageAvailability"),
    refuse = o.bigOr0("refuse"),
    generalRates = o.bigOr0("generalRates"),
    interest = o.bigOr0("interest"),
    adjustments = o.bigOr0("adjustments"),
    payable = o.big("payable"),
    prevReading = o.big("prevReading"),
    currReading = o.big("currReading"),
    outbuildingKl = o.bigOr0("outbuildingKl"),
    meterReplaced = o.optBoolean("meterReplaced", false),
    oldMeterFinal = o.big("oldMeterFinal"),
    newMeterStart = o.big("newMeterStart"),
    daysInPeriod = o.getInt("daysInPeriod"),
    outbuildingDays = o.getInt("outbuildingDays"),
    forebuildingDays = o.getInt("forebuildingDays"),
    ratesTo = o.enumOr("ratesTo", Party.OWNER),
    interestTo = o.enumOr("interestTo", Party.OWNER),
    adjustmentsTo = o.enumOr("adjustmentsTo", Party.OWNER),
    outbuildingTenant = o.optString("outbuildingTenant"),
    outbuildingWhatsapp = o.optString("outbuildingWhatsapp"),
    forebuildingTenant = o.optString("forebuildingTenant"),
    forebuildingWhatsapp = o.optString("forebuildingWhatsapp"),
    importedFromSheet = o.optBoolean("importedFromSheet", false),
    invoice = o.attachment("invoice"),
    meter = o.attachment("meter"),
)

fun Tenancy.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("house", house.name)
    put("name", name)
    put("whatsapp", whatsapp)
    put("from", from.key)
    put("to", to?.key ?: JSONObject.NULL)
}

fun tenancyFromJson(o: JSONObject): Tenancy = Tenancy(
    id = o.getString("id"),
    house = House.valueOf(o.getString("house")),
    name = o.optString("name"),
    whatsapp = o.optString("whatsapp"),
    from = MonthKey.parse(o.getString("from")),
    to = if (!o.has("to") || o.isNull("to")) null else MonthKey.parse(o.getString("to")),
)

fun Settings.toJson(): JSONObject = JSONObject().apply {
    put("outbuildingTenant", outbuilding.tenantName)
    put("outbuildingWhatsapp", outbuilding.whatsapp)
    put("forebuildingTenant", forebuilding.tenantName)
    put("forebuildingWhatsapp", forebuilding.whatsapp)
    put("propertyAddress", propertyAddress)
    put("issuerName", issuerName)
    putBig("lastReading", lastReading)
    put("lastReadingMonth", lastReadingMonth)
    put("municipalAccount", municipalAccount)
    put("meterSerial", meterSerial)
    put("remindersOn", remindersOn)
    put("reminderDay", reminderDay)
    put("setupDone", setupDone)
}

fun settingsFromJson(o: JSONObject): Settings {
    val d = Settings()
    return Settings(
        outbuilding = HouseInfo(o.optString("outbuildingTenant"), o.optString("outbuildingWhatsapp")),
        forebuilding = HouseInfo(o.optString("forebuildingTenant"), o.optString("forebuildingWhatsapp")),
        propertyAddress = o.optString("propertyAddress", d.propertyAddress),
        issuerName = o.optString("issuerName"),
        lastReading = o.big("lastReading") ?: d.lastReading,
        lastReadingMonth = o.optString("lastReadingMonth", d.lastReadingMonth),
        municipalAccount = o.optString("municipalAccount", d.municipalAccount),
        meterSerial = o.optString("meterSerial", d.meterSerial),
        remindersOn = o.optBoolean("remindersOn", false),
        reminderDay = o.optInt("reminderDay", d.reminderDay).coerceIn(1, 31),
        setupDone = o.optBoolean("setupDone", false),
    )
}
