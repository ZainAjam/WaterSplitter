package za.westend.watersplit.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Opens Android's own contact picker (showing people with phone numbers). The app is handed only the one number that
 * was tapped, so no permission to read all contacts is needed.
 */
private class PickPhoneNumber : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/** (display name, phone number) of the picked contact row, or null. */
private fun readPickedContact(context: Context, uri: Uri): Pair<String, String>? = try {
    context.contentResolver.query(
        uri,
        arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
        null,
        null,
        null,
    )?.use { c -> if (c.moveToFirst()) Pair(c.getString(0).orEmpty(), c.getString(1).orEmpty()) else null }
} catch (e: Exception) {
    null
}

/** A button that lets the person choose a number from the phone's contacts. */
@Composable
fun FindInContactsButton(onPicked: (name: String, number: String) -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(PickPhoneNumber()) { uri ->
        if (uri != null) {
            val picked = readPickedContact(context, uri)
            if (picked != null && picked.second.isNotBlank()) {
                onPicked(picked.first, picked.second)
            } else {
                Toast.makeText(context, "Couldn't read a number for that contact.", Toast.LENGTH_SHORT).show()
            }
        }
    }
    FilledTonalButton(
        onClick = {
            try {
                launcher.launch(Unit)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, "No contacts app was found on this phone.", Toast.LENGTH_LONG).show()
            }
        },
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Icon(Icons.Filled.Person, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Find in my contacts")
    }
}
