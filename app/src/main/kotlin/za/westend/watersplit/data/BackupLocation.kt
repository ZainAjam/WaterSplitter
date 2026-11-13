package za.westend.watersplit.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
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
 * Where backups live.
 *
 * She can change the backup location once (Settings -> Backup -> Change backup location, in Android's folder picker: this phone, Google
 * Drive, OneDrive...). Until she does, it is the Documents folder in the phone's internal storage.
 *
 * "Save a backup" and "Restore a backup" open Android's file picker already in that folder (she can still go to any other folder). The
 * automatic backup after every completed month writes one file, "water-splitter-backup.wsbackup", into the same place and replaces it each
 * time (into the chosen folder, or into Documents through MediaStore, which needs no permission).
 *
 * The choice lives in this phone's preferences, not in data.json, so a restored backup never points at a folder that isn't there.
 */
object BackupLocation {
    const val FILE_NAME = "water-splitter-backup.wsbackup"
    private const val MIME = "application/octet-stream" // a specific type would make Android add its own extension
    private const val PREFS = "backup-location"
    private const val RW = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    /** Where backups go until she changes it. */
    const val DEFAULT_LABEL = "the Documents folder on this phone"

    /** Short form for settings: just the folder. */
    const val DEFAULT_SHORT = "Documents"

    /** A folder has been chosen (otherwise the Documents folder is used). */
    var configured by mutableStateOf(false)
        private set

    /** She has decided: either a folder, or "keep the default". Until then the settings banner shows. */
    var choiceMade by mutableStateOf(false)
        private set

    /** For example "Backups in Google Drive". */
    var label by mutableStateOf("")
        private set

    /** One sentence about the last automatic backup. */
    var summary by mutableStateOf("")
        private set

    var running by mutableStateOf(false)
        private set

    /** Where backups are going right now, in words. */
    val where: String get() = if (configured) label else DEFAULT_SHORT

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val choice = p.getString("choice", null)
        configured = choice == "folder" && p.getString("tree", null) != null
        choiceMade = choice != null
        label = p.getString("label", "").orEmpty()
        summary = p.getString("summary", "").orEmpty()
    }

    /** Where the file pickers should open: the chosen folder, else the Documents folder in internal storage. */
    fun startUri(context: Context): Uri {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tree = p.getString("tree", null)
        val folderId = p.getString("folderId", null)
        if (configured && tree != null && folderId != null) {
            return DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree), folderId)
        }
        return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "primary:Documents")
    }

    /** She chose to keep the default (the Documents folder). */
    fun useDefault(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString("tree", null)?.let { runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(it), RW) } }
        p.edit().clear().putString("choice", "default").apply()
        configured = false
        choiceMade = true
        label = ""
        summary = ""
    }

    /**
     * [picked] is the folder she navigated to in the folder picker: backups go straight into it. Returns a sentence when it didn't work,
     * else null. Do not call on the main thread.
     */
    fun setUp(context: Context, picked: Uri): String? = try {
        context.contentResolver.takePersistableUriPermission(picked, RW)
        val treeId = DocumentsContract.getTreeDocumentId(picked)
        val folderName = displayName(context, DocumentsContract.buildDocumentUriUsingTree(picked, treeId)) ?: "the chosen folder"
        val text = "$folderName in ${providerLabel(context, picked)}"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .clear()
            .putString("choice", "folder")
            .putString("tree", picked.toString())
            .putString("folderId", treeId)
            .putString("label", text)
            .apply()
        configured = true
        choiceMade = true
        label = text
        summary = ""
        null
    } catch (e: Exception) {
        "That folder couldn't be used. Choose a folder (not the very top of a drive) and try again."
    }

    /** The automatic backup after a completed month. Safe to call from anywhere; it runs in the background. */
    fun backupAsync(context: Context) {
        if (running) return
        val app = context.applicationContext
        running = true
        scope.launch {
            val result = runCatching { if (configured) backupToFolderBlocking(app) else backupToDocumentsBlocking(app) }
            val text = result.fold(
                { "Backed up automatically on " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm")) + "." },
                { "The last automatic backup didn't work (no internet, or the folder was removed?). It is tried again each time a month is completed." },
            )
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("summary", text).apply()
            summary = text
            running = false
        }
    }

    private fun backupToFolderBlocking(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tree = Uri.parse(p.getString("tree", null) ?: error("no folder"))
        val folderId = p.getString("folderId", null) ?: error("no folder")
        val cr = context.contentResolver
        val folderUri = DocumentsContract.buildDocumentUriUsingTree(tree, folderId)
        // One file only: the old one is replaced.
        findChild(context, tree, folderId, FILE_NAME)?.let {
            DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, it))
        }
        val doc = DocumentsContract.createDocument(cr, folderUri, MIME, FILE_NAME) ?: error("file not created")
        val out = cr.openOutputStream(doc, "w") ?: error("can't write")
        Backup.write(context, out)
    }

    /** The default: Documents/water-splitter-backup.zip, through MediaStore (no permission; Android 10 and newer). */
    private fun backupToDocumentsBlocking(context: Context) {
        check(Build.VERSION.SDK_INT >= 29) { "needs Android 10" }
        val cr = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relative = "Documents/"
        val existing = cr.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(FILE_NAME, relative),
            null,
        )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }
        val target = existing ?: cr.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            },
        ) ?: error("can't create the file")
        val out = cr.openOutputStream(target, "wt") ?: error("can't write")
        Backup.write(context, out)
    }

    /** "Google Drive", "OneDrive", "phone storage"... from the app that provides the folder. */
    private fun providerLabel(context: Context, tree: Uri): String {
        val authority = tree.authority.orEmpty()
        return when {
            authority == EXTERNAL_STORAGE -> "phone storage"
            authority.contains("docs.storage") -> "Google Drive"
            authority.contains("skydrive") || authority.contains("microsoft") -> "OneDrive"
            else -> context.packageManager.resolveContentProvider(authority, 0)?.applicationInfo?.loadLabel(context.packageManager)?.toString()
                ?: "the chosen location"
        }
    }

    private fun displayName(context: Context, docUri: Uri): String? =
        context.contentResolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** Document id of the file called [name] directly inside [parentId], or null. */
    private fun findChild(context: Context, tree: Uri, parentId: String, name: String): String? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        context.contentResolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                if (c.getString(1).equals(name, ignoreCase = true) && !isDir) return c.getString(0)
            }
        }
        return null
    }
}
