package dev.qualia.qualia2fa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.action_cancel
import dev.qualia.qualia2fa.resources.action_copy
import dev.qualia.qualia2fa.resources.action_delete
import dev.qualia.qualia2fa.resources.action_delete_selected
import dev.qualia.qualia2fa.resources.action_done
import dev.qualia.qualia2fa.resources.action_edit
import dev.qualia.qualia2fa.resources.action_generate_secret
import dev.qualia.qualia2fa.resources.action_import
import dev.qualia.qualia2fa.resources.action_next_code
import dev.qualia.qualia2fa.resources.action_save
import dev.qualia.qualia2fa.resources.cd_add_entry
import dev.qualia.qualia2fa.resources.cd_enter_selection
import dev.qualia.qualia2fa.resources.cd_selection_all
import dev.qualia.qualia2fa.resources.cd_selection_none
import dev.qualia.qualia2fa.resources.code_changes_in
import dev.qualia.qualia2fa.resources.code_counter
import dev.qualia.qualia2fa.resources.default_entry_name
import dev.qualia.qualia2fa.resources.default_imported_entry_name
import dev.qualia.qualia2fa.resources.dialog_add_entry
import dev.qualia.qualia2fa.resources.dialog_delete_entry_text
import dev.qualia.qualia2fa.resources.dialog_delete_entry_title
import dev.qualia.qualia2fa.resources.dialog_delete_selected_text
import dev.qualia.qualia2fa.resources.dialog_delete_selected_title
import dev.qualia.qualia2fa.resources.dialog_edit_entry
import dev.qualia.qualia2fa.resources.dialog_import_link
import dev.qualia.qualia2fa.resources.entry_type_hotp
import dev.qualia.qualia2fa.resources.entry_type_totp
import dev.qualia.qualia2fa.resources.error_entry_not_saved
import dev.qualia.qualia2fa.resources.error_entry_rejected
import dev.qualia.qualia2fa.resources.error_image_has_no_entry
import dev.qualia.qualia2fa.resources.error_image_unreadable
import dev.qualia.qualia2fa.resources.error_link_rejected
import dev.qualia.qualia2fa.resources.field_account
import dev.qualia.qualia2fa.resources.field_algorithm
import dev.qualia.qualia2fa.resources.field_counter
import dev.qualia.qualia2fa.resources.field_digits
import dev.qualia.qualia2fa.resources.field_entry_type
import dev.qualia.qualia2fa.resources.field_issuer
import dev.qualia.qualia2fa.resources.field_name
import dev.qualia.qualia2fa.resources.field_otpauth_link
import dev.qualia.qualia2fa.resources.field_period
import dev.qualia.qualia2fa.resources.field_secret
import dev.qualia.qualia2fa.resources.field_secret_replace
import dev.qualia.qualia2fa.resources.hint_link_prefilled
import dev.qualia.qualia2fa.resources.hint_secret_kept
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
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
    // The second the codes and the countdowns are read at, as a state rather than as a value: a
    // value handed to a card is one of the card's parameters, so a second passing would compose
    // every card in the list again -- ten of them, for a number each countdown reads for itself.
    val clock = remember { mutableLongStateOf(currentTimeSeconds()) }
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
    var deletingEntry by remember { mutableStateOf<Entry?>(null) }
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
    val scanner = LocalCodeScanner.current

    /** Re-reads the entries after anything wrote to the vault. */
    fun refresh() {
        entries = vault.entries()
        onPersist()
    }

    /**
     * Adds the entry an `otpauth://` link describes, and says what happened.
     *
     * One function for both ways in — out of an image and off the clipboard — because they
     * differ only in how the text is obtained. Two copies of the entry-building rules would
     * be two places for them to drift apart.
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
                // The link's own algorithm, not this application's default: an SHA-256 link
                // imported as SHA-1 would produce codes that look right and never work.
                algorithm = link.algorithm,
                digits = link.digits,
                periodSeconds = link.periodSeconds,
                // The link's kind as well: a counter here is what makes the new entry
                // counter-based, and importing `otpauth://hotp/` without it would produce
                // time-based codes for an entry whose server counts.
                counter = link.counter,
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
            val millis = currentTimeMillis()
            clock.longValue = millis / 1000
            // Until the second changes, not for a second: a fixed delay from the last wake drifts,
            // and a countdown that drifts holds one number for longer than a second and then skips
            // the next one.
            delay((1000 - millis % 1000).milliseconds)
        }
    }

    LaunchedEffect(copied) {
        val value = copied ?: return@LaunchedEffect
        delay(30.seconds)
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
                                scanner.readCode(
                                    unreadable = unreadableImage,
                                    noCode = noEntryCode,
                                    onText = ::addFrom,
                                    onMessage = { message = it },
                                )
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

        LazyVerticalGrid(
            // As many cards of at least this width as the window holds: the number of columns
            // follows the window rather than being fixed, and a card in a partly filled last
            // row keeps the width of the ones above it.
            columns = GridCells.Adaptive(minSize = MinEntryCardWidth),
            verticalArrangement = Arrangement.spacedBy(Space.s),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            items(entries, key = { it.id }) { entry ->
                // Read once a second, and the code asked of the library only when its step turns
                // over: a code is the same value for the whole of its step, and asking for one on
                // every composition is a crossing into the library per card per frame. A
                // counter-based entry has no step, so what changes it is the counter itself.
                val at = clock.value
                EntryCard(
                    entry = entry,
                    code = remember(entry.id, entry.counter, at / entry.periodSeconds.coerceAtLeast(1)) {
                        vault.codeFor(entry, at)
                    },
                    clock = clock,
                    selected = if (selecting) entry.id in selected else null,
                    onToggleSelection = {
                        selected = if (entry.id in selected) selected - entry.id else selected + entry.id
                    },
                    onCopy = { code ->
                        clipboard.setText(AnnotatedString(code))
                        copied = code
                        message = copiedAndCleared
                    },
                    onAdvance = {
                        if (vault.advanceCounter(entry.id, currentTimeSeconds())) {
                            refresh()
                            message = null
                        } else {
                            message = entryNotSaved
                        }
                    },
                    onDelete = { deletingEntry = entry },
                    onEdit = { editing = entry },
                )
            }
        }
    }

    // One entry asks for the same confirmation the selection does, and for the same reason:
    // the deletion is not local. A tombstone travels to every device the account is on, so the
    // entry disappears from all of them and nothing brings the codes back.
    deletingEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { deletingEntry = null },
            properties = DialogDefaults,
            title = { Text(stringResource(Res.string.dialog_delete_entry_title, entry.name)) },
            text = { Text(stringResource(Res.string.dialog_delete_entry_text)) },
            confirmButton = {
                Button(onClick = {
                    vault.removeEntry(entry.id, currentTimeSeconds())
                    deletingEntry = null
                    refresh()
                    deletedCount = 1
                }) {
                    Text(stringResource(Res.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingEntry = null }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            properties = DialogDefaults,
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
            onSave = { form ->
                val accepted =
                    if (beingEdited == null) {
                        vault.addEntry(
                            name = form.name,
                            issuer = form.issuer,
                            account = form.account,
                            secretBase32 = form.secretBase32,
                            algorithm = form.algorithm,
                            digits = form.digits,
                            periodSeconds = form.periodSeconds,
                            counter = form.counter,
                            note = "",
                            unixTime = currentTimeSeconds(),
                        ) != null
                    } else {
                        vault.updateEntry(
                            id = beingEdited.id,
                            name = form.name,
                            issuer = form.issuer,
                            account = form.account,
                            // An empty field means "leave the secret as it is": the dialog is
                            // never given the current one, so it cannot hand it back.
                            secretBase32 = form.secretBase32.ifBlank { beingEdited.secretBase32 },
                            algorithm = form.algorithm,
                            digits = form.digits,
                            periodSeconds = form.periodSeconds,
                            counter = form.counter,
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
 * per-entry actions, and in selection mode it carries a box and toggles instead — one card
 * that cannot be in two modes at once, which the type says.
 *
 * # What the card shows
 *
 * Three lines, each with one thing to read on the left and the action that belongs to it on
 * the right: the name with its service beside it and **copy**, the code and **edit**, and when
 * the code changes next with the countdown ring and **delete**. The destructive action ends up
 * on the bottom line, where it belongs, and the ring sits immediately to its left. What is
 * counting down and what would destroy it are then read together, rather than the ring
 * floating beside the code.
 *
 * The account and the algorithm are deliberately **not** shown: they are what the editor is
 * for, and on a card that is one column of several they would cost a line each for something
 * the user reads once.
 *
 * # The two kinds of entry
 *
 * A time-based entry changes by itself, so its bottom line counts the step down. A
 * counter-based one changes when the server consumes a code, so the same line shows the counter
 * the code belongs to and offers **next code**, which is the action that moves it. Neither
 * happens on the other kind: a counter on a card that also counted down would be two claims
 * about when the code changes.
 */
