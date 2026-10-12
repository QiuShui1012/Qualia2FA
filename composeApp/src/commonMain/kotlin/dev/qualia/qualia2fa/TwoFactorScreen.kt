package dev.qualia.qualia2fa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.action_cancel
import dev.qualia.qualia2fa.resources.action_copy
import dev.qualia.qualia2fa.resources.action_delete
import dev.qualia.qualia2fa.resources.action_delete_selected
import dev.qualia.qualia2fa.resources.action_done
import dev.qualia.qualia2fa.resources.action_edit
import dev.qualia.qualia2fa.resources.action_generate_secret
import dev.qualia.qualia2fa.resources.action_import
import dev.qualia.qualia2fa.resources.action_save
import dev.qualia.qualia2fa.resources.cd_add_entry
import dev.qualia.qualia2fa.resources.cd_enter_selection
import dev.qualia.qualia2fa.resources.cd_selection_all
import dev.qualia.qualia2fa.resources.cd_selection_none
import dev.qualia.qualia2fa.resources.code_changes_in
import dev.qualia.qualia2fa.resources.default_entry_name
import dev.qualia.qualia2fa.resources.default_imported_entry_name
import dev.qualia.qualia2fa.resources.dialog_add_entry
import dev.qualia.qualia2fa.resources.dialog_delete_selected_text
import dev.qualia.qualia2fa.resources.dialog_delete_selected_title
import dev.qualia.qualia2fa.resources.dialog_edit_entry
import dev.qualia.qualia2fa.resources.dialog_import_link
import dev.qualia.qualia2fa.resources.dialog_pick_image
import dev.qualia.qualia2fa.resources.error_entry_not_saved
import dev.qualia.qualia2fa.resources.error_entry_rejected
import dev.qualia.qualia2fa.resources.error_image_has_no_entry
import dev.qualia.qualia2fa.resources.error_image_unreadable
import dev.qualia.qualia2fa.resources.error_link_rejected
import dev.qualia.qualia2fa.resources.field_account
import dev.qualia.qualia2fa.resources.field_digits
import dev.qualia.qualia2fa.resources.field_issuer
import dev.qualia.qualia2fa.resources.field_name
import dev.qualia.qualia2fa.resources.field_otpauth_link
import dev.qualia.qualia2fa.resources.field_period
import dev.qualia.qualia2fa.resources.field_secret
import dev.qualia.qualia2fa.resources.filter_image_files
import dev.qualia.qualia2fa.resources.hint_link_prefilled
import dev.qualia.qualia2fa.resources.icon_add
import dev.qualia.qualia2fa.resources.icon_select
import dev.qualia.qualia2fa.resources.icon_select_all
import dev.qualia.qualia2fa.resources.icon_selected
import dev.qualia.qualia2fa.resources.menu_enter_manually
import dev.qualia.qualia2fa.resources.menu_import_from_link
import dev.qualia.qualia2fa.resources.menu_scan_code
import dev.qualia.qualia2fa.resources.message_copied_and_cleared
import dev.qualia.qualia2fa.resources.message_entries_deleted
import dev.qualia.qualia2fa.resources.message_entry_imported
import dev.qualia.qualia2fa.resources.nav_two_factor
import dev.qualia.qualia2fa.resources.selection_count
import dev.qualia.qualia2fa.resources.two_factor_empty
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The entries, their codes, and the two ways to add one.
 *
 * The header is the design's: the screen's name on the left, and on the right the way into
 * selection mode and the way to add something. Adding opens a menu with the two options
 * there are — a QR code the user scans from an image, or a secret typed by hand — rather than
 * two buttons that would both claim the same corner.
 *
 * # Selection mode
 *
 * The design provides the icons for it (`select`, `select_all`, `select_check_box`) without
 * describing what the selection is for; deleting several entries at once is what it is for
 * here. Deleting is the one action in this application that cannot be undone — the tombstone
 * propagates to every device the account syncs with — so it asks first, and says so.
 */
