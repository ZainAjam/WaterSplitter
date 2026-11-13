package za.westend.watersplit.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/** What a list is looking for. */
enum class FileKind { PDF, IMAGE, MONTHS, BACKUP }

/** A PDF, photo or Water Splitter file found on the phone. */
data class FoundFile(val file: File, val mime: String, val modified: Long, val size: Long)

/**
 * Finds PDFs and photos in Downloads, Documents, DCIM and Pictures (and everything inside them). Listing files saved by other apps
 * needs Android's "all files access", which the person switches on once in Settings.
 */
object FileScanner {
    private val FOLDERS = listOf("DCIM", "Download", "Documents", "Pictures")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
    private const val LIMIT = 3000

    fun hasAccess(): Boolean = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    /** Android's own switch for this app (Android 11+). */
    fun accessIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun isOldBackup(f: File): Boolean = try {
        java.util.zip.ZipFile(f).use { it.getEntry("data.json") != null }
    } catch (e: Exception) {
        false
    }

    /** Newest first. Do not call on the main thread. */
    fun scan(kind: FileKind): List<FoundFile> {
        val root = Environment.getExternalStorageDirectory()
        val found = ArrayList<FoundFile>()
        for (name in FOLDERS) {
            File(root, name).walkTopDown()
                .onEnter { !it.name.startsWith(".") } // .thumbnails, .trashed and the like
                .filter { it.isFile && !it.name.startsWith(".") }
                .forEach { f ->
                    val ext = f.extension.lowercase()
                    val mime = when {
                        kind == FileKind.PDF && ext == "pdf" -> "application/pdf"
                        kind == FileKind.IMAGE && ext in IMAGE_EXTENSIONS -> "image/$ext"
                        kind == FileKind.MONTHS && ext == "wsmonths" -> "application/octet-stream"
                        kind == FileKind.BACKUP && ext == "wsbackup" -> "application/octet-stream"
                        // backups made by older versions of the app were plain .zip files
                        kind == FileKind.BACKUP && ext == "zip" && f.name.contains("backup", ignoreCase = true) && isOldBackup(f) -> "application/zip"
                        else -> null
                    }
                    if (mime != null && f.length() > 0 && found.size < LIMIT) found.add(FoundFile(f, mime, f.lastModified(), f.length()))
                }
        }
        return found.sortedByDescending { it.modified }
    }
}
