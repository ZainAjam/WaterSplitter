package za.westend.watersplit.update

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import za.westend.watersplit.BuildConfig
import za.westend.watersplit.engine.Versions
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A newer version published as a GitHub release. */
data class Release(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val sizeBytes: Long,
    /** SHA-256 of the APK when GitHub provides it (checked after the download). */
    val sha256: String?,
)

class UpdateFailure(message: String) : Exception(message)

/**
 * Checks the project's GitHub releases for a newer APK, downloads it and hands it to Android's installer.
 * Android itself refuses an update that is not signed with the same key as the installed app, and the person has to
 * confirm the install, so a tampered download cannot silently replace the app.
 */
object Updater {
    private const val PREFS = "updates"
    private const val CHECK_EVERY_MS = 24L * 60 * 60 * 1000

    /** Observable by the screens: the newest release found that is newer than the installed version (or null). */
    var available by mutableStateOf<Release?>(null)
        private set

    var checking by mutableStateOf(false)
        private set

    var lastResult by mutableStateOf<String?>(null)
        private set

    /** 0..1 while the new version is downloading (-1 = size unknown), null when no download is running. */
    var progress by mutableStateOf<Float?>(null)
        private set

    /** Why the last download failed (null = fine). */
    var problem by mutableStateOf<String?>(null)
        private set

    /** Whether Android lets this app install APKs ("install unknown apps"). Refreshed whenever the app comes to the front. */
    var installAllowed by mutableStateOf(true)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True once she has been shown the first-run "allow updates" screen (kept outside data.json so a restored backup never resets it). */
    var permissionAsked by mutableStateOf(true)
        private set

    fun refreshPermission(context: Context) {
        installAllowed = canInstall(context)
        permissionAsked = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("askedInstallPermission", false)
    }

    fun markPermissionAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("askedInstallPermission", true).apply()
        permissionAsked = true
    }

    /** Downloads the available version and hands it to Android's installer (which asks the person to confirm). */
    fun downloadAndInstall(context: Context) {
        val release = available ?: return
        if (progress != null) return
        val app = context.applicationContext
        problem = null
        scope.launch {
            progress = 0f
            val result = runCatching { withContext(Dispatchers.IO) { download(app, release) { progress = it } } }
            progress = null
            result.onSuccess { apk -> withContext(Dispatchers.Main) { install(app, apk) } }
                .onFailure { problem = (it as? UpdateFailure)?.message ?: "The download didn't work. Please try again." }
        }
    }

    val isConfigured: Boolean get() = BuildConfig.UPDATE_REPO.isNotBlank()

    /**
     * Read-only GitHub token for a private repository (baked into release builds by the workflow; blank for a public one).
     * It can only read this one repository, and the APK it fetches already contains everything it could show.
     */
    private val hasToken: Boolean get() = BuildConfig.UPDATE_TOKEN.isNotBlank()

    private fun authorize(conn: HttpURLConnection) {
        if (hasToken) conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.UPDATE_TOKEN}")
    }

    fun installedVersion(): String = BuildConfig.VERSION_NAME

    /** Debug builds can point the check at a local test server by putting its address in files/update-base.txt. */
    private fun apiBase(context: Context): String {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val override = File(context.filesDir, "update-base.txt")
        return if (debuggable && override.exists()) override.readText().trim().trimEnd('/') else "https://api.github.com"
    }

    /** Checks at most once a day, quietly, when the app starts. */
    fun checkIfDue(context: Context) {
        if (!isConfigured) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong("lastCheck", 0L)
        if (System.currentTimeMillis() - last < CHECK_EVERY_MS) return
        check(context, quiet = true)
    }

    fun check(context: Context, quiet: Boolean = false) {
        if (!isConfigured || checking) return
        val app = context.applicationContext
        checking = true
        if (!quiet) lastResult = null
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { fetchLatest(app) } }
            withContext(Dispatchers.Main) {
                checking = false
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("lastCheck", System.currentTimeMillis()).apply()
                result.onSuccess { release ->
                    if (release != null && Versions.isNewer(release.version, installedVersion())) {
                        available = release
                        lastResult = "Version ${release.version} is available."
                    } else {
                        available = null
                        if (!quiet) lastResult = "You're up to date (version ${installedVersion()})."
                    }
                }.onFailure { e ->
                    if (!quiet) lastResult = (e as? UpdateFailure)?.message ?: "Couldn't check for updates. Check the internet connection."
                }
            }
        }
    }

    private fun fetchLatest(context: Context): Release? {
        val conn = URL("${apiBase(context)}/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=10").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "water-split-app")
            authorize(conn)
            when (val code = conn.responseCode) {
                200 -> Unit
                404 -> return null // no release published yet
                else -> throw UpdateFailure("GitHub answered with error $code. Try again later.")
            }
            // The newest published release that carries an APK. Prereleases count too (the "latest" endpoint would skip them).
            val list = org.json.JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            for (r in 0 until list.length()) {
              val o = list.getJSONObject(r)
              if (o.optBoolean("draft")) continue
              val assets = o.optJSONArray("assets") ?: continue
              for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                return Release(
                    version = o.optString("tag_name").removePrefix("v"),
                    notes = o.optString("body").trim(),
                    // A private repository only serves its files through the API address, and only to someone with the token.
                    apkUrl = if (hasToken) a.getString("url") else a.getString("browser_download_url"),
                    apkName = name,
                    sizeBytes = a.optLong("size", 0L),
                    sha256 = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
                )
              }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the APK into the app's cache. [onProgress] gets 0..1 (or -1 when the size is unknown). Blocking. */
    fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply {
            deleteRecursively()
            mkdirs()
        }
        val target = File(dir, "update.apk")
        var conn = URL(release.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "water-split-app")
            if (hasToken) conn.setRequestProperty("Accept", "application/octet-stream")
            authorize(conn)
            // GitHub redirects to a signed storage address; the token must NOT be sent there, so follow the redirect by hand.
            var hops = 0
            while (conn.responseCode in 301..308 && hops++ < 5) {
                val next = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = URL(next).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "water-split-app")
            }
            if (conn.responseCode != 200) throw UpdateFailure("The download failed (error ${conn.responseCode}).")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        onProgress(if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else -1f)
                    }
                }
            }
            val expected = release.sha256
            if (expected != null) {
                val got = digest.digest().joinToString("") { "%02x".format(it) }
                if (!got.equals(expected, ignoreCase = true)) {
                    target.delete()
                    throw UpdateFailure("The downloaded file didn't match what GitHub published, so it was thrown away.")
                }
            }
            return target
        } finally {
            conn.disconnect()
        }
    }

    /** Android only lets an app install other APKs once the person has allowed it for this app. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the screen where the person allows "Install unknown apps" for this app. */
    fun allowInstallIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Hands the downloaded APK to Android's installer, which asks the person to confirm. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
