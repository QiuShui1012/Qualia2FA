package dev.qualia.qualia2fa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.action_accept_deletion
import dev.qualia.qualia2fa.resources.action_generate_code
import dev.qualia.qualia2fa.resources.action_keep_deleted
import dev.qualia.qualia2fa.resources.action_keep_entry
import dev.qualia.qualia2fa.resources.action_keep_local
import dev.qualia.qualia2fa.resources.action_merge
import dev.qualia.qualia2fa.resources.action_pair_new_device
import dev.qualia.qualia2fa.resources.action_scan_code
import dev.qualia.qualia2fa.resources.action_restore_entry
import dev.qualia.qualia2fa.resources.action_take_incoming
import dev.qualia.qualia2fa.resources.dialog_pick_image
import dev.qualia.qualia2fa.resources.error_image_unreadable
import dev.qualia.qualia2fa.resources.field_peer_code
import dev.qualia.qualia2fa.resources.filter_image_files
import dev.qualia.qualia2fa.resources.message_copied
import dev.qualia.qualia2fa.resources.nav_sync
import dev.qualia.qualia2fa.resources.sync_conflict_entry_mismatch
import dev.qualia.qualia2fa.resources.sync_conflict_heading
import dev.qualia.qualia2fa.resources.sync_conflict_local_deleted
import dev.qualia.qualia2fa.resources.sync_conflict_local_is_newer
import dev.qualia.qualia2fa.resources.sync_conflict_peer_deleted
import dev.qualia.qualia2fa.resources.sync_conflict_side_deleted
import dev.qualia.qualia2fa.resources.sync_conflict_side_incoming
import dev.qualia.qualia2fa.resources.sync_conflict_side_local
import dev.qualia.qualia2fa.resources.sync_error_already_applied
import dev.qualia.qualia2fa.resources.sync_error_authentication_failed
import dev.qualia.qualia2fa.resources.sync_error_code_rejected
import dev.qualia.qualia2fa.resources.sync_error_decision_refused
import dev.qualia.qualia2fa.resources.sync_error_no_code_in_image
import dev.qualia.qualia2fa.resources.sync_error_no_device_identity
import dev.qualia.qualia2fa.resources.sync_error_not_saved
import dev.qualia.qualia2fa.resources.sync_error_nothing_to_read
import dev.qualia.qualia2fa.resources.sync_error_other
import dev.qualia.qualia2fa.resources.sync_error_sender_mismatch
import dev.qualia.qualia2fa.resources.sync_intro
import dev.qualia.qualia2fa.resources.sync_message_code_read
import dev.qualia.qualia2fa.resources.sync_my_code_explainer
import dev.qualia.qualia2fa.resources.sync_pair_explainer
import dev.qualia.qualia2fa.resources.sync_pair_section
import dev.qualia.qualia2fa.resources.sync_read_explainer
import dev.qualia.qualia2fa.resources.sync_result_needs_decision
import dev.qualia.qualia2fa.resources.sync_result_nothing_new
import dev.qualia.qualia2fa.resources.sync_result_summary
import org.jetbrains.compose.resources.stringResource

/**
 * Exchanging codes with another device: one direction at a time, in both roles.
 *
 * # Two things live here
 *
 * *Syncing* moves entries between two devices that already share the account, and *pairing*
 * hands the account to a device that does not have it yet. They are the same kind of
 * operation — a code the user carries from one device to the other — and the design puts
 * them in the same place by giving this tab to "sync" and the login screen to the joining
 * side. Pairing is the reverse of that login, so it belongs on this screen.
 *
 * # Without navigation
 *
 * The design asks for the sync screen **without** navigation in the login flow. That is
 * [JoinAccountScreen], which is the joining side of a pairing rendered in this screen's
 * language; this composable is the tab, and the tab always has the navigation around it.
 */