@Composable
fun TwoFactorScreen(
    vault: Vault,
    onPersist: () -> Unit,
) {
    var entries by remember { mutableStateOf(vault.entries()) }
    var now by remember { mutableStateOf(currentTimeSeconds()) }
    var message by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var importingLink by remember { mutableStateOf(false) }
    var linkText by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Entry?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var deletedCount by remember { mutableStateOf<Int?>(null) }
    val clipboard = LocalClipboardManager.current

    val unreadableImage = stringResource(Res.string.error_image_unreadable)
    val noEntryCode = stringResource(Res.string.error_image_has_no_entry)
    val importedEntryName = stringResource(Res.string.default_imported_entry_name)
    val entryRejected = stringResource(Res.string.error_entry_rejected)
    val entryImported = stringResource(Res.string.message_entry_imported)
    val entryNotSaved = stringResource(Res.string.error_entry_not_saved)
    val linkRejected = stringResource(Res.string.error_link_rejected)
    val unnamed = stringResource(Res.string.default_entry_name)
    val copiedAndCleared = stringResource(Res.string.message_copied_and_cleared)
    val pickImageTitle = stringResource(Res.string.dialog_pick_image)
    val imageFilesLabel = stringResource(Res.string.filter_image_files)

    /** Re-reads the entries after anything wrote to the vault. */
    fun refresh() {
        entries = vault.entries()
        onPersist()
    }

    /**
     * Adds the entry an `otpauth://` link describes, and says what happened.
     *
     * One function for both ways in — out of an image and off the clipboard — because they
     * differ only in how the text is obtained, and two copies of the entry-building rules
     * would be two places for them to drift apart.
     */
    fun addFrom(text: String) {
        val link = Qualia.parseOtpAuth(text)
        if (link == null) {
            message = linkRejected
            return
        }

        val added =
            vault.addEntry(
                name = link.issuer.ifBlank { link.account }.ifBlank { importedEntryName },
                issuer = link.issuer,
                account = link.account,
                secretBase32 = link.secretBase32,
                digits = link.digits,
                periodSeconds = link.periodSeconds,
                note = "",
                unixTime = currentTimeSeconds(),
            )
        if (added == null) {
            message = entryRejected
        } else {
            refresh()
            message = entryImported
        }
    }

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
        modifier = Modifier.fillMaxSize().padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        if (selecting) {
            ScreenHeader(title = stringResource(Res.string.selection_count, selected.size)) {
                val allSelected = entries.isNotEmpty() && selected.size == entries.size
                IconAction(
                    painter = painterResource(Res.drawable.icon_select_all),
                    description =
                        stringResource(
                            if (allSelected) Res.string.cd_selection_none else Res.string.cd_selection_all,
                        ),
                ) {
                    selected = if (allSelected) emptySet() else entries.map { it.id }.toSet()
                }
                TextButton(onClick = { confirmingDelete = true }, enabled = selected.isNotEmpty()) {
                    Text(stringResource(Res.string.action_delete_selected))
                }
                TextButton(onClick = {
                    selecting = false
                    selected = emptySet()
                    message = null
                }) {
                    Text(stringResource(Res.string.action_done))
                }
            }
        } else {
            ScreenHeader(title = stringResource(Res.string.nav_two_factor)) {
                IconAction(
                    painter = painterResource(Res.drawable.icon_select),
                    description = stringResource(Res.string.cd_enter_selection),
                ) {
                    selecting = true
                    selected = emptySet()
                }
                Box {
                    IconAction(
                        painter = painterResource(Res.drawable.icon_add),
                        description = stringResource(Res.string.cd_add_entry),
                    ) { menuOpen = true }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.menu_scan_code)) },
                            onClick = {
                                menuOpen = false
                                val image = pickGrayImage(pickImageTitle, imageFilesLabel)
                                if (image == null) {
                                    message = unreadableImage
                                } else {
                                    val text = Qualia.qrTextFromImage(image.pixels, image.width, image.height)
                                    if (text == null) message = noEntryCode else addFrom(text)
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.menu_import_from_link)) },
                            onClick = {
                                menuOpen = false
                                linkText = clipboard.getText()?.text?.trim().orEmpty()
                                importingLink = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.menu_enter_manually)) },
                            onClick = {
                                menuOpen = false
                                adding = true
                            },
                        )
                    }
                }
            }
        }

        MessageLine(message)
        deletedCount?.let { count ->
            // Resolved here rather than where the deletion happened: a callback cannot read a
            // resource, and a stored string would keep the language it was built in.
            MessageLine(stringResource(Res.string.message_entries_deleted, count), isError = false)
        }

        if (entries.isEmpty()) {
            Hint(stringResource(Res.string.two_factor_empty))
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            items(entries, key = { it.id }) { entry ->
                EntryCard(
                    entry = entry,
                    code = vault.code(entry.id, now),
                    now = now,
                    selected = if (selecting) entry.id in selected else null,
                    onToggleSelection = {
                        selected = if (entry.id in selected) selected - entry.id else selected + entry.id
                    },
                    onCopy = { code ->
                        clipboard.setText(AnnotatedString(code))
                        copied = code
                        message = copiedAndCleared
                    },
                    onDelete = {
                        vault.removeEntry(entry.id, currentTimeSeconds())
                        refresh()
                    },
                    onEdit = { editing = entry },
                )
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(Res.string.dialog_delete_selected_title, selected.size)) },
            text = { Text(stringResource(Res.string.dialog_delete_selected_text)) },
            confirmButton = {
                Button(onClick = {
                    val removed = selected.size
                    selected.forEach { id -> vault.removeEntry(id, currentTimeSeconds()) }
                    confirmingDelete = false
                    selecting = false
                    selected = emptySet()
                    refresh()
                    deletedCount = removed
                }) {
                    Text(stringResource(Res.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }

    if (importingLink) {
        LinkDialog(
            initial = linkText,
            onDismiss = {
                importingLink = false
                linkText = ""
            },
            onImport = { text ->
                importingLink = false
                linkText = ""
                addFrom(text)
            },
        )
    }

    if (adding || editing != null) {
        val beingEdited = editing
        EntryDialog(
            initial = beingEdited,
            defaultName = unnamed,
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
                    refresh()
                    message = null
                }
            },
        )
    }
}

