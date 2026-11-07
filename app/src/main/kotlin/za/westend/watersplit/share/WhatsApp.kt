package za.westend.watersplit.share

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import za.westend.watersplit.engine.Phone
import java.io.File

object WhatsApp {
    private val packages = listOf("com.whatsapp", "com.whatsapp.w4b")

    /**
     * Shares the statement [image] to [phone] in WhatsApp when it is installed and the number looks valid.
     * Otherwise (or when WhatsApp asks who to send it to) the person picks the contact; with no WhatsApp at all
     * the normal Android share menu is used.
     * @return true when WhatsApp itself was opened, false when the share menu was used.
     */
    fun send(context: Context, image: File, phone: String?, message: String): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", image)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, message)
            clipData = ClipData.newRawUri(image.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pkg = packages.firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }
        val number = phone?.let { Phone.whatsappNumber(it) }
        val toStart = if (pkg != null) {
            intent.setPackage(pkg)
            if (number != null) intent.putExtra("jid", "$number@s.whatsapp.net")
            intent
        } else {
            Intent.createChooser(intent, "Send statement")
        }
        if (context !is Activity) toStart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(toStart)
        return pkg != null
    }
}
