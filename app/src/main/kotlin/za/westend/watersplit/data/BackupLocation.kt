package za.westend.watersplit.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Where backups go.
 *
 * By default the backup goes into "Backups/Water Splitter" in the phone's internal storage (Android 11+ lets an app create a top-level folder
 * only with the "All files access" permission, which the permission banner asks for; older phones use Download/Water Splitter data backup).
 * She can instead pick a folder once, in Android's folder picker: on the phone, or in any app that offers its
 * storage there (Google Drive, OneDrive...). Either way "Save a backup" and the automatic backup after every completed month write
 * ONE file ("water-splitter-backup.zip", the same zip as always) there and replace it each time.
 *
 * The choice lives in this phone's preferences, not in data.json, so a restored backup never points at a folder that isn't there.
 */
object BackupLocation {
    const val FOLDER_NAME = "Water Splitter data backup"
    const val FILE_NAME = "water-splitter-backup.zip"
    private const val PREFS = "backup-location"
    private const val RW = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** A folder has been chosen (so backups are automatic). */
    var configured by mutableStateOf(false)
        private set

    /** She has decided: either a folder, or "keep the default". Until then the app reminds her. */
    var choiceMade by mutableStateOf(false)
        private set

    /** For example "Water Splitter data backup in Google Drive". */
    var label by mutableStateOf("")
        private set

    /** One sentence about the last automatic backup. */
    var summary by mutableStateOf("")
        private set

    var running by mutableStateOf(false)
        private set

    private const val DEFAULT_PARENT = "Backups"
    private const val DEFAULT_FOLDER = "Water Splitter"

    /** Where the backup goes when no folder was chosen. */
    val DEFAULT_LABEL: String
        get() = if (Build.VERSION.SDK_INT >= 30) {
            "the $DEFAULT_PARENT/$DEFAULT_FOLDER folder in this phone's internal storage"
        } else {
            "the Downloads folder on this phone (Download/$FOLDER_NAME)"
        }

    /** Where backups are going right now, in words. */
    val where: String get() = if (configured) label else DEFAULT_LABEL

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val choice = p.getString("choice", null)
        configured = choice == "folder" && p.getString("tree", null) != null
        choiceMade = choice != null
        label = p.getString("label", "").orEmpty()
        summary = p.getString("summary", "").orEmpty()
    }

    /** She chose to keep the default way (the Android Save screen, asked each time). */
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
     * [picked] is a folder she chose in the folder picker. The backup goes into a folder named [FOLDER_NAME] inside it (created when it
     * isn't there yet; or the folder itself when it already has that name). Returns a sentence when it didn't work, else null.
     * Do not call on the main thread.
     */
    fun setUp(context: Context, picked: Uri): String? = try {
        val cr = context.contentResolver
        cr.takePersistableUriPermission(picked, RW)
        val treeId = DocumentsContract.getTreeDocumentId(picked)
        val pickedDoc = DocumentsContract.buildDocumentUriUsingTree(picked, treeId)
        val folderId = if (displayName(context, pickedDoc).equals(FOLDER_NAME, ignoreCase = true)) {
            treeId
        } else {
            findChild(context, picked, treeId, FOLDER_NAME, folder = true)
                ?: DocumentsContract.createDocument(cr, pickedDoc, DocumentsContract.Document.MIME_TYPE_DIR, FOLDER_NAME)
                    ?.let { DocumentsContract.getDocumentId(it) }
                ?: error("folder not created")
        }
        val where = providerLabel(context, picked)
        val text = "$FOLDER_NAME in $where"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .clear()
            .putString("choice", "folder")
            .putString("tree", picked.toString())
            .putString("folderId", folderId)
            .putString("label", text)
            .apply()
        configured = true
        choiceMade = true
        label = text
        summary = ""
        null
    } catch (e: Exception) {
        "That folder couldn't be used. Choose or create a folder (not the very top of a drive) and try again."
    }

    /** Writes the backup now, to the chosen folder or else the Downloads folder. Safe to call from anywhere; it runs in the background. */
    fun backupAsync(context: Context) {
        if (running) return
        val app = context.applicationContext
        running = true
        scope.launch {
            val result = runCatching { if (configured) backupBlocking(app) else backupToDefaultBlocking(app) }
            val text = result.fold(
                { "Backed up on " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm")) + "." },
                { e ->
                    if (e is NeedsFileAccess) {
                        "To save backups in $DEFAULT_PARENT/$DEFAULT_FOLDER, allow file access (the red button at the top of the main screen)."
                    } else {
                        "The last backup didn't work (no internet, or the folder was removed?). It is tried again each time a month is completed, or tap Save a backup."
                    }
                },
            )
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("summary", text).apply()
            summary = text
            running = false
        }
    }

    private class NeedsFileAccess : Exception()

    /** The default location, replaced each time: Backups/Water Splitter on Android 11+ (needs All files access), else Download/. */
    private fun backupToDefaultBlocking(context: Context) {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) throw NeedsFileAccess()
            val dir = File(Environment.getExternalStorageDirectory(), "$DEFAULT_PARENT/$DEFAULT_FOLDER")
            dir.mkdirs()
            check(dir.isDirectory) { "can't create the folder" }
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.outputStream().use { Backup.write(context, it) }
            val target = File(dir, FILE_NAME)
            if (!tmp.renameTo(target)) {
                target.delete()
                check(tmp.renameTo(target)) { "can't replace the old backup" }
            }
            // so file managers and the picker see it straight away
            MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf("application/zip"), null)
        } else {
            backupToDownloadsBlocking(context)
        }
    }

    /** Older phones: Download/Water Splitter data backup/water-splitter-backup.zip through MediaStore (no permission). */
    private fun backupToDownloadsBlocking(context: Context) {
        check(Build.VERSION.SDK_INT >= 29) { "needs Android 10" }
        val cr = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME/"
        val existing = cr.query(
            collection,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
            arrayOf(FILE_NAME, relative),
            null,
        )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }
        val target = existing ?: cr.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, relative)
            },
        ) ?: error("can't create the file")
        val out = cr.openOutputStream(target, "wt") ?: error("can't write")
        Backup.write(context, out)
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

    /** "Google Drive", "OneDrive", "Phone storage"... from the app that provides the folder. */
    private fun providerLabel(context: Context, tree: Uri): String {
        val authority = tree.authority.orEmpty()
        return when {
            authority == "com.android.externalstorage.documents" -> "phone storage"
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
                // Drive can show the same name with other capital letters; treat them as the same folder so no duplicate is made.
                if (c.getString(1).equals(name, ignoreCase = true) && isDir == folder) return c.getString(0)
            }
        }
        return null
    }
}
