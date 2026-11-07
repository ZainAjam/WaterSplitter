package za.westend.watersplit.reading

import android.util.Base64
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.Base64PdfSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.DocumentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import org.json.JSONObject
import za.westend.watersplit.ui.Previews
import java.io.File
import java.time.Duration

/** A reading that did not work, with a reason the person can understand. */
class ReadFailure(message: String) : Exception(message)

/**
 * Sends an invoice or a meter photo to Claude (Anthropic's API, using the person's own key) and gets the numbers back as
 * validated JSON. Everything here is blocking: call it off the main thread.
 */
object ClaudeReader {
    private const val MODEL = "claude-opus-5-5"

    private val INVOICE_FIELDS = listOf(
        "statementDate" to "The \"Statement Date\" as ISO yyyy-mm-dd (e.g. 20260824 becomes 2026-08-24). Empty if not visible.",
        "accountNumber" to "The \"Account Number\", digits only. Empty if not visible.",
        "propertyKl" to "Kilolitres billed on the water meter: the \"CONS.\" figure on the WATER meter line (e.g. 8.0). Empty if not visible.",
        "water" to "The water consumption charge in rand BEFORE VAT: the first amount on the WATER meter line (e.g. 175.36).",
        "waterAvailability" to "The \"AVAILABILITY CHARGE\" in rand BEFORE VAT (e.g. 71.74).",
        "sewerage" to "The \"SEWERAGE\" charge in rand BEFORE VAT. Not the sewerage availability line.",
        "sewerageAvailability" to "The \"SEWERAGE AVAILABILIT(Y)\" charge in rand BEFORE VAT.",
        "refuse" to "The \"REFUSE CHARGE\" in rand BEFORE VAT.",
        "generalRates" to "The \"GEN RATES\" charge in rand (it carries 0% VAT).",
        "interest" to "Interest charged, in rand. \"0\" when there is none.",
        "broughtForward" to "The \"BROUGHT FORWARD\" amount at the top. Positive when owed; NEGATIVE when it is marked CR (credit).",
        "payments" to "The total of payments received (lines such as \"THANK YOU FOR YOUR PAYMENT\", marked CR), as a POSITIVE number. \"0\" if none.",
        "otherAdjustments" to "Any other earlier-period items (discounts, reversals, corrections) net, positive = owed. \"0\" if none.",
        "totalPayable" to "The \"TOTAL PAYABLE\" amount on the last page.",
        "unsure" to "Comma-separated names of the fields above that you could not read confidently, or empty.",
        "notes" to "One short sentence about anything unusual on the invoice, or empty.",
    )

    private val METER_FIELDS = listOf(
        "reading" to "The meter reading in kilolitres as a decimal: black digits = whole kL, red wheels = the decimals, e.g. 73.806. Empty if it cannot be read.",
        "serial" to "The serial number on the meter's label (e.g. SN123456789), or empty if not visible.",
        "confidence" to "high, medium or low: how sure you are of every digit.",
        "lastWheelUncertain" to "true if the last red wheel is between two numbers (you reported the lower digit), otherwise false.",
        "notes" to "One short sentence about anything that makes the reading uncertain, or empty.",
    )

    private const val INVOICE_PROMPT =
        "This is a Nelson Mandela Bay Municipality tax invoice (South Africa) for a residential property. " +
            "Read it and return the requested fields. All money is in South African rand: give plain decimals with a dot, " +
            "no currency symbol, no spaces (write 1 266.40 as 1266.40). Charge lines show an amount and then a separate " +
            "\"VAT @ 15.0%\" line: always report the amount BEFORE VAT, never the VAT line and never the subtotal that includes VAT. " +
            "Use an empty string for anything you cannot see; never guess a number."

    private const val METER_PROMPT =
        "This is a photo of a household water meter register (rotating digits). The black digits are whole kilolitres (kL) " +
            "and the red wheels to their right are the decimal part. Read the full reading as a decimal number of kL, including " +
            "every red wheel you can see. If the last wheel is between two numbers, report the lower digit and set lastWheelUncertain " +
            "to true. Also read the serial number printed on the meter's label if it is visible. " +
            "Never guess: if the digits cannot be read, leave reading empty."

