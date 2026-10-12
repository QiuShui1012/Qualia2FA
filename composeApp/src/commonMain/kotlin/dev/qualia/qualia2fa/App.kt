package dev.qualia.qualia2fa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.action_add_entry
import dev.qualia.qualia2fa.resources.action_cancel
import dev.qualia.qualia2fa.resources.action_copy
import dev.qualia.qualia2fa.resources.action_create_vault
import dev.qualia.qualia2fa.resources.action_delete
import dev.qualia.qualia2fa.resources.action_edit
import dev.qualia.qualia2fa.resources.action_generate_secret
import dev.qualia.qualia2fa.resources.action_import_entry_from_image
import dev.qualia.qualia2fa.resources.action_join_existing_account
import dev.qualia.qualia2fa.resources.action_lock
import dev.qualia.qualia2fa.resources.action_pair_new_device
import dev.qualia.qualia2fa.resources.action_save
import dev.qualia.qualia2fa.resources.action_sync
import dev.qualia.qualia2fa.resources.app_name
import dev.qualia.qualia2fa.resources.code_changes_in
import dev.qualia.qualia2fa.resources.core_version_file_key
import dev.qualia.qualia2fa.resources.core_version_hardware_key
import dev.qualia.qualia2fa.resources.core_version_in_status
import dev.qualia.qualia2fa.resources.default_entry_name
import dev.qualia.qualia2fa.resources.default_imported_entry_name
import dev.qualia.qualia2fa.resources.dialog_add_entry
import dev.qualia.qualia2fa.resources.dialog_edit_entry
import dev.qualia.qualia2fa.resources.dialog_pick_image
import dev.qualia.qualia2fa.resources.entry_count
import dev.qualia.qualia2fa.resources.error_entry_not_saved
import dev.qualia.qualia2fa.resources.error_entry_rejected
import dev.qualia.qualia2fa.resources.error_image_has_no_entry
import dev.qualia.qualia2fa.resources.error_image_unreadable
import dev.qualia.qualia2fa.resources.error_no_platform_key_store
import dev.qualia.qualia2fa.resources.error_serialize_failed
import dev.qualia.qualia2fa.resources.field_account
import dev.qualia.qualia2fa.resources.field_digits
import dev.qualia.qualia2fa.resources.field_issuer
import dev.qualia.qualia2fa.resources.field_name
import dev.qualia.qualia2fa.resources.field_period
import dev.qualia.qualia2fa.resources.field_secret
import dev.qualia.qualia2fa.resources.filter_image_files
import dev.qualia.qualia2fa.resources.first_run_intro
import dev.qualia.qualia2fa.resources.first_run_join_explainer
import dev.qualia.qualia2fa.resources.first_run_no_hardware_warning
import dev.qualia.qualia2fa.resources.message_copied_and_cleared
import dev.qualia.qualia2fa.resources.message_entry_imported
import dev.qualia.qualia2fa.resources.settings_language
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The application window.
 *
 * It shows either the first-run choice or the entry list, depending on whether this
 * device already holds a master key and a vault.
 *
 * [blurCount] increases every time the window loses focus; any increase locks the vault,
 * because a two-factor code left on screen is a code somebody else can read. The vault is
 * also locked after [SESSION_TIMEOUT_SECONDS] regardless of activity, and locking drops
 * the only reference to the key material, which is wiped by the Rust side when it is
 * dropped.
 *
 * # The language wraps the screens, not the vault
 *
 * The vault state lives **outside** the key that the language change recreates: switching
 * the language must not lock the vault, re-read the master key from the platform key
 * store, or restart the inactivity timeout. Everything below the key is rebuilt, so the
 * transient messages of a screen are dropped as well — they were resolved in the language
 * that is no longer in effect.
 */
