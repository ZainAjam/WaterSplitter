package za.westend.watersplit.ui

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import za.westend.watersplit.data.BackupLocation

/** Android's "Save as" screen, opened in the backup location. She can still move to any other folder before saving. */
class SaveBackupDocument : ActivityResultContracts.CreateDocument("application/octet-stream") {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).putExtra(DocumentsContract.EXTRA_INITIAL_URI, BackupLocation.startUri(context))
}

/** Android's "choose a file" screen, opened in the backup location. She can still move to any other folder to find the file. */
class OpenBackupDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(DocumentsContract.EXTRA_INITIAL_URI, BackupLocation.startUri(context))
}
