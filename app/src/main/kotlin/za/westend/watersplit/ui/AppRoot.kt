package za.westend.watersplit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import za.westend.watersplit.data.AttachmentKind
import za.westend.watersplit.data.Store
import za.westend.watersplit.engine.House
import za.westend.watersplit.share.ShareInbox
import za.westend.watersplit.share.ShareSheet
import za.westend.watersplit.update.Updater
import za.westend.watersplit.share.StatementImage

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object ServiceSettings : Screen
    data class AppSettings(val back: Screen = Settings) : Screen
    data class Setup(val step: Int) : Screen
    data class Tenants(val back: Screen = ServiceSettings) : Screen
    data class TenantEdit(val id: String?, val house: House, val back: Screen, val earlier: Boolean = false) : Screen
    data class Draft(val key: String) : Screen
    data class Review(val key: String) : Screen
    data class Viewer(val key: String, val kind: AttachmentKind, val back: Screen) : Screen
    data class Preview(val key: String, val house: House) : Screen
}

@Composable
fun AppRoot(store: Store, inbox: ShareInbox) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val incoming = inbox.items.firstOrNull()

    fun dropIncoming(deleteFile: Boolean) {
        incoming?.let {
            if (deleteFile) it.file.delete()
            inbox.items.remove(it)
        }
    }

    // Where the phone's own back button / gesture goes from here (null = nowhere, e.g. the home page or the first setup step).
    fun parentOf(s: Screen): Screen? = when (s) {
        Screen.Home -> null
        is Screen.Setup -> if (s.step > 0) Screen.Setup(s.step - 1) else null
        Screen.ServiceSettings -> Screen.Settings
        is Screen.AppSettings -> s.back
        is Screen.Tenants -> s.back
        is Screen.TenantEdit -> s.back
        is Screen.Viewer -> s.back
        is Screen.Preview -> Screen.Review(s.key)
        else -> Screen.Home
    }

    BackHandler(enabled = incoming != null || parentOf(screen) != null) {
        if (incoming != null) dropIncoming(deleteFile = true) else parentOf(screen)?.let { screen = it }
    }

    // The background fills the whole window (behind the system bars); only the content is inset.
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (incoming != null) {
                // Something was shared to the app: ask what it is and which month it belongs to first.
                ImportScreen(
                    store = store,
                    item = incoming,
                    remaining = inbox.items.size,
                    onAdded = { key ->
                        dropIncoming(deleteFile = false) // the file was moved into the month's storage
                        screen = Screen.Draft(key)
                    },
                    onCancel = { dropIncoming(deleteFile = true) },
                )
            } else if (Updater.isConfigured && !Updater.installAllowed && !Updater.permissionAsked) {
                // First start after installing: ask for permission to install updates before anything else (even a backup restore).
                InstallPermissionScreen()
            } else {
                when (val s = screen) {
                    Screen.Home -> if (!store.settings.setupDone) {
                        // First time the app is used: the wizard comes before anything else.
                        LaunchedEffect(Unit) { screen = Screen.Setup(0) }
                    } else {
                        HomeScreen(
                            store = store,
                            onNew = {
                                val key = store.nextFreeMonth().key
                                store.newDraft(key)
                                screen = Screen.Draft(key)
                            },
                            onOpenDraft = { screen = Screen.Draft(it) },
                            onOpen = { screen = Screen.Review(it) },
                            onSettings = { screen = Screen.Settings },
                        )
                    }
                    is Screen.Setup -> SetupScreen(
                        store = store,
                        step = s.step,
                        onStep = { screen = Screen.Setup(it) },
                        onAddEarlier = { house -> screen = Screen.TenantEdit(null, house, back = s, earlier = true) },
                        onEditTenancy = { id, house -> screen = Screen.TenantEdit(id, house, back = s) },
                        onFinish = {
                            store.updateSettings(store.settings.copy(setupDone = true))
                            screen = Screen.Home
                        },
                    )
                    Screen.Settings -> SettingsScreen(
                        onBack = { screen = Screen.Home },
                        onService = { screen = Screen.ServiceSettings },
                        onApp = { screen = Screen.AppSettings() },
                    )
                    Screen.ServiceSettings -> ServiceSettingsScreen(
                        store,
                        onBack = { screen = Screen.Settings },
                        onTenants = { screen = Screen.Tenants() },
                    )
                    is Screen.AppSettings -> AppSettingsScreen(store, onBack = { screen = s.back })
                    is Screen.Tenants -> TenantsScreen(
                        store = store,
                        onBack = { screen = s.back },
                        onEdit = { id, house -> screen = Screen.TenantEdit(id, house, back = s) },
                    )
                    is Screen.TenantEdit -> TenantEditScreen(
                        store = store,
                        id = s.id,
                        house = s.house,
                        earlier = s.earlier,
                        onDone = { screen = s.back },
                    )
                    is Screen.Draft -> DraftScreen(
                        store = store,
                        key = s.key,
                        onBack = { screen = Screen.Home },
                        onKeyChanged = { screen = Screen.Draft(it) },
                        onView = { kind -> screen = Screen.Viewer(s.key, kind, s) },
                        onReview = { screen = Screen.Review(it) },
                        onTenants = { screen = Screen.Tenants(back = s) },
                        onSettings = { screen = Screen.AppSettings(back = s) },
                    )
                    is Screen.Review -> ReviewScreen(
                        store = store,
                        key = s.key,
                        onBack = { screen = Screen.Home },
                        onEdit = { key ->
                            if (store.reopenAsDraft(key) != null) screen = Screen.Draft(key)
                        },
                        onView = { kind -> screen = Screen.Viewer(s.key, kind, s) },
                        onPreview = { house -> screen = Screen.Preview(s.key, house) },
                        onTenants = { screen = Screen.Tenants(back = s) },
                    )
                    is Screen.Viewer -> {
                        val att = (store.draft(s.key)?.attachment(s.kind) ?: store.month(s.key)?.attachment(s.kind))
                        val file = att?.let { store.attachmentFile(s.key, it) }
                        if (att != null && file != null && file.exists()) {
                            AttachmentViewer(
                                title = s.kind.label.replaceFirstChar { it.uppercase() },
                                file = file,
                                mime = att.mime,
                                onClose = { screen = s.back },
                            )
                        } else {
                            LaunchedEffect(s) { screen = s.back }
                        }
                    }
                    is Screen.Preview -> {
                        val context = LocalContext.current
                        val record = store.month(s.key)
                        val image = remember(s, record) {
                            record?.let { r ->
                                val result = r.calculate()
                                val isOutbuilding = s.house == House.OUTBUILDING
                                val tenant = store.tenantFor(s.house, r.key)?.name
                                    ?: (if (isOutbuilding) r.outbuildingTenant else r.forebuildingTenant)
                                StatementImage.create(
                                    context = context,
                                    house = s.house,
                                    tenantName = tenant,
                                    monthLabel = r.label,
                                    monthKey = r.key,
                                    statement = if (isOutbuilding) result.outbuilding else result.forebuilding,
                                    settings = store.settings,
                                )
                            }
                        }
                        if (image != null) {
                            AttachmentViewer(
                                title = "${s.house.label} statement",
                                file = image,
                                mime = "image/png",
                                onClose = { screen = Screen.Review(s.key) },
                                onShare = { ShareSheet.shareImage(context, image, "${s.house.label} statement - ${record?.label ?: ""}") },
                            )
                        } else {
                            LaunchedEffect(s) { screen = Screen.Review(s.key) }
                        }
                    }
                }
            }
        }
    }
}
