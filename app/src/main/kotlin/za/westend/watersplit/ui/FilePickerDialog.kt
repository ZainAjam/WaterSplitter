package za.westend.watersplit.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.westend.watersplit.data.Packages
import za.westend.watersplit.share.FileKind
import za.westend.watersplit.share.FileScanner
import za.westend.watersplit.share.FoundFile
import java.text.DateFormat
import java.util.Date

/**
 * "Attach invoice" / "Attach meter photo": every PDF (or photo) found in Downloads, Documents, DCIM and Pictures, newest first, each
 * with a preview and the date and time it was saved. [onOther] opens Android's normal file picker for anything stored elsewhere.
 */
@Composable
fun FilePickerDialog(kind: FileKind, onPick: (FoundFile) -> Unit, onOther: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(FileScanner.hasAccess()) }
    // Coming back from Android's settings screen: look again.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) allowed = FileScanner.hasAccess() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    var files by remember { mutableStateOf<List<FoundFile>?>(null) }
    LaunchedEffect(allowed, kind) {
        files = if (allowed) withContext(Dispatchers.IO) { FileScanner.scan(kind) } else null
    }
    val noun = when (kind) {
        FileKind.PDF -> "PDF files"
        FileKind.IMAGE -> "photos"
        FileKind.MONTHS -> "shared-months files"
        FileKind.BACKUP -> "backup files"
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BackArrow(onClose)
                    Text(
                        when (kind) {
                            FileKind.PDF -> "Choose the invoice"
                            FileKind.IMAGE -> "Choose the meter photo"
                            FileKind.MONTHS -> "Choose the months file"
                            FileKind.BACKUP -> "Choose the backup"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                val list = files
                when {
                    !allowed -> Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(8.dp)) {
                        Text(
                            "To show the $noun on this phone, Water Splitter needs your permission to see files. " +
                                "Tap the button, switch on \"Allow access to manage all files\", then come back.",
                        )
                        Button(onClick = { context.startActivity(FileScanner.accessIntent(context)) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Allow access to files")
                        }
                        OutlinedButton(onClick = onOther, modifier = Modifier.fillMaxWidth()) { Text("Browse files myself instead") }
                    }
                    list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator()
                            Text("Looking for $noun…")
                        }
                    }
                    else -> {
                        OutlinedButton(onClick = onOther, modifier = Modifier.fillMaxWidth()) { Text("Look in other folders") }
                        if (list.isEmpty()) {
                            Text("No $noun were found in Downloads, Documents, DCIM or Pictures.", modifier = Modifier.padding(8.dp))
                        }
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(list, key = { it.file.path }) { f ->
                                FoundRow(f, kind, onClick = { onPick(f) })
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FoundRow(f: FoundFile, kind: FileKind, onClick: () -> Unit) {
    val isPackage = kind == FileKind.MONTHS || kind == FileKind.BACKUP
    val pages by if (isPackage) remember { mutableStateOf<List<android.graphics.Bitmap>?>(emptyList()) } else rememberPages(f.file, f.mime, 240, 1)
    val first = pages?.firstOrNull()
    val summary by produceState<String?>(null, f.file) { value = if (isPackage) withContext(Dispatchers.IO) { Packages.summary(f.file) } else null }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(width = 80.dp, height = if (isPackage) 56.dp else 100.dp), contentAlignment = Alignment.Center) {
            if (isPackage) {
                Text(if (kind == FileKind.MONTHS) "months" else "backup", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            } else if (first != null) {
                Image(first.asImageBitmap(), contentDescription = "Preview", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                Text(if (pages == null) "…" else "No preview", style = MaterialTheme.typography.bodySmall)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(f.file.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stamp(f.modified), style = MaterialTheme.typography.bodyMedium)
            if (isPackage) summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Text(
                f.file.parentFile?.name.orEmpty() + " · " + sizeText(f.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun stamp(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

private fun sizeText(bytes: Long): String = if (bytes >= 1_000_000) "%.1f MB".format(bytes / 1_000_000.0) else "${maxOf(1, bytes / 1000)} KB"