@Composable
fun App(storage: VaultStorage, settings: SettingsStore, blurCount: Int = 0) {
    var language by remember { mutableStateOf(settings.language()) }
    var vault by remember { mutableStateOf<Vault?>(null) }
    var status by remember { mutableStateOf<Status>(Status.OpeningVault) }
    val clipboard = LocalClipboardManager.current

    fun lock(reason: Status) {
        vault = null
        status = reason
        clipboard.setText(AnnotatedString(""))
    }

    val selectLanguage: (AppLanguage) -> Unit = { chosen ->
        language = chosen
        settings.setLanguage(chosen)
    }

    LaunchedEffect(Unit) {
        val store = storage.masterKeyStore
        val data = storage.loadVault()
        vault =
            when {
                data == null -> null
                !Qualia.hasMasterKey(store) -> null
                else -> Qualia.openVault(data, store)
            }
        if (vault != null) {
            status = Status.Unlocked
        }
    }

    LaunchedEffect(blurCount) {
        if (blurCount > 0 && vault != null) {
            lock(Status.LockedOnBlur)
        }
    }

    LaunchedEffect(vault) {
        if (vault == null) return@LaunchedEffect
        delay(SESSION_TIMEOUT_SECONDS * 1_000L)
        lock(Status.LockedAfterTimeout(SESSION_TIMEOUT_SECONDS))
    }

    CompositionLocalProvider(LocalAppLocale provides language.tag) {
        key(language) {
            MaterialTheme {
                val current = vault
                if (current == null) {
                    FirstRunScreen(
                        storage = storage,
                        status = status,
                        language = language,
                        onSelectLanguage = selectLanguage,
                        onCreated = { created, detail ->
                            vault = created
                            status = detail
                        },
                    )
                } else {
                    VaultScreen(
                        vault = current,
                        storage = storage,
                        language = language,
                        onSelectLanguage = selectLanguage,
                        onLock = { lock(Status.Locked) },
                    )
                }
            }
        }
    }
}

/** How long an unlocked vault stays unlocked with no way for the user to extend it. */
private const val SESSION_TIMEOUT_SECONDS = 900

/**
 * The label of this device's identity key pair in the platform key store.
 *
 * One label names one key pair for the lifetime of the device, so it is a constant rather
 * than something derived from user input.
 */
internal const val DEVICE_LABEL = "qualia2fa-device"

/**
 * The language selector: the current choice, and a menu with every language.
 *
 * The names are endonyms (see [AppLanguage]), so a user who cannot read the language in
 * effect can still find their own in the list.
 */