@Composable
private fun EntryCard(
    entry: Entry,
    code: String?,
    clock: State<Long>,
    selected: Boolean?,
    onToggleSelection: () -> Unit,
    onCopy: (String) -> Unit,
    onAdvance: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    val period = entry.periodSeconds.coerceAtLeast(1)
    val service = entry.issuer.takeIf { it.isNotBlank() }
    val counter = entry.counter
    val changing = code != null && counter == null

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (selected == null) {
                        Modifier
                    } else {
                        // A checkbox rather than a clickable card: a screen reader then says
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
        Column(
            modifier = Modifier.fillMaxWidth().padding(Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
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

                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.alignByBaseline())
                    if (service != null) {
                        Text("·", style = MaterialTheme.typography.bodySmall, modifier = Modifier.alignByBaseline())
                        Text(service, style = MaterialTheme.typography.bodySmall, modifier = Modifier.alignByBaseline())
                    }
                }

                if (selected == null) {
                    CompactTextButton(
                        label = stringResource(Res.string.action_copy),
                        enabled = code != null,
                        onClick = { code?.let(onCopy) },
                    )
                }
            }

            if (selected == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = code?.chunked(3)?.joinToString(" ") ?: "------",
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    CompactTextButton(label = stringResource(Res.string.action_edit), onClick = onEdit)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (counter == null) {
                        CountdownText(
                            clock = clock,
                            period = period,
                            changing = changing,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Text(
                            text = stringResource(Res.string.code_counter, counter),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (changing) {
                        CountdownRing(
                            clock = clock,
                            period = period,
                            modifier = Modifier.size(Sizes.ring).padding(end = Space.s),
                        )
                    }
                    if (counter != null) {
                        CompactTextButton(
                            label = stringResource(Res.string.action_next_code),
                            onClick = onAdvance,
                        )
                    }
                    CompactTextButton(label = stringResource(Res.string.action_delete), onClick = onDelete)
                }
            }
        }
    }
}

