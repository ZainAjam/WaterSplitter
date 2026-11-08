package za.westend.watersplit.data

import android.accounts.Account
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Keeps one backup file ("water-splitter-backup.zip", the same zip as Settings -> Backup) in a folder called
 * "Water Splitter data backup" in the person's Google Drive, refreshed every time a month is completed.
 *
 * It talks to Google Drive directly (no Drive app, no folder picker). The person agrees once on Google's own consent screen; after that
 * the backup runs by itself. The permission asked for is `drive.file`: the app can only see files and folders it created itself, never
 * the rest of her Drive. Google must know the app (a Cloud project with an Android OAuth client for this package and signing key;
 * see docs/DRIVE_BACKUP.md), otherwise Google refuses and the person is told it isn't set up.
 */
object DriveBackup {
    const val FOLDER_NAME = "Water Splitter data backup"
    const val FILE_NAME = "water-splitter-backup.zip"
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val PREFS = "drive-backup"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"

    /** She has connected a Google account. */
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
        configured = p.getString("account", null) != null
        summary = p.getString("summary", "").orEmpty()
    }

    /** The question to ask Google: "may this app create and change its own files in my Drive?" */
    fun request(accountEmail: String? = null): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE)))
            .apply { if (accountEmail != null) setAccount(Account(accountEmail, "com.google")) }
            .build()

    /** Google said yes (on the consent screen or silently): remember which account, and make the first backup. */
    fun onConnected(context: Context, result: AuthorizationResult) {
        val email = result.toGoogleSignInAccount()?.email ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("account", email).apply()
        configured = true
        backupAsync(context)
    }

    /** A sentence for the person when Google refuses or can't be reached. */
    fun explain(e: Exception): String = when {
        e is ApiException && e.statusCode == 10 ->
            "Google doesn't know this version of the app yet, so Drive backup isn't available. (The Google Cloud setup in docs/DRIVE_BACKUP.md is missing.)"
        e is ApiException && e.statusCode == 7 -> "No internet connection. Try again when you're online."
        e is ApiException && e.statusCode == 16 -> "That was cancelled."
        else -> "Couldn't connect to Google Drive (${e.javaClass.simpleName}). Please try again."
    }

    /** Stops backing up (what is already in Drive stays). */
    fun disconnect(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        configured = false
        summary = ""
    }

    /** Writes the backup now (if connected). Safe to call from anywhere; it runs in the background. */
    fun backupAsync(context: Context) {
        if (!configured || running) return
        val app = context.applicationContext
        running = true
        scope.launch {
            val text = try {
                backupBlocking(app)
                "Backed up to Google Drive on " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm")) + "."
            } catch (e: NeedsReconnect) {
                "Google Drive needs to be connected again. Tap Connect Google Drive."
            } catch (e: Exception) {
                "The last backup to Google Drive didn't work (no internet?). It is tried again each time a month is completed, or tap Back up now."
            }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("summary", text).apply()
            summary = text
            running = false
        }
    }

    private class NeedsReconnect : Exception()

    private fun backupBlocking(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val email = p.getString("account", null) ?: throw NeedsReconnect()
        val result = Tasks.await(Identity.getAuthorizationClient(context).authorize(request(email)))
        // Google wants the person to agree again (consent withdrawn, or she changed her password).
        if (result.hasResolution()) throw NeedsReconnect()
        val token = result.accessToken ?: throw NeedsReconnect()

        val zip = File(context.cacheDir, "drive-backup.zip")
        try {
            zip.outputStream().use { Backup.write(context, it) }
            val folderId = findOne(token, "name = '$FOLDER_NAME' and mimeType = '$FOLDER_MIME' and trashed = false")
                ?: createFolder(token)
            var fileId = findOne(token, "'$folderId' in parents and name = '$FILE_NAME' and trashed = false")
            if (fileId == null) fileId = createFileEntry(token, folderId)
            upload(token, fileId, zip)
        } finally {
            zip.delete()
        }
    }

    private fun findOne(token: String, query: String): String? {
        val url = "https://www.googleapis.com/drive/v3/files?q=${URLEncoder.encode(query, "UTF-8")}&fields=files(id)&spaces=drive&pageSize=1"
        val files = JSONObject(call("GET", url, token)).optJSONArray("files")
        return if (files != null && files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    private fun createFolder(token: String): String {
        val body = JSONObject().put("name", FOLDER_NAME).put("mimeType", FOLDER_MIME).toString()
        return JSONObject(call("POST", "https://www.googleapis.com/drive/v3/files?fields=id", token, body.toByteArray(), "application/json")).getString("id")
    }

    private fun createFileEntry(token: String, folderId: String): String {
        val body = JSONObject().put("name", FILE_NAME).put("mimeType", "application/zip").put("parents", org.json.JSONArray().put(folderId)).toString()
        return JSONObject(call("POST", "https://www.googleapis.com/drive/v3/files?fields=id", token, body.toByteArray(), "application/json")).getString("id")
    }

    /** Replaces the content of the file (so there is only ever one backup). */
    private fun upload(token: String, fileId: String, zip: File) {
        val url = URL("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("X-HTTP-Method-Override", "PATCH") // HttpURLConnection can't send PATCH itself
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/zip")
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(zip.length())
            zip.inputStream().use { input -> conn.outputStream.use { input.copyTo(it) } }
            if (conn.responseCode !in 200..299) error("upload failed: ${conn.responseCode}")
        } finally {
            conn.disconnect()
        }
    }

    private fun call(method: String, url: String, token: String, body: ByteArray? = null, contentType: String? = null): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType ?: "application/json")
                conn.outputStream.use { it.write(body) }
            }
            if (conn.responseCode !in 200..299) error("Drive answered ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
