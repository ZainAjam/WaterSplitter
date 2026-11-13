package za.westend.watersplit.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts

/** Where the file picker starts: a folder on the phone's internal storage ("primary"), e.g. "Download" or "Pictures/WhatsApp". */
object StartFolder {
    const val DOWNLOADS = "primary:Download"
    const val WHATSAPP_PICTURES = "primary:Pictures/WhatsApp"
}

/**
 * The normal "choose a file" screen, but opened in [startFolder] instead of wherever it was last. It also asks for the right to
 * delete the chosen file later (the person is asked first, once the file is safely stored in the app).
 */
class PickDocumentFrom(private val startFolder: String) : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", startFolder))
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }
}

/** Deleting the original of a file that was attached with the picker, once the app holds its own copy. */
object SourceFiles {
    /**
     * Keeps the right to delete [uri] after this screen closes. Returns the address to store, or null when the file can't be
     * deleted by this app (then nothing is offered later).
     */
    fun rememberForDelete(context: Context, uri: Uri): String? = try {
        if (uri.scheme == "file") {
            uri.toString() // found by the app's own scanner: it may delete the file directly
        } else if (!supportsDelete(context, uri)) {
            null
        } else {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            uri.toString()
        }
    } catch (e: Exception) {
        null
    }

    private fun supportsDelete(context: Context, uri: Uri): Boolean =
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
            c.moveToFirst() && (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0
        } ?: false

    /** Gives up the right to delete [stored] (the person chose to keep the original). */
    fun forget(context: Context, stored: String) {
        if (stored.startsWith("file:")) return
        context.contentResolver.releasePersistableUriPermission(Uri.parse(stored), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    /** Deletes the original. Returns false when it was already gone, moved, or Android refused. Do not call on the main thread. */
    fun delete(context: Context, stored: String): Boolean {
        val uri = Uri.parse(stored)
        if (uri.scheme == "file") return uri.path?.let { java.io.File(it).delete() } ?: false
        val ok = try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            false
        }
        try {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) {
            // nothing to release
        }
        return ok
    }
}