/**
 * How much of the current step is left, in whole seconds.
 *
 * Never zero: a step ends as the next one begins, and a countdown that reached zero would be
 * saying the code had expired at the moment it changed.
 */
private fun remainingSeconds(unixSeconds: Long, period: Long): Int =
    (period - unixSeconds.mod(period)).toInt()

/**
 * The seconds left of the current step, read from the clock rather than handed the time.
 *
 * The clock changes once a second, which is what this says, so it is composed again when the
 * number changes and at no other time.
 */
@Composable
private fun CountdownText(
    clock: State<Long>,
    period: Long,
    changing: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        text = if (changing) stringResource(Res.string.code_changes_in, remainingSeconds(clock.value, period)) else "",
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier,
    )
}

/**
 * The ring that counts the same clock down.
 *
 * The clock is read inside the progress lambda, which is evaluated while the ring is drawn, so the
 * ring follows the clock without this composable being composed again. A ring that follows it
 * more finely than once a second is possible and was measured: every tick redraws each countdown
 * on screen, and a state change makes Compose record the display list again, at roughly seventy
 * milliseconds of the UI thread per second for each additional tick per second.
 */
@Composable
private fun CountdownRing(clock: State<Long>, period: Long, modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        progress = { remainingSeconds(clock.value, period).toFloat() / period.toFloat() },
        modifier = modifier,
    )
}

/**
 * A text button with the padding of an action rather than of a dialog button.
 *
 * Several of them share the bottom of a card that is now one column of several, and the
 * default padding of a text button is sized for a row of two across a whole screen.
 */
@Composable
private fun CompactTextButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = Space.s, vertical = Space.xs),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
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
        properties = DialogDefaults,
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

