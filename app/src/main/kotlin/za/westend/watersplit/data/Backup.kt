package za.westend.watersplit.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A backup is one zip file: the data file plus every invoice / meter photo kept with a month.
 * The Claude key is deliberately NOT included (it is tied to this phone's secure storage).
 */
object Backup {
    private const val DATA = "data.json"
    private const val ATTACHMENTS = "attachments"

    /** Writes the backup zip to [out]. Returns how many files went in. */
    fun write(context: Context, out: OutputStream): Int {
        var count = 0
        ZipOutputStream(out).use { zip ->
            val data = File(context.filesDir, DATA)
            if (data.exists()) {
                zip.putNextEntry(ZipEntry(DATA))
                data.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                count++
            }
            File(context.filesDir, ATTACHMENTS).walkTopDown().filter { it.isFile }.forEach { f ->
                zip.putNextEntry(ZipEntry(f.relativeTo(context.filesDir).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                count++
            }
        }
        return count
    }

    sealed interface RestoreResult {
        data class Done(val files: Int) : RestoreResult
        data class Failed(val reason: String) : RestoreResult
    }

    /**
     * Replaces everything on this phone with the contents of the backup in [input]. The current data is first set aside in
     * `before-restore/`, so a wrong restore can still be undone by hand. Nothing is changed unless the backup checks out.
     */
    fun restore(context: Context, input: InputStream): RestoreResult {
        val tmp = File(context.cacheDir, "restore").apply {
            deleteRecursively()
            mkdirs()
        }
        var count = 0
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory) continue
                    if (name != DATA && !name.startsWith("$ATTACHMENTS/")) continue // ignore anything unexpected
                    val target = File(tmp, name)
                    if (name.contains("..") || !target.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) {
                        return RestoreResult.Failed("This file isn't a valid backup.")
                    }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                    count++
                }
            }
        } catch (e: Exception) {
            tmp.deleteRecursively()
            return RestoreResult.Failed("That file couldn't be read as a backup.")
        }

        val data = File(tmp, DATA)
        if (!data.exists()) {
            tmp.deleteRecursively()
            return RestoreResult.Failed("This isn't a Water Splitter backup (it has no data in it).")
        }
        try {
            val o = JSONObject(data.readText())
            o.getJSONObject("settings")
            o.getJSONArray("months")
        } catch (e: Exception) {
            tmp.deleteRecursively()
            return RestoreResult.Failed("The data in this backup couldn't be read.")
        }

        val live = File(context.filesDir, DATA)
        val liveAttachments = File(context.filesDir, ATTACHMENTS)
        val before = File(context.filesDir, "before-restore").apply {
            deleteRecursively()
            mkdirs()
        }
        if (live.exists()) live.copyTo(File(before, DATA), overwrite = true)
        if (liveAttachments.exists()) liveAttachments.renameTo(File(before, ATTACHMENTS))
        data.copyTo(live, overwrite = true)
        File(tmp, ATTACHMENTS).takeIf { it.exists() }?.copyRecursively(File(context.filesDir, ATTACHMENTS), overwrite = true)
        tmp.deleteRecursively()
        return RestoreResult.Done(count)
    }
}