@Composable
internal fun LanguageSelector(language: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("${stringResource(Res.string.settings_language)}: ${language.label()}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AppLanguage.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label()) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun FirstRunScreen(
    storage: VaultStorage,
    status: Status,
    language: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit,
    onCreated: (Vault, Status) -> Unit,
) {
    val store = storage.masterKeyStore
    var message by remember { mutableStateOf<String?>(null) }
    var joining by remember { mutableStateOf(false) }

    if (joining) {
        JoinAccountScreen(
            storage = storage,
            onJoined = { onCreated(it, Status.JoinedThroughPairing) },
            onCancel = { joining = false },
        )
        return
    }

    val serializeFailed = stringResource(Res.string.error_serialize_failed)
    val noKeyStore = stringResource(Res.string.error_no_platform_key_store)

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(Res.string.core_version_in_status, Qualia.coreVersion(), status.text()),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.width(4.dp))

        Text(stringResource(Res.string.first_run_intro), style = MaterialTheme.typography.bodyMedium)

        if (!store.isHardwareBacked) {
            Text(
                stringResource(Res.string.first_run_no_hardware_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(onClick = {
            val registration = Qualia.register(store, DEVICE_LABEL)
            if (registration != null) {
                val data = registration.vault.serialize()
                if (data == null) {
                    message = serializeFailed
                } else {
                    storage.saveVault(data)
                    onCreated(
                        registration.vault,
                        Status.CreatedDeviceIdentity(registration.backend, registration.hardwareBacked),
                    )
                }
            } else {
                // The platform key store is what holds the device identity, and without it
                // this device cannot pair or sync. Creating a vault anyway is still useful,
                // but it is a different thing and the user is told so.
                val created = Qualia.createLocalVault(store)
                val data = created?.serialize()
                if (created == null || data == null) {
                    message = noKeyStore
                } else {
                    storage.saveVault(data)
                    onCreated(created, Status.LocalVaultOnly)
                }
            }
        }) {
            Text(stringResource(Res.string.action_create_vault))
        }

        OutlinedButton(onClick = { joining = true }) {
            Text(stringResource(Res.string.action_join_existing_account))
        }

        Text(
            stringResource(Res.string.first_run_join_explainer),
            style = MaterialTheme.typography.bodySmall,
        )

        LanguageSelector(language = language, onSelect = onSelectLanguage)

        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun VaultScreen(
    vault: Vault,
    storage: VaultStorage,
    language: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit,
    onLock: () -> Unit,
) {
    var now by remember { mutableStateOf(currentTimeSeconds()) }
    var entries by remember { mutableStateOf(vault.entries()) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Entry?>(null) }
    var pairing by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf<String?>(null) }

    val unreadableImage = stringResource(Res.string.error_image_unreadable)
    val noEntryCode = stringResource(Res.string.error_image_has_no_entry)
    val importedEntryName = stringResource(Res.string.default_imported_entry_name)
    val entryRejected = stringResource(Res.string.error_entry_rejected)
    val entryImported = stringResource(Res.string.message_entry_imported)
    val entryNotSaved = stringResource(Res.string.error_entry_not_saved)
    val unnamed = stringResource(Res.string.default_entry_name)
    val copiedAndCleared = stringResource(Res.string.message_copied_and_cleared)
    val pickImageTitle = stringResource(Res.string.dialog_pick_image)
    val imageFilesLabel = stringResource(Res.string.filter_image_files)

    LaunchedEffect(Unit) {
        while (true) {
            now = currentTimeSeconds()
            delay(250)
        }
    }

    LaunchedEffect(copied) {
        val value = copied ?: return@LaunchedEffect
        delay(30_000)
        if (clipboard.getText()?.text == value) {
            clipboard.setText(AnnotatedString(""))
        }
        copied = null
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(12.dp))
            Text(
                pluralStringResource(Res.plurals.entry_count, entries.size, entries.size),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.weight(1f))
            LanguageSelector(language = language, onSelect = onSelectLanguage)
            TextButton(onClick = onLock) { Text(stringResource(Res.string.action_lock)) }
        }

        Text(
            stringResource(
                if (storage.masterKeyStore.isHardwareBacked) {
                    Res.string.core_version_hardware_key
                } else {
                    Res.string.core_version_file_key
                },
                Qualia.coreVersion(),
            ),
            style = MaterialTheme.typography.bodySmall,
        )

        TextButton(onClick = { adding = true }) { Text(stringResource(Res.string.action_add_entry)) }
        TextButton(onClick = {
            val image = pickGrayImage(pickImageTitle, imageFilesLabel)
            if (image == null) {
                message = unreadableImage
            } else {
                val text = Qualia.qrTextFromImage(image.pixels, image.width, image.height)
                val link = text?.let { Qualia.parseOtpAuth(it) }
                if (link == null) {
                    message = noEntryCode
                } else {
                    val id = vault.addEntry(
                        name = link.issuer.ifBlank { link.account }.ifBlank { importedEntryName },
                        issuer = link.issuer,
                        account = link.account,
                        secretBase32 = link.secretBase32,
                        digits = link.digits,
                        periodSeconds = link.periodSeconds,
                        note = "",
                        unixTime = currentTimeSeconds(),
                    )
                    if (id == null) {
                        message = entryRejected
                    } else {
                        vault.serialize()?.let(storage::saveVault)
                        entries = vault.entries()
                        message = entryImported
                    }
                }
            }
        }) { Text(stringResource(Res.string.action_import_entry_from_image)) }
        TextButton(onClick = { pairing = true }) { Text(stringResource(Res.string.action_pair_new_device)) }
        TextButton(onClick = { syncing = true }) { Text(stringResource(Res.string.action_sync)) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { it.id }) { entry ->
                EntryCard(
                    entry = entry,
                    code = vault.code(entry.id, now),
                    now = now,
                    onCopy = { code ->
                        clipboard.setText(AnnotatedString(code))
                        copied = code
                        message = copiedAndCleared
                    },
                    onDelete = {
                        vault.removeEntry(entry.id, currentTimeSeconds())
                        vault.serialize()?.let(storage::saveVault)
                        entries = vault.entries()
                    },
                    onEdit = { editing = entry },
                )
            }
        }
    }

    if (pairing) {
        PairNewDeviceScreen(vault = vault, onDone = { pairing = false })
    }

    if (syncing) {
        // The identity is looked up when the screen is opened, not when the list is composed:
        // it is a platform key store access, and a hardware one on machines that have it.
        val deviceId = remember { Qualia.deviceId(DEVICE_LABEL) }
        SyncScreen(
            vault = vault,
            deviceId = deviceId,
            onChanged = {
                // A merge and a decision both write into the vault, and neither can persist
                // it: the file belongs to this layer.
                vault.serialize()?.let(storage::saveVault)
                entries = vault.entries()
            },
            onDone = { syncing = false },
        )
    }

    if (adding || editing != null) {
        val beingEdited = editing
        EntryDialog(
            initial = beingEdited,
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = { name, issuer, account, secret, digits, period ->
                val accepted =
                    if (beingEdited == null) {
                        vault.addEntry(
                            name = name,
                            issuer = issuer,
                            account = account,
                            secretBase32 = secret,
                            digits = digits,
                            periodSeconds = period,
                            note = "",
                            unixTime = currentTimeSeconds(),
                        ) != null
                    } else {
                        vault.updateEntry(
                            id = beingEdited.id,
                            name = name,
                            issuer = issuer,
                            account = account,
                            secretBase32 = secret,
                            digits = digits,
                            periodSeconds = period,
                            note = beingEdited.note,
                            unixTime = currentTimeSeconds(),
                        )
                    }

                adding = false
                editing = null
                if (!accepted) {
                    message = entryNotSaved
                } else {
                    vault.serialize()?.let(storage::saveVault)
                    entries = vault.entries()
                    message = null
                }
            },
            defaultName = unnamed,
        )
    }
}

