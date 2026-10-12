package dev.qualia.qualia2fa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.action_accept_deletion
import dev.qualia.qualia2fa.resources.action_back
import dev.qualia.qualia2fa.resources.action_copy
import dev.qualia.qualia2fa.resources.action_generate_code
import dev.qualia.qualia2fa.resources.action_keep_deleted
import dev.qualia.qualia2fa.resources.action_keep_entry
import dev.qualia.qualia2fa.resources.action_keep_local
import dev.qualia.qualia2fa.resources.action_merge
import dev.qualia.qualia2fa.resources.action_read_code_from_image
import dev.qualia.qualia2fa.resources.action_restore_entry
import dev.qualia.qualia2fa.resources.action_sync
import dev.qualia.qualia2fa.resources.action_take_incoming
import dev.qualia.qualia2fa.resources.dialog_pick_image
import dev.qualia.qualia2fa.resources.error_image_unreadable
import dev.qualia.qualia2fa.resources.field_peer_code
import dev.qualia.qualia2fa.resources.filter_image_files
import dev.qualia.qualia2fa.resources.message_copied
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
import dev.qualia.qualia2fa.resources.sync_read_explainer
import dev.qualia.qualia2fa.resources.sync_result_needs_decision
import dev.qualia.qualia2fa.resources.sync_result_nothing_new
import dev.qualia.qualia2fa.resources.sync_result_summary
import org.jetbrains.compose.resources.stringResource

/**
 * Synchronization, over the code channel.
 *
 * Both directions live on one screen because a sync **is** two directions: this device shows
 * a code, the other reads it, and then the other shows its own code for this device to read.
 * The code is generated on demand rather than when the screen opens, so that generating it
 * again after a merge is the obvious next step.
 *
 * [onChanged] is called after a merge or a decision wrote into the vault, so the entry list
 * behind this screen can refresh and the vault can be persisted.
 */
@Composable
fun SyncScreen(
    vault: Vault,
    deviceId: String?,
    onChanged: () -> Unit,
    onDone: () -> Unit,
) {
    val session = remember(vault, deviceId) { deviceId?.let { Qualia.syncSession(vault, it) } }

    var myCode by remember { mutableStateOf<String?>(null) }
    var peerCode by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<SyncOutcome?>(null) }
    var conflicts by remember { mutableStateOf<List<SyncConflict>>(emptyList()) }
    val clipboard = LocalClipboardManager.current

    val copied = stringResource(Res.string.message_copied)
    val unreadableImage = stringResource(Res.string.error_image_unreadable)
    val noCodeInImage = stringResource(Res.string.sync_error_no_code_in_image)
    val codeRead = stringResource(Res.string.sync_message_code_read)
    val notSaved = stringResource(Res.string.sync_error_not_saved)
    val decisionRefused = stringResource(Res.string.sync_error_decision_refused)
    val pickImageTitle = stringResource(Res.string.dialog_pick_image)
    val imageFilesLabel = stringResource(Res.string.filter_image_files)

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
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.action_sync), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(Res.string.sync_error_no_device_identity), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onDone) { Text(stringResource(Res.string.action_back)) }
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
        modifier = Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.action_sync), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(Res.string.sync_intro), style = MaterialTheme.typography.bodyMedium)

        HorizontalDivider()
        Text(stringResource(Res.string.sync_my_code_explainer), style = MaterialTheme.typography.bodySmall)
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

        myCode?.let { code ->
            // The code is drawn when it fits; the text stays underneath either way, because a
            // code too dense to scan must still leave the user a way to carry it over.
            remember(code) { Qualia.qrImage(code) }?.let { image -> QrCodeView(image) }
            Text(code, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(code))
                message = copied
            }) {
                Text(stringResource(Res.string.action_copy))
            }
        }

        HorizontalDivider()
        Text(stringResource(Res.string.sync_read_explainer), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = peerCode,
            onValueChange = { peerCode = it },
            label = { Text(stringResource(Res.string.field_peer_code)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = {
            val image = pickGrayImage(pickImageTitle, imageFilesLabel)
            if (image == null) {
                message = unreadableImage
            } else {
                val read = Qualia.qrTextFromImage(image.pixels, image.width, image.height)
                if (read == null) {
                    message = noCodeInImage
                } else {
                    peerCode = read
                    message = codeRead
                }
            }
        }) {
            Text(stringResource(Res.string.action_read_code_from_image))
        }
        Button(onClick = { merge(peerCode.trim()) }, enabled = peerCode.isNotBlank()) {
            Text(stringResource(Res.string.action_merge))
        }

        outcome?.let { result ->
            Text(
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
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (conflicts.isNotEmpty()) {
            Text(
                stringResource(Res.string.sync_result_needs_decision, conflicts.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
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

        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = onDone) { Text(stringResource(Res.string.action_back)) }
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
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(Res.string.sync_conflict_heading), style = MaterialTheme.typography.titleMedium)
            Text(
                when (conflict.kind) {
                    SyncConflictKind.EntryMismatch -> stringResource(Res.string.sync_conflict_entry_mismatch)
                    SyncConflictKind.PeerDeleted -> stringResource(Res.string.sync_conflict_peer_deleted)
                    SyncConflictKind.LocalDeleted -> stringResource(Res.string.sync_conflict_local_deleted)
                    SyncConflictKind.Unknown -> stringResource(Res.string.sync_conflict_heading)
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            if (conflict.kind == SyncConflictKind.PeerDeleted && conflict.localIsNewer) {
                Text(
                    stringResource(Res.string.sync_conflict_local_is_newer),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SideRow(stringResource(Res.string.sync_conflict_side_local), conflict.local)
            SideRow(stringResource(Res.string.sync_conflict_side_incoming), conflict.incoming)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
}

/** One side of a disagreement: the entry's fields, or the fact that it was deleted. */
@Composable
private fun SideRow(label: String, side: SyncSide?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        if (side == null) {
            Text(stringResource(Res.string.sync_conflict_side_deleted), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(side.name, style = MaterialTheme.typography.bodyMedium)
            val details = listOfNotNull(side.issuer.takeIf { it.isNotBlank() }, side.account.takeIf { it.isNotBlank() })
            if (details.isNotEmpty()) {
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
