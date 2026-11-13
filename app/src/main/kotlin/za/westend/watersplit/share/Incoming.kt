package za.westend.watersplit.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import java.io.File
import java.util.UUID

/** A file that was shared into the app (from Gmail, WhatsApp, Files...) and copied to private storage. */
data class SharedItem(val file: File, val mime: String, val displayName: String)

/** Files waiting for the person to say what they are and which month they belong to. */
class ShareInbox {
    val items = mutableStateListOf<SharedItem>()

    /** A .wsbackup or .wsmonths file somebody opened with this app, waiting for the person to confirm. */
    var pending by androidx.compose.runtime.mutableStateOf<za.westend.watersplit.data.PendingPackage?>(null)
}

object Incoming {
    /**
     * Copies everything shared with [intent] into the cache. Content URIs given to us by other apps (Gmail...) are only
     * readable for a short while, so this copies at once. Do not call on the main thread.
     * Files that are neither PDFs nor photos are skipped.
     */
    fun fromIntent(context: Context, intent: Intent): List<SharedItem> {
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        return uris.mapNotNull { copyUri(context, it) }
    }

    /** Copies one URI into the cache and works out whether it is a PDF or a photo. Do not call on the main thread. */
    fun copyUri(context: Context, uri: Uri): SharedItem? {
        val dir = File(context.cacheDir, "incoming").apply { mkdirs() }
        val target = File(dir, UUID.randomUUID().toString())
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { src -> target.outputStream().use { dst -> src.copyTo(dst) } }
            val mime = sniffMime(target)
            if (mime == null) {
                target.delete()
                null
            } else {
                SharedItem(target, mime, displayName(context, uri) ?: "shared file")
            }
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /** Looks at the first bytes (not the name or the sender's claim). Returns null for anything but PDF / photos. */
    fun sniffMime(file: File): String? {
        val b = ByteArray(16)
        val n = file.inputStream().use { it.read(b) }
        if (n < 4) return null
        fun at(i: Int) = b[i].toInt() and 0xFF
        return when {
            at(0) == '%'.code && at(1) == 'P'.code && at(2) == 'D'.code && at(3) == 'F'.code -> "application/pdf"
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
            at(0) == 0x89 && at(1) == 'P'.code && at(2) == 'N'.code && at(3) == 'G'.code -> "image/png"
            n >= 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
            n >= 12 && String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> "image/heic"
            else -> null
        }
    }

    /** Throws away files that were shared in but never filed (older than a day). */
    fun cleanUp(context: Context) {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        File(context.cacheDir, "incoming").listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }
}