@Composable
fun SyncScreen(
    vault: Vault,
    deviceId: String?,
    onChanged: () -> Unit,
) {
    var pairing by remember { mutableStateOf(false) }

    if (pairing) {
        PairNewDeviceScreen(vault = vault, onDone = { pairing = false })
        return
    }

    val session = remember(vault, deviceId) { deviceId?.let { Qualia.syncSession(vault, it) } }

    var myCode by remember { mutableStateOf<String?>(null) }
    var peerCode by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<SyncOutcome?>(null) }
    var conflicts by remember { mutableStateOf<List<SyncConflict>>(emptyList()) }

    val copied = stringResource(Res.string.message_copied)
    val unreadableImage = stringResource(Res.string.error_image_unreadable)
    val noCodeInImage = stringResource(Res.string.sync_error_no_code_in_image)
    val codeRead = stringResource(Res.string.sync_message_code_read)
    val notSaved = stringResource(Res.string.sync_error_not_saved)
    val decisionRefused = stringResource(Res.string.sync_error_decision_refused)
    val scanner = LocalCodeScanner.current

    // Resolved here rather than where a refusal happens: a merge runs in a callback, and no
    // resource can be resolved there.
    val refusalTexts =
        mapOf(
            SyncRefusal.NotACode to stringResource(Res.string.sync_error_code_rejected),
            SyncRefusal.NothingToRead to stringResource(Res.string.sync_error_nothing_to_read),
            SyncRefusal.AlreadyApplied to stringResource(Res.string.sync_error_already_applied),
            SyncRefusal.SenderMismatch to stringResource(Res.string.sync_error_sender_mismatch),
            SyncRefusal.AuthenticationFailed to stringResource(Res.string.sync_error_authentication_failed),
            SyncRefusal.Other to stringResource(Res.string.sync_error_other),
        )

    if (session == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            ScreenHeader(title = stringResource(Res.string.nav_sync))
            MessageLine(stringResource(Res.string.sync_error_no_device_identity))
        }
        return
    }

    /** Merges what is in the field, and keeps whatever the merge left to decide. */
    fun merge(text: String) {
        when (val result = session.mergeText(text)) {
            is SyncMerge.Refused -> message = refusalTexts[result.reason]
            is SyncMerge.Merged -> {
                outcome = result.outcome
                conflicts = session.conflicts()
                message = null
                peerCode = ""
                onChanged()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(Space.l).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        ScreenHeader(title = stringResource(Res.string.nav_sync))
        Hint(stringResource(Res.string.sync_intro))

        SectionCard(title = stringResource(Res.string.action_generate_code)) {
            Hint(stringResource(Res.string.sync_my_code_explainer))
            Button(onClick = {
                val code = session.myCodeText()
                if (code == null) {
                    message = notSaved
                } else {
                    myCode = code
                    message = null
                }
            }) {
                Text(stringResource(Res.string.action_generate_code))
            }
            myCode?.let { code -> CodeBlock(code = code, onCopied = { message = copied }) }
        }

        SectionCard(title = stringResource(Res.string.action_merge)) {
            Hint(stringResource(Res.string.sync_read_explainer))
            OutlinedTextField(
                value = peerCode,
                onValueChange = { peerCode = it },
                label = { Text(stringResource(Res.string.field_peer_code)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = {
                scanner.readCode(
                    unreadable = unreadableImage,
                    noCode = noCodeInImage,
                    onText = {
                        peerCode = it
                        message = codeRead
                    },
                    onMessage = { message = it },
                )
            }) {
                Text(stringResource(Res.string.action_scan_code))
            }
            Button(onClick = { merge(peerCode.trim()) }, enabled = peerCode.isNotBlank()) {
                Text(stringResource(Res.string.action_merge))
            }

            outcome?.let { result ->
                MessageLine(
                    if (result.madeNoChange) {
                        stringResource(Res.string.sync_result_nothing_new)
                    } else {
                        stringResource(
                            Res.string.sync_result_summary,
                            result.added,
                            result.tombstonesAdded,
                            result.deletionsApplied,
                            result.unchanged,
                        )
                    },
                    isError = false,
                )
            }
        }

        if (conflicts.isNotEmpty()) {
            SectionCard(title = stringResource(Res.string.sync_conflict_heading)) {
                MessageLine(stringResource(Res.string.sync_result_needs_decision, conflicts.size))
                conflicts.forEach { conflict ->
                    ConflictCard(
                        conflict = conflict,
                        onDecide = { takeIncoming ->
                            if (session.decide(conflict.entryId, takeIncoming)) {
                                conflicts = session.conflicts()
                                message = null
                                onChanged()
                            } else {
                                message = decisionRefused
                            }
                        },
                    )
                }
            }
        }

        SectionCard(title = stringResource(Res.string.sync_pair_section)) {
            Hint(stringResource(Res.string.sync_pair_explainer))
            OutlinedButton(onClick = { pairing = true }) {
                Text(stringResource(Res.string.action_pair_new_device))
            }
        }

        MessageLine(message)
    }
}

/**
 * One disagreement, with the two versions side by side and the two ways out of it.
 *
 * The buttons say what each choice **does**, not "yes" and "no": for a deletion the same two
 * buttons mean opposite things depending on which device deleted, and a user who has to work
 * that out from the conflict is a user who will pick wrong once.
 */
@Composable
private fun ConflictCard(conflict: SyncConflict, onDecide: (Boolean) -> Unit) {
    SectionCard {
        Text(
            when (conflict.kind) {
                SyncConflictKind.EntryMismatch -> stringResource(Res.string.sync_conflict_entry_mismatch)
                SyncConflictKind.PeerDeleted -> stringResource(Res.string.sync_conflict_peer_deleted)
                SyncConflictKind.LocalDeleted -> stringResource(Res.string.sync_conflict_local_deleted)
                SyncConflictKind.Unknown -> stringResource(Res.string.sync_conflict_heading)
            },
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )

        if (conflict.kind == SyncConflictKind.PeerDeleted && conflict.localIsNewer) {
            Hint(stringResource(Res.string.sync_conflict_local_is_newer))
        }

        SideRow(stringResource(Res.string.sync_conflict_side_local), conflict.local)
        SideRow(stringResource(Res.string.sync_conflict_side_incoming), conflict.incoming)

        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            OutlinedButton(onClick = { onDecide(false) }) {
                Text(
                    when (conflict.kind) {
                        SyncConflictKind.PeerDeleted -> stringResource(Res.string.action_keep_entry)
                        SyncConflictKind.LocalDeleted -> stringResource(Res.string.action_keep_deleted)
                        else -> stringResource(Res.string.action_keep_local)
                    },
                )
            }
            Button(onClick = { onDecide(true) }) {
                Text(
                    when (conflict.kind) {
                        SyncConflictKind.PeerDeleted -> stringResource(Res.string.action_accept_deletion)
                        SyncConflictKind.LocalDeleted -> stringResource(Res.string.action_restore_entry)
                        else -> stringResource(Res.string.action_take_incoming)
                    },
                )
            }
        }
    }
}

/** One side of a disagreement: the entry's fields, or the fact that it was deleted. */
@Composable
private fun SideRow(label: String, side: SyncSide?) {
    Column {
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
        if (side == null) {
            Text(stringResource(Res.string.sync_conflict_side_deleted))
        } else {
            Text(side.name)
            val details =
                listOfNotNull(
                    side.issuer.takeIf { it.isNotBlank() },
                    side.account.takeIf { it.isNotBlank() },
                )
            if (details.isNotEmpty()) {
                Hint(details.joinToString(" · "))
            }
        }
    }
}
