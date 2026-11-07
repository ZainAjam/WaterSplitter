package za.westend.watersplit.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import za.westend.watersplit.data.Settings
import za.westend.watersplit.engine.Fmt
import za.westend.watersplit.engine.House
import za.westend.watersplit.engine.PartyStatement
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Draws one tenant's itemised statement as a PNG. An image opens straight inside WhatsApp (no PDF reader needed).
 * The picture is 1080 px wide and only as tall as it needs to be.
 */
object StatementImage {
    private const val W = 1080
    private const val LEFT = 72f
    private const val RIGHT = 1008f
    private const val COL_AMOUNT = 670f
    private const val COL_VAT = 840f
    private const val ROW_H = 62f

    fun create(
        context: Context,
        house: House,
        tenantName: String,
        monthLabel: String,
        monthKey: String,
        statement: PartyStatement,
        settings: Settings,
    ): File {
        fun paint(size: Float, bold: Boolean = false, color: Int = Color.BLACK, align: Paint.Align = Paint.Align.LEFT) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                textAlign = align
                typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            }

        val title = paint(46f, bold = true)
        val body = paint(30f)
        val bodyBold = paint(30f, bold = true)
        val gray = paint(24f, color = Color.DKGRAY)
        val right = paint(30f, align = Paint.Align.RIGHT)
        val rightBold = paint(30f, bold = true, align = Paint.Align.RIGHT)
        val totalLabel = paint(38f, bold = true)
        val totalValue = paint(38f, bold = true, align = Paint.Align.RIGHT)
        val rule = Paint().apply {
            color = Color.GRAY
            strokeWidth = 2f
        }

        val hasIssuer = settings.issuerName.isNotBlank()
        val height = (
            100f + 54f + 66f + 46f + 42f + (if (hasIssuer) 42f else 0f) + 64f +
                30f + statement.lines.size * ROW_H + 30f + 84f + 60f + 56f
            ).toInt()

        val bitmap = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.drawColor(Color.WHITE)

        var y = 100f
        c.drawText("Water, Sewerage & Refuse Statement", LEFT, y, title)
        y += 54f
        c.drawText(settings.propertyAddress, LEFT, y, body)
        y += 66f

        val who = if (tenantName.isBlank()) house.label else "${house.label} - $tenantName"
        c.drawText(who, LEFT, y, bodyBold)
        y += 46f
        c.drawText("Statement for: $monthLabel", LEFT, y, body)
        y += 42f
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH))
        c.drawText("Date: $today", LEFT, y, body)
        if (hasIssuer) {
            y += 42f
            c.drawText("From: ${settings.issuerName}", LEFT, y, body)
        }
        y += 64f

        c.drawText("Description", LEFT, y, bodyBold)
        c.drawText("Amount (R)", COL_AMOUNT, y, rightBold)
        c.drawText("VAT (R)", COL_VAT, y, rightBold)
        c.drawText("Total (R)", RIGHT, y, rightBold)
        y += 16f
        c.drawLine(LEFT, y, RIGHT, y, rule)
        y += 14f

        for (line in statement.lines) {
            y += ROW_H - 14f
            c.drawText(line.code.label, LEFT, y, body)
            c.drawText(Fmt.amount(line.net), COL_AMOUNT, y, right)
            c.drawText(if (line.code.vatable) Fmt.amount(line.vat) else "-", COL_VAT, y, right)
            c.drawText(Fmt.amount(line.gross), RIGHT, y, right)
            y += 14f
        }

        y += 16f
        c.drawLine(LEFT, y, RIGHT, y, rule)
        y += 62f
        c.drawText("Total due", LEFT, y, totalLabel)
        c.drawText(Fmt.rand(statement.total), RIGHT, y, totalValue)
        y += 70f
        c.drawText("All amounts in South African rand. VAT at 15% is shown where it applies.", LEFT, y, gray)

        val dir = File(context.cacheDir, "statements").apply { mkdirs() }
        val file = File(dir, "${house.label}-$monthKey.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }
}
