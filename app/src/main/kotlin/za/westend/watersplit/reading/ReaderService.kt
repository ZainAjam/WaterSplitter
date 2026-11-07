package za.westend.watersplit.reading

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import za.westend.watersplit.data.ApiKeyStore
import za.westend.watersplit.data.AttachmentKind
import za.westend.watersplit.data.Store
import za.westend.watersplit.engine.InvoiceReading
import za.westend.watersplit.engine.MeterReading
import java.io.File

/**
 * Runs automatic reading in the background (so leaving the screen doesn't stop it) and writes the result into the draft.
 * Nothing is read unless the person has saved a Claude key in Settings.
 */
object ReaderService {
    private lateinit var appContext: Context
    private lateinit var keys: ApiKeyStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** "2026-10|INVOICE" -> true while that file is being read. Observable from Compose. */
    private val running = mutableStateMapOf<String, Boolean>()

    fun init(context: Context) {
        appContext = context.applicationContext
        keys = ApiKeyStore(appContext)
    }

    val keyStore: ApiKeyStore get() = keys

    private fun id(key: String, kind: AttachmentKind) = "$key|${kind.name}"

    fun isReading(key: String, kind: AttachmentKind): Boolean = running[id(key, kind)] == true

    /** Debug builds only: a canned answer pushed to files/fake-invoice.json or fake-meter.json, for testing without a key. */
    private fun fakeFile(kind: AttachmentKind): File? {
        val debuggable = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return null
        return File(appContext.filesDir, "fake-${kind.name.lowercase()}.json").takeIf { it.exists() }
    }

    /** True when there is something to read with (a saved key; or a canned answer in a debug build). */
    fun isConfigured(kind: AttachmentKind): Boolean = keys.hasKey() || fakeFile(kind) != null

    /** Starts reading the file attached to [kind] in the draft for [key]. Does nothing without a key or a file. */
    fun start(store: Store, key: String, kind: AttachmentKind) {
        val taskId = id(key, kind)
        if (running[taskId] == true) return
        val draft = store.draft(key) ?: return
        val att = draft.attachment(kind) ?: return
        val file = store.attachmentFile(key, att)
        if (!file.exists()) return
        val fake = fakeFile(kind)
        val apiKey = keys.get()
        if (apiKey == null && fake == null) return

        store.clearReadFailure(key, kind)
        running[taskId] = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (fake != null) {
                        delay(2500)
                        val o = JSONObject(fake.readText())
                        o.keys().asSequence().associateWith { k -> if (o.isNull(k)) null else o.get(k).toString() }
                    } else if (kind == AttachmentKind.INVOICE) {
                        ClaudeReader.readInvoice(apiKey!!, file, att.mime)
                    } else {
                        ClaudeReader.readMeter(apiKey!!, file, att.mime)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                running.remove(taskId)
                result.onSuccess { values ->
                    if (kind == AttachmentKind.INVOICE) {
                        store.applyInvoiceReading(key, InvoiceReading.fromStrings(values))
                    } else {
                        store.applyMeterReading(key, MeterReading.fromStrings(values))
                    }
                }.onFailure { e ->
                    store.recordReadFailure(key, kind, (e as? ReadFailure)?.message ?: "Automatic reading didn't work. Please type the numbers in.")
                }
            }
        }
    }
}