@Composable
private fun EntryCard(
    entry: Entry,
    code: String?,
    now: Long,
    onCopy: (String) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    val period = entry.periodSeconds.coerceAtLeast(1)
    val remaining = period - (now % period)

    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                val subtitle = listOfNotNull(entry.issuer.takeIf { it.isNotBlank() }, entry.account.takeIf { it.isNotBlank() })
                if (subtitle.isNotEmpty()) {
                    Text(subtitle.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    text = code?.chunked(3)?.joinToString(" ") ?: "------",
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                )
                if (code != null && !entry.isCounterBased) {
                    Text(
                        stringResource(Res.string.code_changes_in, remaining),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (code != null && !entry.isCounterBased) {
                CircularProgressIndicator(
                    progress = { remaining.toFloat() / period.toFloat() },
                    modifier = Modifier.width(36.dp),
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { code?.let(onCopy) }, enabled = code != null) {
                    Text(stringResource(Res.string.action_copy))
                }
                TextButton(onClick = onEdit) { Text(stringResource(Res.string.action_edit)) }
                TextButton(onClick = onDelete) { Text(stringResource(Res.string.action_delete)) }
            }
        }
    }
}

@Composable
private fun EntryDialog(
    initial: Entry?,
    defaultName: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, Int, Long) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var issuer by remember { mutableStateOf(initial?.issuer.orEmpty()) }
    var account by remember { mutableStateOf(initial?.account.orEmpty()) }
    var secret by remember { mutableStateOf(initial?.secretBase32.orEmpty()) }
    var digits by remember { mutableStateOf((initial?.digits ?: 6).toString()) }
    var period by remember { mutableStateOf((initial?.periodSeconds ?: 30L).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initial == null) Res.string.dialog_add_entry else Res.string.dialog_edit_entry))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.field_name)) },
                )
                OutlinedTextField(
                    value = issuer,
                    onValueChange = { issuer = it },
                    label = { Text(stringResource(Res.string.field_issuer)) },
                )
                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it },
                    label = { Text(stringResource(Res.string.field_account)) },
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text(stringResource(Res.string.field_secret)) },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = digits,
                        onValueChange = { digits = it },
                        label = { Text(stringResource(Res.string.field_digits)) },
                        singleLine = true,
                        modifier = Modifier.width(110.dp),
                    )
                    OutlinedTextField(
                        value = period,
                        onValueChange = { period = it },
                        label = { Text(stringResource(Res.string.field_period)) },
                        singleLine = true,
                        modifier = Modifier.width(140.dp),
                    )
                }
                TextButton(onClick = { secret = Qualia.newSecret() }) {
                    Text(stringResource(Res.string.action_generate_secret))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        name.ifBlank { defaultName },
                        issuer,
                        account,
                        secret.trim().uppercase(),
                        digits.toIntOrNull() ?: 6,
                        period.toLongOrNull() ?: 30L,
                    )
                },
                enabled = secret.isNotBlank(),
            ) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}