    /** Reads an invoice (PDF or photo/scan) and returns the raw field values. */
    fun readInvoice(apiKey: String, file: File, mime: String): Map<String, String?> =
        ask(apiKey, listOf(fileBlock(file, mime, 1800), ContentBlockParam.ofText(INVOICE_PROMPT)), schema(INVOICE_FIELDS), OutputConfig.Effort.MEDIUM)

    /** Reads a photo of the water meter and returns the raw field values. */
    fun readMeter(apiKey: String, file: File, mime: String): Map<String, String?> =
        ask(apiKey, listOf(fileBlock(file, mime, 1600), ContentBlockParam.ofText(METER_PROMPT)), schema(METER_FIELDS), OutputConfig.Effort.MEDIUM)

    private fun fileBlock(file: File, mime: String, maxWidth: Int): ContentBlockParam =
        if (mime == "application/pdf") {
            val data = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            ContentBlockParam.ofDocument(DocumentBlockParam.builder().source(Base64PdfSource.builder().data(data).build()).build())
        } else {
            val jpeg = Previews.jpegBytes(file, maxWidth) ?: throw ReadFailure("That photo couldn't be opened.")
            val data = Base64.encodeToString(jpeg, Base64.NO_WRAP)
            ContentBlockParam.ofImage(
                ImageBlockParam.builder()
                    .source(Base64ImageSource.builder().data(data).mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).build())
                    .build(),
            )
        }

    /** A JSON schema: an object whose every field is a string (so numbers keep the exact digits that were read). */
    private fun schema(fields: List<Pair<String, String>>): JsonOutputFormat.Schema =
        JsonOutputFormat.Schema.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty(
                "properties",
                JsonValue.from(fields.associate { (name, description) -> name to mapOf("type" to "string", "description" to description) }),
            )
            .putAdditionalProperty("required", JsonValue.from(fields.map { it.first }))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .build()

    private fun ask(
        apiKey: String,
        content: List<ContentBlockParam>,
        schema: JsonOutputFormat.Schema,
        effort: OutputConfig.Effort,
    ): Map<String, String?> {
        val client = AnthropicOkHttpClient.builder()
            .apiKey(apiKey.trim())
            .timeout(Duration.ofSeconds(150))
            .maxRetries(2)
            .build()
        try {
            val params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(16000L)
                .outputConfig(
                    OutputConfig.builder()
                        .effort(effort)
                        .format(JsonOutputFormat.builder().schema(schema).build())
                        .build(),
                )
                .addUserMessageOfContentBlockParams(content)
                .build()
            val message = client.messages().create(params)
            if (message.stopReason().orElse(null) == StopReason.REFUSAL) {
                throw ReadFailure("Claude declined to read this file. Please type the numbers in.")
            }
            val text = message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
            if (text.isBlank()) throw ReadFailure("Claude sent back nothing readable. Please try again.")
            return toMap(text)
        } catch (e: ReadFailure) {
            throw e
        } catch (e: UnauthorizedException) {
            throw ReadFailure("Claude didn't accept the API key. Check it in Settings.")
        } catch (e: PermissionDeniedException) {
            throw ReadFailure("This API key isn't allowed to do that. Check the key in Settings.")
        } catch (e: RateLimitException) {
            throw ReadFailure("Claude is busy at the moment. Please try again in a minute.")
        } catch (e: BadRequestException) {
            val m = e.message.orEmpty()
            throw ReadFailure(
                if (m.contains("credit", ignoreCase = true)) {
                    "The Claude account has no credit left. Add credit at console.anthropic.com."
                } else {
                    "Claude couldn't use this file. Please type the numbers in."
                },
            )
        } catch (e: AnthropicIoException) {
            throw ReadFailure("Couldn't reach Claude. Check the internet connection and try again.")
        } catch (e: AnthropicServiceException) {
            throw ReadFailure("Claude reported a problem (error ${e.statusCode()}). Please try again later.")
        } finally {
            client.close()
        }
    }

    /** The model's JSON answer as plain text values (a JSON null or the word "null" becomes null). */
    internal fun toMap(json: String): Map<String, String?> {
        val cleaned = json.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val o = try {
            JSONObject(cleaned)
        } catch (e: Exception) {
            throw ReadFailure("Claude's answer couldn't be understood. Please try again.")
        }
        return o.keys().asSequence().associateWith { k -> if (o.isNull(k)) null else o.get(k).toString() }
    }
}
