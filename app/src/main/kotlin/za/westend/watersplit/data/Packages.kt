package za.westend.watersplit.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** The two file types the app owns: a whole-app backup (.wsbackup) and a handful of months to share with someone (.wsmonths). */
enum class PackageKind(val extension: String) { BACKUP("wsbackup"), MONTHS("wsmonths") }

/** A file that arrived from outside (tapped in Files, WhatsApp, Gmail...) and has been copied into the cache. */
class PendingPackage(val kind: PackageKind, val file: File)

/** Months read from a .wsmonths file, with their attachments unpacked in [dir] (one folder per month). */
class MonthsFile(val months: List<MonthRecord>, val drafts: List<Draft>, val dir: File)

object Packages {
    private const val MONTHS_JSON = "months.json"
    private const val ATTACHMENTS = "attachments/"

    /** Writes the selected months (and the invoices / photos kept with them) as one file. Returns how many months went in. */
    fun writeMonths(store: Store, keys: Set<String>, out: OutputStream): Int {
        val chosen = store.months.filter { it.key in keys }
        // Months still in progress travel too (without the address the originals were attached from).
        val chosenDrafts = store.drafts.filter { it.key in keys }.map { it.copy(invoice = it.invoice?.copy(sourceUri = null), meter = it.meter?.copy(sourceUri = null)) }
        ZipOutputStream(out).use { zip ->
            val root = JSONObject().put("format", 2)
                .put("months", JSONArray().also { a -> chosen.forEach { a.put(it.toJson()) } })
                .put("drafts", JSONArray().also { a -> chosenDrafts.forEach { a.put(it.toJson()) } })
            zip.putNextEntry(ZipEntry(MONTHS_JSON))
            zip.write(root.toString().toByteArray())
            zip.closeEntry()
            val attached = chosen.map { it.key to listOfNotNull(it.invoice, it.meter) } + chosenDrafts.map { it.key to listOfNotNull(it.invoice, it.meter) }
            for ((key, atts) in attached) {
                for (att in atts) {
                    val f = store.attachmentFile(key, att)
                    if (!f.exists()) continue
                    zip.putNextEntry(ZipEntry("$ATTACHMENTS$key/${att.fileName}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        return chosen.size + chosenDrafts.size
    }

    /**
     * Copies what another app handed us ([uri]) into the cache and says what it is. Returns null when it is neither a backup nor a
     * months file. Looks inside the file, not at its name, because other apps often rename what they pass on. Do not call on the main thread.
     */
    fun receive(context: Context, uri: Uri): PendingPackage? {
        val cr = context.contentResolver
        val isZip = try {
            cr.openInputStream(uri)?.use { s ->
                val b = ByteArray(4)
                s.read(b) == 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2].toInt() == 3 && b[3].toInt() == 4
            } ?: false
        } catch (e: Exception) {
            false
        }
        if (!isZip) return null
        val dir = File(context.cacheDir, "packages").apply { mkdirs() }
        val target = File(dir, UUID.randomUUID().toString())
        return try {
            cr.openInputStream(uri)!!.use { src -> target.outputStream().use { src.copyTo(it) } }
            val kind = ZipFile(target).use { z ->
                when {
                    z.getEntry(MONTHS_JSON) != null -> PackageKind.MONTHS
                    z.getEntry("data.json") != null -> PackageKind.BACKUP
                    else -> null
                }
            }
            if (kind == null) {
                target.delete()
                null
            } else {
                PendingPackage(kind, target)
            }
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    /** One line about what is inside a months file or a backup, for the file lists. Null when it can't be read. */
    fun summary(file: File): String? = try {
        ZipFile(file).use { z ->
            val months = z.getEntry(MONTHS_JSON)
            val data = z.getEntry("data.json")
            when {
                months != null -> {
                    val root = JSONObject(z.getInputStream(months).readBytes().toString(Charsets.UTF_8))
                    val arr = root.getJSONArray("months")
                    val dr = root.optJSONArray("drafts")
                    val labels = ((0 until arr.length()).map { monthFromJson(arr.getJSONObject(it)).let { m -> m.key to m.label } } +
                        (0 until (dr?.length() ?: 0)).map { draftFromJson(dr!!.getJSONObject(it)).let { d -> d.key to d.label + " (in progress)" } })
                        .sortedBy { it.first }.map { it.second }
                    val shown = if (labels.size <= 3) labels.joinToString(", ") else labels.take(2).joinToString(", ") + " ... " + labels.last()
                    "${labels.size} ${if (labels.size == 1) "month" else "months"}: $shown"
                }
                data != null -> {
                    val o = JSONObject(z.getInputStream(data).readBytes().toString(Charsets.UTF_8))
                    val n = o.getJSONArray("months").length()
                    "Whole-app backup with $n ${if (n == 1) "month" else "months"}"
                }
                else -> null
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Unpacks a months file into the cache. Returns null when it can't be read. Do not call on the main thread. */
    fun readMonths(context: Context, file: File): MonthsFile? = try {
        val dir = File(context.cacheDir, "months-import").apply {
            deleteRecursively()
            mkdirs()
        }
        var json: String? = null
        var unsafe = false
        ZipFile(file).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory) continue
                if (e.name == MONTHS_JSON) {
                    json = z.getInputStream(e).readBytes().toString(Charsets.UTF_8)
                } else if (e.name.startsWith(ATTACHMENTS)) {
                    val target = File(dir, e.name.removePrefix(ATTACHMENTS))
                    if (e.name.contains("..") || !target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                        unsafe = true
                        continue
                    }
                    target.parentFile?.mkdirs()
                    z.getInputStream(e).use { src: InputStream -> target.outputStream().use { src.copyTo(it) } }
                }
            }
        }
        val text = json
        if (unsafe || text == null) {
            null
        } else {
            val root = JSONObject(text)
            val arr = root.getJSONArray("months")
            val dr = root.optJSONArray("drafts")
            MonthsFile(
                (0 until arr.length()).map { monthFromJson(arr.getJSONObject(it)) },
                (0 until (dr?.length() ?: 0)).map { draftFromJson(dr!!.getJSONObject(it)) },
                dir,
            )
        }
    } catch (e: Exception) {
        null
    }
}