/**
 * The fields of one form row: two to a row when the dialog is wide enough, one otherwise.
 *
 * It is a wrapping row with a cap on how many items fit, so the caller writes each field once
 * and gets either arrangement; the fields take `Modifier.weight(1f)` and fill whatever the
 * row gives them, which is what keeps their labels legible at any width.
 */
@Composable
private fun FormRow(
    twoColumns: Boolean,
    content: @Composable FlowRowScope.() -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
        maxItemsInEachRow = if (twoColumns) 2 else 1,
        content = content,
    )
}

/**
 * Which of the two kinds of code an entry is described by.
 *
 * The vault stores no such field: what makes an entry counter-based is the counter it carries,
 * so this is the editor's own name for the choice, and [EntryForm.counter] is where it lands.
 */
private enum class EntryKind {
    /** TOTP: the code follows the clock, and the entry carries no counter. */
    TimeBased,

    /** HOTP: the code follows a counter, which advances when a server consumes a code. */
    CounterBased,
}

/**
 * What the entry editor collected, which is exactly what is written to the vault.
 *
 * A value rather than the seven arguments the dialog used to hand over: the two kinds of entry
 * fill the same fields with different meanings, and a caller reading named members cannot
 * mistake the counter for the period.
 */
private data class EntryForm(
    val name: String,
    val issuer: String,
    val account: String,
    val secretBase32: String,
    val algorithm: OtpAlgorithm,
    val digits: Int,
    val periodSeconds: Long,
    /** The counter to store, or `null` for a time-based entry. */
    val counter: Long?,
)

/** The period a new entry starts with, which is the step RFC 6238's own examples use. */
private const val DEFAULT_PERIOD_SECONDS = 30L

/** The counter a new counter-based entry starts at, which is RFC 4226's first counter. */
private const val DEFAULT_COUNTER = 0L

