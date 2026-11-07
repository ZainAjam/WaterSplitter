package za.westend.watersplit.engine

import java.math.BigDecimal
import java.time.LocalDate

/** A remark about something read automatically, shown to the person who has to check it. */
data class ReadingNote(
    val warning: Boolean,
    val text: String,
    /** For a "this looks like another month's invoice" warning: the month it probably belongs to. */
    val suggestMonth: MonthKey? = null,
)

private fun BigDecimal.plain(): String = stripTrailingZeros().toPlainString()

private fun Map<String, String?>.dec(key: String): BigDecimal? = this[key]?.let { Parse.decimal(it) }

private fun Map<String, String?>.text(key: String): String? = this[key]?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

/** What was read off one municipal invoice. Amounts are in rand, before VAT (rates carry no VAT). */
data class InvoiceReading(
    val statementDate: LocalDate?,
    val accountNumber: String?,
    val propertyKl: BigDecimal?,
    val water: BigDecimal?,
    val waterAvailability: BigDecimal?,
    val sewerage: BigDecimal?,
    val sewerageAvailability: BigDecimal?,
    val refuse: BigDecimal?,
    val generalRates: BigDecimal?,
    val interest: BigDecimal?,
    /** Balance brought forward from last month (negative when it was a credit). */
    val broughtForward: BigDecimal?,
    /** Payments received since the last invoice, as a positive number. */
    val payments: BigDecimal?,
    /** Any other earlier-period items (discounts, reversals), positive = owed. */
    val otherAdjustments: BigDecimal?,
    val totalPayable: BigDecimal?,
    /** Names of fields the reader said it was unsure about. */
    val unsure: List<String>,
    val notes: String?,
) {
    /** Everything carried over from before this invoice: what was owed, less payments, plus other items. */
    val adjustments: BigDecimal?
        get() = if (broughtForward == null && payments == null && otherAdjustments == null) {
            null
        } else {
            (broughtForward ?: BigDecimal.ZERO) - (payments ?: BigDecimal.ZERO) + (otherAdjustments ?: BigDecimal.ZERO)
        }

    /** Puts what was read into the draft, but only into boxes that are still empty: typed numbers are never overwritten. */
    fun fillInto(base: DraftFields): DraftFields {
        fun pick(current: String, read: BigDecimal?): String = if (current.isBlank() && read != null) read.plain() else current
        fun pickUnlessZero(current: String, read: BigDecimal?): String =
            if (current.isBlank() && read != null && read.signum() != 0) read.plain() else current
        return base.copy(
            totalKl = pick(base.totalKl, propertyKl),
            water = pick(base.water, water),
            avail = pick(base.avail, waterAvailability),
            sewer = pick(base.sewer, sewerage),
            sewAvail = pick(base.sewAvail, sewerageAvailability),
            refuse = pick(base.refuse, refuse),
            rates = pick(base.rates, generalRates),
            interest = pickUnlessZero(base.interest, interest),
            adjustments = pickUnlessZero(base.adjustments, adjustments),
            payable = pick(base.payable, totalPayable),
        )
    }

    /** True when the reader found nothing useful at all (so the person should type the numbers). */
    val isEmpty: Boolean
        get() = listOf(propertyKl, water, waterAvailability, sewerage, sewerageAvailability, refuse, generalRates).all { it == null }

    /** Sanity checks against what we know. [expectedAccount] may be blank when not set. */
    fun checks(expectedAccount: String?, draftMonth: MonthKey): List<ReadingNote> {
        val notes = mutableListOf<ReadingNote>()

        if (unsure.isNotEmpty()) {
            notes.add(ReadingNote(true, "The app wasn't sure about: ${unsure.joinToString(", ")}. Please check these against the invoice."))
        }

        val expected = expectedAccount?.filter { it.isDigit() }.orEmpty()
        val found = accountNumber?.filter { it.isDigit() }.orEmpty()
        if (expected.isNotEmpty() && found.isNotEmpty() && expected != found) {
            notes.add(ReadingNote(true, "This invoice is for account $accountNumber, not your account $expectedAccount. Is it the right invoice?"))
        }

        val date = statementDate
        if (date != null) {
            val invoiceMonth = MonthKey(date.year, date.monthValue)
            if (invoiceMonth != draftMonth) {
                notes.add(
                    ReadingNote(
                        true,
                        "This invoice is dated ${date.dayOfMonth} ${invoiceMonth.label()}, so it looks like the ${invoiceMonth.label()} invoice, " +
                            "but this draft is for ${draftMonth.label()}.",
                        suggestMonth = invoiceMonth,
                    ),
                )
            }
        }

        val required = listOf(propertyKl, water, waterAvailability, sewerage, sewerageAvailability, refuse, generalRates)
        val total = totalPayable
        if (total != null && required.all { it != null }) {
            val bill = MunicipalBill(
                totalWaterKl = propertyKl!!,
                water = water!!,
                waterAvailability = waterAvailability!!,
                sewerage = sewerage!!,
                sewerageAvailability = sewerageAvailability!!,
                refuse = refuse!!,
                generalRates = generalRates!!,
                interest = interest ?: BigDecimal.ZERO,
                adjustments = adjustments ?: BigDecimal.ZERO,
            )
            val computed = bill.computedTotal().cents()
            if ((computed - total).abs() > BigDecimal("0.05")) {
                notes.add(
                    ReadingNote(
                        true,
                        "The lines add up to ${Fmt.rand(computed)} but the invoice says the total payable is ${Fmt.rand(total)}. " +
                            "A number may have been misread, so please check each one.",
                    ),
                )
            } else {
                notes.add(ReadingNote(false, "Checked: the numbers add up to the invoice's total payable of ${Fmt.rand(total)}."))
            }
        }

        notes.addAll(this.notes?.let { listOf(ReadingNote(false, it)) }.orEmpty())
        return notes
    }

    companion object {
        /** Builds a reading from the text values the reader returned (numbers may arrive as "1 266,40" etc.). */
        fun fromStrings(m: Map<String, String?>): InvoiceReading {
            val date = m.text("statementDate")?.let { s ->
                runCatching { LocalDate.parse(s) }.getOrNull()
                    ?: runCatching { LocalDate.parse(s, java.time.format.DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
            }
            return InvoiceReading(
                statementDate = date,
                accountNumber = m.text("accountNumber"),
                propertyKl = m.dec("propertyKl"),
                water = m.dec("water"),
                waterAvailability = m.dec("waterAvailability"),
                sewerage = m.dec("sewerage"),
                sewerageAvailability = m.dec("sewerageAvailability"),
                refuse = m.dec("refuse"),
                generalRates = m.dec("generalRates"),
                interest = m.dec("interest"),
                broughtForward = m.dec("broughtForward"),
                payments = m.dec("payments"),
                otherAdjustments = m.dec("otherAdjustments"),
                totalPayable = m.dec("totalPayable"),
                unsure = m.text("unsure")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                notes = m.text("notes"),
            )
        }
    }
}

enum class Confidence { HIGH, MEDIUM, LOW }

/** What was read off a photo of the outbuilding water meter. */
data class MeterReading(
    /** Kilolitres: the black digits are whole kL, the red wheels are the decimals. */
    val reading: BigDecimal?,
    /** The serial number printed on the meter label (e.g. SN123456789), if visible. */
    val serial: String?,
    val confidence: Confidence,
    /** True when the last red wheel was between two numbers (the lower digit is reported). */
    val lastWheelUncertain: Boolean,
    val notes: String?,
) {
    fun fillInto(base: DraftFields): DraftFields =
        if (base.curr.isBlank() && reading != null) base.copy(curr = reading.plain()) else base

    /**
     * Sanity checks. [previous] is last month's reading; [expectedSerial] is the serial of the meter on the property.
     * [meterReplaced] suppresses the "lower than last time" warning when the meter really was replaced.
     */
    fun checks(previous: BigDecimal?, expectedSerial: String?, meterReplaced: Boolean): List<ReadingNote> {
        val notes = mutableListOf<ReadingNote>()
        val r = reading
        if (r == null) {
            notes.add(ReadingNote(true, "The reading couldn't be made out in the photo. Please type it in."))
            return notes
        }
        if (confidence == Confidence.LOW) {
            notes.add(ReadingNote(true, "The photo is hard to read. Please check the number against the photo."))
        }
        if (lastWheelUncertain) {
            notes.add(ReadingNote(false, "The last wheel was between two numbers, so the lower digit was used."))
        }
        val want = expectedSerial?.filter { it.isLetterOrDigit() }?.uppercase().orEmpty()
        val got = serial?.filter { it.isLetterOrDigit() }?.uppercase().orEmpty()
        if (want.isNotEmpty() && got.isNotEmpty() && want != got) {
            notes.add(
                ReadingNote(
                    true,
                    "The meter in the photo is $serial but your meter is ${expectedSerial?.trim()}. If the meter was replaced, switch on \"The meter was replaced\" below.",
                ),
            )
        }
        if (previous != null && !meterReplaced) {
            if (r < previous) {
                notes.add(ReadingNote(true, "This reading (${Fmt.kl(r)}) is lower than last month's (${Fmt.kl(previous)})."))
            } else if (r - previous > BigDecimal(20)) {
                notes.add(ReadingNote(true, "That's ${Fmt.kl(r - previous)} more than last month's reading. Please check the number."))
            }
        }
        this.notes?.let { notes.add(ReadingNote(false, it)) }
        return notes
    }

    companion object {
        fun fromStrings(m: Map<String, String?>): MeterReading = MeterReading(
            reading = m.dec("reading"),
            serial = m.text("serial"),
            confidence = when (m.text("confidence")?.lowercase()) {
                "high" -> Confidence.HIGH
                "low" -> Confidence.LOW
                else -> Confidence.MEDIUM
            },
            lastWheelUncertain = m.text("lastWheelUncertain")?.equals("true", ignoreCase = true) == true,
            notes = m.text("notes"),
        )
    }
}