/**
 * One entry.
 *
 * [selected] is `null` outside selection mode: the same card then carries the code and the
 * per-entry actions, and in selection mode it carries a box and toggles instead — one row
 * that cannot be in two modes at once, which the type says.
 */
@Composable
private fun EntryCard(
    entry: Entry,
    code: String?,
    now: Long,
    selected: Boolean?,
    onToggleSelection: () -> Unit,
    onCopy: (String) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    val period = entry.periodSeconds.coerceAtLeast(1)
    val remaining = period - (now % period)

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (selected == null) {
                        Modifier
                    } else {
                        // A checkbox rather than a clickable row: a screen reader then says
                        // whether the entry is selected instead of only that it can be tapped.
                        Modifier.toggleable(
                            value = selected,
                            role = Role.Checkbox,
                            onValueChange = { onToggleSelection() },
                        )
                    },
                ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Space.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            if (selected != null) {
                Icon(
                    painter =
                        painterResource(
                            if (selected) Res.drawable.icon_selected else Res.drawable.icon_select,
                        ),
                    contentDescription = null,
                    modifier = Modifier.size(Sizes.navIcon),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }

            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                val subtitle =
                    listOfNotNull(
                        entry.issuer.takeIf { it.isNotBlank() },
                        entry.account.takeIf { it.isNotBlank() },
                    )
                if (subtitle.isNotEmpty()) {
                    Text(subtitle.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                if (selected == null) {
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
            }

            if (selected == null) {
                if (code != null && !entry.isCounterBased) {
                    CircularProgressIndicator(
                        progress = { remaining.toFloat() / period.toFloat() },
                        modifier = Modifier.size(Sizes.ring),
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
}

/**
 * The way in for a link: an `otpauth://` URI pasted from wherever the user got it.
 *
 * The field starts out holding the clipboard when the clipboard holds a link, because that is
 * where a link arrives from — a web page, a chat message, another application. Nothing is
 * taken out of the clipboard behind the user's back: the text lands in the field, and
 * importing it is a button press.
 */
@Composable
private fun LinkDialog(
    initial: String,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.dialog_import_link)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(Res.string.field_otpauth_link)) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint(stringResource(Res.string.hint_link_prefilled))
            }
        },
        confirmButton = {
            Button(onClick = { onImport(text.trim()) }, enabled = text.isNotBlank()) {
                Text(stringResource(Res.string.action_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

/** The editor, for a new entry and for an existing one. */
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
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
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