/** The editor, for a new entry and for an existing one. */
@Composable
private fun EntryDialog(
    initial: Entry?,
    defaultName: String,
    onDismiss: () -> Unit,
    onSave: (EntryForm) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var issuer by remember { mutableStateOf(initial?.issuer.orEmpty()) }
    var account by remember { mutableStateOf(initial?.account.orEmpty()) }
    // Never filled in from the entry being edited, and never shown back: a secret on screen is
    // a secret in someone's field of view, and nothing about editing the other fields needs
    // it. Editing therefore offers to *replace* the secret — an empty field leaves the one in
    // the vault alone.
    //
    // The field starts on a single space rather than on nothing, because Material floats an
    // outlined field's label onto the border only while the field holds something: on an empty
    // one the label drops inside and stops reading as the field's name, which is where the
    // other fields' names are. A space is `isBlank`, so the rules around this field still see
    // it as empty, and the value is trimmed before it is used.
    var secret by remember { mutableStateOf(" ") }
    var algorithm by remember { mutableStateOf(initial?.algorithm ?: OtpAlgorithm.Sha1) }
    var digits by remember { mutableStateOf((initial?.digits ?: 6).toString()) }
    var period by remember { mutableStateOf((initial?.periodSeconds ?: DEFAULT_PERIOD_SECONDS).toString()) }
    var kind by remember {
        mutableStateOf(if (initial?.isCounterBased == true) EntryKind.CounterBased else EntryKind.TimeBased)
    }
    var counter by remember { mutableStateOf((initial?.counter ?: DEFAULT_COUNTER).toString()) }

    val timeBasedLabel = stringResource(Res.string.entry_type_totp)
    val counterBasedLabel = stringResource(Res.string.entry_type_hotp)

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogDefaults,
        title = {
            Text(stringResource(if (initial == null) Res.string.dialog_add_entry else Res.string.dialog_edit_entry))
        },
        text = {
            // A wide dialog puts the short fields two to a row, a narrow one stacks them.
            // That arrangement is what keeps the form inside the dialog: a column of six
            // fields is taller than a small window, and whatever does not fit is clipped by
            // the dialog's own surface, which is what "the values inside are not visible"
            // looks like — a field cut in half by the edge. The scroll is the fallback for a
            // window short enough to defeat even this.
            BoxWithConstraints {
                val twoColumns = formHasTwoColumns(maxWidth)

                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(Res.string.field_name)) },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    FormRow(twoColumns) {
                        OutlinedTextField(
                            value = issuer,
                            onValueChange = { issuer = it },
                            label = { Text(stringResource(Res.string.field_issuer)) },
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = account,
                            onValueChange = { account = it },
                            label = { Text(stringResource(Res.string.field_account)) },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = {
                            Text(
                                stringResource(
                                    if (initial == null) Res.string.field_secret else Res.string.field_secret_replace,
                                ),
                            )
                        },
                        singleLine = true,
                        // The explanation goes under the field rather than into its label:
                        // the label has to stay a name, the way the other fields' labels are.
                        supportingText = if (initial == null) {
                            null
                        } else {
                            { Text(stringResource(Res.string.hint_secret_kept)) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // The kind first, because it decides which of the last two fields is the
                    // one that means something: a counter for an entry whose server counts, a
                    // period for one whose code follows the clock.
                    Text(stringResource(Res.string.field_entry_type), style = MaterialTheme.typography.labelLarge)
                    ChoiceRow(
                        options = EntryKind.entries,
                        selected = kind,
                        // Resolved above rather than here: the label of a choice is a plain
                        // function, and a resource cannot be read outside composition.
                        label = {
                            when (it) {
                                EntryKind.TimeBased -> timeBasedLabel
                                EntryKind.CounterBased -> counterBasedLabel
                            }
                        },
                        onSelect = { kind = it },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // All three are shown at once: a user reading the algorithm off a server's
                    // setup page should find it without opening anything.
                    Text(stringResource(Res.string.field_algorithm), style = MaterialTheme.typography.labelLarge)
                    ChoiceRow(
                        options = OtpAlgorithm.entries,
                        selected = algorithm,
                        label = { it.label },
                        onSelect = { algorithm = it },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    FormRow(twoColumns) {
                        OutlinedTextField(
                            value = digits,
                            onValueChange = { digits = it },
                            label = { Text(stringResource(Res.string.field_digits)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        when (kind) {
                            EntryKind.TimeBased ->
                                OutlinedTextField(
                                    value = period,
                                    onValueChange = { period = it },
                                    label = { Text(stringResource(Res.string.field_period)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )

                            EntryKind.CounterBased ->
                                OutlinedTextField(
                                    value = counter,
                                    onValueChange = { counter = it },
                                    label = { Text(stringResource(Res.string.field_counter)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                        }
                    }

                    TextButton(onClick = { secret = Qualia.newSecret() }) {
                        Text(stringResource(Res.string.action_generate_secret))
                    }
                }
            }
        },
        confirmButton = {
            // Nothing is defaulted on the way out: a digit count that is not one of the
            // three the scheme has, a period that is not a positive number, or a counter that
            // is not a whole number keeps Save disabled instead of quietly becoming something
            // else. Only the field of the kind in effect is checked, because the other one is
            // not on screen and so could not be corrected.
            val digitCount = digits.toIntOrNull()
            val periodSeconds = period.toLongOrNull()
            val counterValue = counter.toLongOrNull()
            // A blank secret is a value only when there is one to keep: adding an entry needs
            // one, editing an entry leaves the current one alone.
            val acceptable =
                (initial != null || secret.isNotBlank()) &&
                    digitCount != null &&
                    digitCount in 6..8 &&
                    when (kind) {
                        EntryKind.TimeBased -> periodSeconds != null && periodSeconds > 0
                        EntryKind.CounterBased -> counterValue != null && counterValue >= 0
                    }

            Button(
                onClick = {
                    onSave(
                        EntryForm(
                            name = name.ifBlank { defaultName },
                            issuer = issuer,
                            account = account,
                            secretBase32 = secret.trim().uppercase(),
                            algorithm = algorithm,
                            digits = requireNotNull(digitCount),
                            // A counter-based entry advances by counter, so its time step means
                            // nothing: what is written is the field's own value when it still
                            // holds one, and otherwise the scheme's default that the core's own
                            // convention keeps there.
                            periodSeconds =
                                when (kind) {
                                    EntryKind.TimeBased -> requireNotNull(periodSeconds)
                                    EntryKind.CounterBased -> periodSeconds?.takeIf { it > 0 } ?: DEFAULT_PERIOD_SECONDS
                                },
                            counter = if (kind == EntryKind.CounterBased) requireNotNull(counterValue) else null,
                        ),
                    )
                },
                enabled = acceptable,
            ) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}
