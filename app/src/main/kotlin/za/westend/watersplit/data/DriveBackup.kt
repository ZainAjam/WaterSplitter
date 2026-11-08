package za.westend.watersplit.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Keeps one backup file ("water-splitter-backup.zip", the same zip as Settings -> Backup) in a folder called
 * "Water Splitter data backup" in the person's Google Drive, refreshed every time a month is completed.
 *
 * It uses Android's own folder picker (Google Drive shows up in it), so the app never signs in to Google and holds no
 * Drive permission beyond the one folder she chose. The folder choice lives in this phone's preferences, not in data.json,
 * so restoring a backup on another phone never points at a folder that isn't there.
 */
object DriveBackup {
    const val FOLDER_NAME = "Water Splitter data backup"
    const val FILE_NAME = "water-splitter-backup.zip"
    private const val PREFS = "drive-backup"
    private const val RW = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** A folder has been chosen. */
    var configured by mutableStateOf(false)
        private set

    /** One sentence about the last backup (when it worked, or why it didn't). */
    var summary by mutableStateOf("")
        private set

    var running by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        configured = p.getString("tree", null) != null
        summary = p.getString("summary", "").orEmpty()
    }

    /**
     * [picked] is any folder she chose in the folder picker (for Google Drive, normally "My Drive"). The backup goes into a folder
     * named [FOLDER_NAME] inside it, which is created when it isn't there yet (or used directly if she picked a folder of that name).
     * Returns a sentence to show when it didn't work, or null. Do not call on the main thread.
     */
    fun setUp(context: Context, picked: Uri): String? = try {
        val cr = context.contentResolver
        cr.takePersistableUriPermission(picked, RW)
        val treeId = DocumentsContract.getTreeDocumentId(picked)
        val pickedDoc = DocumentsContract.buildDocumentUriUsingTree(picked, treeId)
        val folderId = if (displayName(context, pickedDoc) == FOLDER_NAME) {
            treeId
        } else {
            findChild(context, picked, treeId, FOLDER_NAME, folder = true)
                ?: DocumentsContract.createDocument(cr, pickedDoc, DocumentsContract.Document.MIME_TYPE_DIR, FOLDER_NAME)
                    ?.let { DocumentsContract.getDocumentId(it) }
                ?: error("folder not created")
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("tree", picked.toString())
            .putString("folderId", folderId)
            .apply()
        configured = true
        null
    } catch (e: Exception) {
        "That folder couldn't be used. Choose a folder in your Google Drive (for example My Drive) and try again."
    }

    /** Writes the backup now (if a folder was chosen). Safe to call from anywhere; it runs in the background. */
    fun backupAsync(context: Context) {
        if (!configured || running) return
        val app = context.applicationContext
        running = true
        scope.launch {
            val result = runCatching { backupBlocking(app) }
            val text = result.fold(
                { "Backed up to Google Drive on " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm")) + "." },
                { "The last backup to Google Drive didn't work. It is tried again each time a month is completed, or tap Back up now." },
            )
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("summary", text).apply()
            summary = text
            running = false
        }
    }

    private fun backupBlocking(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tree = Uri.parse(p.getString("tree", null) ?: error("no folder"))
        val folderId = p.getString("folderId", null) ?: error("no folder")
        val cr = context.contentResolver
        val folderUri = DocumentsContract.buildDocumentUriUsingTree(tree, folderId)
        // One file only: the old one is replaced.
        findChild(context, tree, folderId, FILE_NAME, folder = false)?.let {
            DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, it))
        }
        val doc = DocumentsContract.createDocument(cr, folderUri, "application/zip", FILE_NAME) ?: error("file not created")
        val out = cr.openOutputStream(doc, "w") ?: error("can't write")
        Backup.write(context, out)
    }

    /** Stops using the chosen folder (the backup already in it stays). */
    fun forget(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString("tree", null)?.let { runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(it), RW) } }
        p.edit().clear().apply()
        configured = false
        summary = ""
    }

    private fun displayName(context: Context, docUri: Uri): String? =
        context.contentResolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** Document id of the child of [parentId] called [name] (a folder or a file), or null. */
    private fun findChild(context: Context, tree: Uri, parentId: String, name: String, folder: Boolean): String? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        context.contentResolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                if (c.getString(1) == name && isDir == folder) return c.getString(0)
            }
        }
        return null
    }
}
