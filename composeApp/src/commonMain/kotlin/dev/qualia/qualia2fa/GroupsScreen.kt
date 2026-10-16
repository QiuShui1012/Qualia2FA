package dev.qualia.qualia2fa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.action_cancel
import dev.qualia.qualia2fa.resources.action_close
import dev.qualia.qualia2fa.resources.action_move_down
import dev.qualia.qualia2fa.resources.action_move_to
import dev.qualia.qualia2fa.resources.action_move_up
import dev.qualia.qualia2fa.resources.action_new_group
import dev.qualia.qualia2fa.resources.action_remove
import dev.qualia.qualia2fa.resources.action_rename
import dev.qualia.qualia2fa.resources.action_save
import dev.qualia.qualia2fa.resources.dialog_new_group
import dev.qualia.qualia2fa.resources.field_group_name
import dev.qualia.qualia2fa.resources.groups_empty
import dev.qualia.qualia2fa.resources.groups_name_taken
import dev.qualia.qualia2fa.resources.groups_order_hint
import dev.qualia.qualia2fa.resources.groups_remove_hint
import dev.qualia.qualia2fa.resources.groups_top
import dev.qualia.qualia2fa.resources.groups_top_hint
import dev.qualia.qualia2fa.resources.settings_groups
import org.jetbrains.compose.resources.stringResource

/**
 * The groups, and the order inside each of them.
 *
 * # What a group is here
 *
 * A group is an **ordered** collection. That order is not decoration: it is what the user
 * arranged, it travels between devices, and two devices that arranged the same group differently
 * is a conflict the sync screen shows rather than something this screen may paper over. The entry
 * order is therefore edited deliberately - one step at a time, written in full - and the hint
 * under the list says so.
 *
 * # What removing a group does
 *
 * It removes the group, not its entries: they return to the top group. That is what makes it a
 * tidying action, and the confirmation says it in those words. A user who wanted to delete the
 * entries has the list for that.
 *
 * # Why the top group is shown
 *
 * Because it is where every entry starts, and an entry that is in no named group would otherwise
 * be invisible here. It cannot be renamed, moved or removed: it is not a group the user made, it
 * is where the ungrouped entries are.
 */
@Composable
fun GroupsScreen(
    vault: Vault,
    onClose: () -> Unit,
    onChanged: () -> Unit,
) {
    var groups by remember { mutableStateOf(vault.groups()) }
    var names by remember { mutableStateOf(entryNames(vault)) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Group?>(null) }
    var moving by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<Group?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val nameTaken = stringResource(Res.string.groups_name_taken)

    /** Re-reads what the vault holds. Every action goes through here, so the screen cannot show a
     * group that is no longer there, or hide one that is. */
    fun refresh() {
        groups = vault.groups()
        names = entryNames(vault)
        onChanged()
    }

    /** Writes the order of [group] with the entry at [index] moved by [step]. */
    fun swapEntries(group: Group, index: Int, step: Int) {
        val target = index + step
        if (target < 0 || target >= group.entryIds.size) return
        val order = group.entryIds.toMutableList()
        val moved = order.removeAt(index)
        order.add(target, moved)
        if (vault.setOrdering(group.id, order)) refresh() else message = nameTaken
    }

    val named = groups.filterNot { it.top }

    Column(
        modifier = Modifier.fillMaxWidth().padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        ScreenTitle(stringResource(Res.string.settings_groups))
        Hint(stringResource(Res.string.groups_order_hint))
        MessageLine(message)

        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            OutlinedButton(onClick = { creating = true }) { Text(stringResource(Res.string.action_new_group)) }
            TextButton(onClick = onClose) { Text(stringResource(Res.string.action_close)) }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            items(groups, key = { it.id }) { group ->
                val index = groups.indexOf(group)
                val position = index - 1
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            Text(
                                if (group.top) stringResource(Res.string.groups_top) else group.name,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (group.top) {
                                Text(
                                    stringResource(Res.string.groups_top_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                TextButton(
                                    enabled = position > 0,
                                    onClick = { if (vault.moveGroup(group.id, position - 1)) refresh() },
                                ) { Text(stringResource(Res.string.action_move_up)) }
                                TextButton(
                                    enabled = position < named.size - 1,
                                    onClick = { if (vault.moveGroup(group.id, position + 1)) refresh() },
                                ) { Text(stringResource(Res.string.action_move_down)) }
                                TextButton(onClick = { renaming = group }) {
                                    Text(stringResource(Res.string.action_rename))
                                }
                                TextButton(onClick = { removing = group }) {
                                    Text(stringResource(Res.string.action_remove))
                                }
                            }
                        }

                        if (group.entryIds.isEmpty()) {
                            Hint(stringResource(Res.string.groups_empty))
                        } else {
                            group.entryIds.forEachIndexed { entryIndex, entryId ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                                ) {
                                    Text(
                                        names[entryId] ?: shortDeviceId(entryId),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        enabled = entryIndex > 0,
                                        onClick = { swapEntries(group, entryIndex, -1) },
                                    ) { Text(stringResource(Res.string.action_move_up)) }
                                    TextButton(
                                        enabled = entryIndex < group.entryIds.size - 1,
                                        onClick = { swapEntries(group, entryIndex, 1) },
                                    ) { Text(stringResource(Res.string.action_move_down)) }
                                    TextButton(onClick = { moving = entryId }) {
                                        Text(stringResource(Res.string.action_move_to))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = stringResource(Res.string.dialog_new_group),
            initial = "",
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                if (vault.createGroup(name) == null) message = nameTaken else refresh()
            },
        )
    }

    renaming?.let { group ->
        NameDialog(
            title = stringResource(Res.string.action_rename),
            initial = group.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                if (!vault.renameGroup(group.id, name)) message = nameTaken else refresh()
            },
        )
    }

    moving?.let { entryId ->
        AlertDialog(
            onDismissRequest = { moving = null },
            properties = DialogDefaults,
            title = { Text(stringResource(Res.string.action_move_to)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    groups.forEach { group ->
                        TextButton(
                            onClick = {
                                moving = null
                                if (vault.assignToGroup(entryId, group.id)) refresh() else message = nameTaken
                            },
                        ) {
                            Text(if (group.top) stringResource(Res.string.groups_top) else group.name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { moving = null }) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }

    removing?.let { group ->
        AlertDialog(
            onDismissRequest = { removing = null },
            properties = DialogDefaults,
            title = { Text(stringResource(Res.string.action_remove)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(group.name, style = MaterialTheme.typography.titleMedium)
                    // Said in full rather than implied: the action removes the group, and a user
                    // who reads "remove" as "remove its entries" would not expect them to survive.
                    Hint(stringResource(Res.string.groups_remove_hint))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        removing = null
                        if (vault.removeGroup(group.id)) refresh() else message = nameTaken
                    },
                ) { Text(stringResource(Res.string.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }
}

/** Asks for a group's name. */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    val acceptable = name.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogDefaults,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.field_group_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = acceptable, onClick = { onConfirm(name.trim()) }) {
                Text(stringResource(Res.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

/** The name of every entry, by identifier. */
private fun entryNames(vault: Vault): Map<String, String> = vault.entries().associate { it.id to it.name }
