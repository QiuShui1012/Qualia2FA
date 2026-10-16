package dev.qualia.qualia2fa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.action_accept_deletion
import dev.qualia.qualia2fa.resources.action_find_on_lan
import dev.qualia.qualia2fa.resources.action_generate_code
import dev.qualia.qualia2fa.resources.action_keep_deleted
import dev.qualia.qualia2fa.resources.action_keep_entry
import dev.qualia.qualia2fa.resources.action_keep_local
import dev.qualia.qualia2fa.resources.action_lan_receive_state
import dev.qualia.qualia2fa.resources.action_lan_send_state
import dev.qualia.qualia2fa.resources.action_merge
import dev.qualia.qualia2fa.resources.action_pair_new_device
import dev.qualia.qualia2fa.resources.action_scan_code
import dev.qualia.qualia2fa.resources.action_restore_entry
import dev.qualia.qualia2fa.resources.action_stop_lan
import dev.qualia.qualia2fa.resources.action_take_incoming
import dev.qualia.qualia2fa.resources.error_image_unreadable
import dev.qualia.qualia2fa.resources.field_peer_code
import dev.qualia.qualia2fa.resources.lan_permission_refused
import dev.qualia.qualia2fa.resources.message_copied
import dev.qualia.qualia2fa.resources.nav_sync
import dev.qualia.qualia2fa.resources.sync_conflict_entry_mismatch
import dev.qualia.qualia2fa.resources.action_use_incoming_order
import dev.qualia.qualia2fa.resources.action_use_local_order
import dev.qualia.qualia2fa.resources.sync_conflict_ordering
import dev.qualia.qualia2fa.resources.sync_conflict_ordering_empty
import dev.qualia.qualia2fa.resources.sync_conflict_ordering_hint
import dev.qualia.qualia2fa.resources.sync_conflict_ordering_incoming
import dev.qualia.qualia2fa.resources.sync_conflict_ordering_local
import dev.qualia.qualia2fa.resources.sync_conflict_ordering_renamed
import dev.qualia.qualia2fa.resources.sync_conflict_ordering_top
import dev.qualia.qualia2fa.resources.sync_conflict_heading
import dev.qualia.qualia2fa.resources.sync_conflict_local_deleted
import dev.qualia.qualia2fa.resources.sync_conflict_local_is_newer
import dev.qualia.qualia2fa.resources.sync_conflict_peer_deleted
import dev.qualia.qualia2fa.resources.sync_conflict_side_deleted
import dev.qualia.qualia2fa.resources.sync_conflict_side_incoming
import dev.qualia.qualia2fa.resources.sync_frames_count
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
import dev.qualia.qualia2fa.resources.sync_lan_explainer
import dev.qualia.qualia2fa.resources.sync_lan_looking
import dev.qualia.qualia2fa.resources.sync_lan_no_peers
import dev.qualia.qualia2fa.resources.sync_lan_nothing_received
import dev.qualia.qualia2fa.resources.sync_lan_peer_left
import dev.qualia.qualia2fa.resources.sync_lan_peer_unreachable
import dev.qualia.qualia2fa.resources.sync_lan_section
import dev.qualia.qualia2fa.resources.sync_lan_selected
import dev.qualia.qualia2fa.resources.sync_lan_send_failed
import dev.qualia.qualia2fa.resources.sync_lan_sent
import dev.qualia.qualia2fa.resources.sync_lan_unavailable
import dev.qualia.qualia2fa.resources.sync_lan_waiting
import dev.qualia.qualia2fa.resources.sync_my_code_explainer
import dev.qualia.qualia2fa.resources.sync_pair_explainer
import dev.qualia.qualia2fa.resources.sync_pair_section
import dev.qualia.qualia2fa.resources.sync_read_explainer
import dev.qualia.qualia2fa.resources.sync_result_needs_decision
import dev.qualia.qualia2fa.resources.sync_result_nothing_new
import dev.qualia.qualia2fa.resources.sync_result_summary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Exchanging codes with another device: one direction at a time, in both roles.
 *
 * # Two things live here
 *
 * *Syncing* moves entries between two devices that already share the account, and *pairing*
 * hands the account to a device that does not have it yet. They are the same kind of
 * operation — a code the user carries from one device to the other — so the design gives
 * them one place. This tab is the "sync" side; the login screen is the joining side.
 * Pairing is the reverse of that login, so it belongs on this screen.
 *
 * # Without navigation
 *
 * The design asks for the sync screen **without** navigation in the login flow. That is
 * [JoinAccountScreen], which is the joining side of a pairing rendered in this screen's
 * language; this composable is the tab, and a tab always comes with the surrounding
 * navigation.
 *
 * # The code route and the network route
 *
 * A code is carried by hand and always works. Two devices on one WiFi can also reach each
 * other directly, which is the section that opens a [LanChannel]; it is opened by an explicit
 * action and closed on the way out, because an open endpoint holds a port, a thread and a
 * place in the network's announcements.
 */
@Composable
fun SyncScreen(
    vault: Vault,
    deviceId: String?,
    onChanged: () -> Unit,
) {
    var pairing by remember { mutableStateOf(false) }

    if (pairing) {
        PairNewDeviceScreen(vault = vault, deviceId = deviceId, onDone = { pairing = false })
        return
    }

    val session = remember(vault, deviceId) { deviceId?.let { Qualia.syncSession(vault, it) } }

    var myCode by remember { mutableStateOf<String?>(null) }
    var peerCode by remember { mutableStateOf("") }
    var collected by remember { mutableStateOf<Pair<Int, Int?>?>(null) }
    var completedPayload by remember { mutableStateOf<String?>(null) }
    val collector = remember { Qualia.frameCollector() }
    var message by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<SyncOutcome?>(null) }
    var outcomeOverLan by remember { mutableStateOf(false) }
    var conflicts by remember { mutableStateOf(PendingConflicts.none) }

    var lanChannel by remember { mutableStateOf<LanChannel?>(null) }
    var lanLobby by remember { mutableStateOf<LanLobby?>(null) }
    var lanPeers by remember { mutableStateOf<List<LanPeer>>(emptyList()) }
    var lanSelected by remember { mutableStateOf<String?>(null) }
    var lanSentTo by remember { mutableStateOf<String?>(null) }
    var lanReceiving by remember { mutableStateOf(false) }
    val lanSession = remember(vault, lanChannel) { lanChannel?.let { Qualia.lanSyncSession(vault, it) } }

    val multicast = rememberMulticastHold()
    val permission = rememberLanPermission()
    val scope = rememberCoroutineScope()

    val copied = stringResource(Res.string.message_copied)
    val unreadableImage = stringResource(Res.string.error_image_unreadable)
    val noCodeInImage = stringResource(Res.string.sync_error_no_code_in_image)
    val notSaved = stringResource(Res.string.sync_error_not_saved)
    val decisionRefused = stringResource(Res.string.sync_error_decision_refused)
    val lanUnavailable = stringResource(Res.string.sync_lan_unavailable)
    val lanRefused = stringResource(Res.string.lan_permission_refused)
    val lanSendFailed = stringResource(Res.string.sync_lan_send_failed)
    val lanNothingReceived = stringResource(Res.string.sync_lan_nothing_received)
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

    /** Opens an endpoint and its lobby, which is what looking for devices costs. */
    fun openLan() {
        val id = deviceId ?: return
        val channel = Qualia.lanChannel(id)
        if (channel == null) {
            message = lanUnavailable
            return
        }
        multicast.acquire()
        val lobby = channel.openLobby()
        if (lobby == null) {
            multicast.release()
            channel.close()
            message = lanUnavailable
            return
        }
        lanChannel = channel
        lanLobby = lobby
        lobby.announceNow()
        message = null
    }

    /** Closes the endpoint and its lobby, and gives the multicast hold back. */
    fun closeLan() {
        lanLobby?.close()
        lanChannel?.close()
        multicast.release()
        lanLobby = null
        lanChannel = null
        lanPeers = emptyList()
        lanSelected = null
        lanSentTo = null
    }

    /**
     * What one read code does.
     *
     * A payload that fits in one code is unwrapped and put in the field, which is where the
     * user sees what they are about to merge. A code that is one of several is handed to the
     * collector instead: it carries part of the payload, and the field is for whole codes.
     */
    fun collect(scanned: String): FrameCount? =
        when (val step = collector?.push(scanned)) {
            is FrameStep.Completed -> {
                completedPayload = step.payload.decodeToString()
                collected = collector?.let { it.received to it.expected }
                null
            }
            is FrameStep.Taken -> {
                collected = collector?.let { it.received to it.expected }
                FrameCount(step.index, step.count)
            }
            else -> null
        }

    /** Merges what is in the field, and keeps whatever the merge left to decide. */
    fun merge(text: String) {
        when (val result = session.mergeText(text)) {
            is SyncMerge.Refused -> message = refusalTexts[result.reason]
            is SyncMerge.Merged -> {
                outcome = result.outcome
                outcomeOverLan = false
                conflicts = PendingConflicts(session.conflicts(), session::conflicts, session::decide)
                message = null
                peerCode = ""
                onChanged()
            }
        }
    }

    /**
     * Sends this device's state to [peer], which has to be registered first.
     *
     * Registration is what gives the endpoint an address to dial: an announcement carries
     * none, so a peer the lobby has heard but never seen a source for can be shown and not
     * reached.
     */
    fun sendState(peer: LanPeer) {
        val channel = lanChannel
        val lan = lanSession
        if (channel == null || lan == null || !channel.remember(peer)) {
            message = lanSendFailed
            return
        }
        if (lan.send(peer.deviceId)) {
            lanSentTo = peer.deviceId
            message = null
        } else {
            message = lanSendFailed
        }
    }

    /** Takes the other device's state, which is the direction that changes this vault. */
    fun receiveState() {
        val lan = lanSession ?: return
        scope.launch {
            lanReceiving = true
            val result = lan.receive(LAN_RECEIVE_TIMEOUT_MS)
            lanReceiving = false
            when (result) {
                is SyncMerge.Refused ->
                    message =
                        if (result.reason == SyncRefusal.NothingToRead) {
                            lanNothingReceived
                        } else {
                            refusalTexts[result.reason]
                        }

                is SyncMerge.Merged -> {
                    outcome = result.outcome
                    outcomeOverLan = true
                    conflicts = PendingConflicts(lan.conflicts(), lan::conflicts, lan::decide)
                    message = null
                    onChanged()
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { closeLan() }
    }

    LaunchedEffect(lanLobby) {
        val lobby = lanLobby ?: return@LaunchedEffect
        while (true) {
            lanPeers = lobby.peers()
            delay(LAN_LIST_REFRESH_MS)
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
            myCode?.let { code ->
                // One code or a sequence of them, decided where every other code is drawn:
                // what this screen hands over is the text, and how many symbols it takes to
                // show it is the transport's answer.
                CodeBlock(code = code, onCopied = { message = copied })
            }
        }

        SectionCard(title = stringResource(Res.string.action_merge)) {
            Hint(stringResource(Res.string.sync_read_explainer))
            OutlinedTextField(
                value = peerCode,
                onValueChange = { peerCode = it },
                label = { Text(stringResource(Res.string.field_peer_code)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                OutlinedButton(onClick = {
                    scanner.readCode(
                        unreadable = unreadableImage,
                        noCode = noCodeInImage,
                        onFrame = { collect(it) },
                        onText = { scanned ->
                            val payload = completedPayload
                            completedPayload = null
                            merge(payload ?: Qualia.unwrapQrText(scanned) ?: scanned)
                        },
                        onMessage = { message = it },
                    )
                }) {
                    Text(stringResource(Res.string.action_scan_code))
                }
                collected?.let { (received, expected) ->
                    Text(stringResource(Res.string.sync_frames_count, received, expected ?: received))
                }
            }
            Button(onClick = { merge(peerCode.trim()) }, enabled = peerCode.isNotBlank()) {
                Text(stringResource(Res.string.action_merge))
            }

            if (!outcomeOverLan) {
                outcome?.let { result -> MergeResult(result) }
            }
        }

        SectionCard(title = stringResource(Res.string.sync_lan_section)) {
            Hint(stringResource(Res.string.sync_lan_explainer))

            if (lanChannel == null) {
                Button(onClick = {
                    permission.request { allowed -> if (allowed) openLan() else message = lanRefused }
                }) {
                    Text(stringResource(Res.string.action_find_on_lan))
                }
            } else {
                if (lanPeers.isEmpty()) {
                    CircularProgressIndicator(modifier = Modifier.size(Space.xl))
                    Hint(stringResource(Res.string.sync_lan_looking))
                    Hint(stringResource(Res.string.sync_lan_no_peers))
                } else {
                    lanPeers.forEach { peer ->
                        LanPeerChoice(
                            peer = peer,
                            selected = peer.deviceId == lanSelected,
                            onSelect = { lanSelected = peer.deviceId },
                        )
                    }
                }

                val selected = lanPeers.firstOrNull { it.deviceId == lanSelected }
                if (selected != null) {
                    Text(stringResource(Res.string.sync_lan_selected, shortDeviceId(selected.deviceId)))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        Button(
                            onClick = { sendState(selected) },
                            enabled = !lanReceiving && selected.socket != null,
                        ) {
                            Text(stringResource(Res.string.action_lan_send_state))
                        }
                        OutlinedButton(onClick = { receiveState() }, enabled = !lanReceiving) {
                            Text(stringResource(Res.string.action_lan_receive_state))
                        }
                    }
                }

                if (lanReceiving) {
                    CircularProgressIndicator(modifier = Modifier.size(Space.xl))
                    Hint(stringResource(Res.string.sync_lan_waiting, LAN_RECEIVE_SECONDS))
                }

                if (outcomeOverLan) {
                    outcome?.let { result -> MergeResult(result) }
                }

                lanSentTo?.let { device ->
                    MessageLine(stringResource(Res.string.sync_lan_sent, shortDeviceId(device)), isError = false)
                }

                OutlinedButton(onClick = { closeLan() }, enabled = !lanReceiving) {
                    Text(stringResource(Res.string.action_stop_lan))
                }
            }
        }

        if (conflicts.items.isNotEmpty()) {
            // Resolved once per set of conflicts: an arrangement is a list of identifiers, and what
            // the user has to choose between is the names they see on their own screen.
            val names = remember(conflicts.items) { vault.entries().associate { it.id to it.name } }
            SectionCard(title = stringResource(Res.string.sync_conflict_heading)) {
                MessageLine(stringResource(Res.string.sync_result_needs_decision, conflicts.items.size))
                conflicts.items.forEach { conflict ->
                    ConflictCard(
                        conflict = conflict,
                        names = names,
                        onDecide = { takeIncoming ->
                            if (conflicts.decide(conflict.entryId, takeIncoming)) {
                                conflicts = conflicts.settled()
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
 * How long a receive waits for the other device's state.
 *
 * Both devices are at their screens for this, because that is the rule for every channel here;
 * the wait is long enough to fetch the state from an unlocked device by hand and short enough
 * that a device which is not coming leaves the screen usable.
 */
private const val LAN_RECEIVE_TIMEOUT_MS = 30_000L

/** The same wait in whole seconds, which is what the interface counts in. */
private val LAN_RECEIVE_SECONDS = (LAN_RECEIVE_TIMEOUT_MS / 1_000L).toInt()

/** How often the list of devices on the network is read while the lobby is open. */
private const val LAN_LIST_REFRESH_MS = 1_000L

/**
 * The disagreements waiting for a decision, and the session that reported them.
 *
 * The two travel together because a decision is applied through the session that received the
 * payload: the library keeps each session's own pending list, so a decision sent through
 * another session would change nothing. Which session that was — the code channel's or the
 * network one — is not something the cards below have to know.
 */
private class PendingConflicts(
    val items: List<SyncConflict>,
    private val pending: () -> List<SyncConflict>,
    /** Applies one decision, whose result the caller shows. */
    val decide: (entryId: String, takeIncoming: Boolean) -> Boolean,
) {
    /** The same source, with the decisions applied so far. */
    fun settled(): PendingConflicts = PendingConflicts(pending(), pending, decide)

    companion object {
        /** Nothing to decide, and a decision that says so. */
        val none = PendingConflicts(emptyList(), { emptyList() }) { _, _ -> false }
    }
}

/**
 * What one merge changed, shown where it was asked for.
 *
 * A merge runs either over a code the user carried or over the network, and the two are
 * separate sections of the screen: the summary belongs in the one the user acted on.
 */
@Composable
private fun MergeResult(outcome: SyncOutcome) {
    MessageLine(
        if (outcome.madeNoChange) {
            stringResource(Res.string.sync_result_nothing_new)
        } else {
            stringResource(
                Res.string.sync_result_summary,
                outcome.added,
                outcome.tombstonesAdded,
                outcome.deletionsApplied,
                outcome.unchanged,
            )
        },
        isError = false,
    )
}

/**
 * One device the lobby has heard, as one of the choices the user picks from.
 *
 * A device that said goodbye is listed too, and so is one whose announcement carried no
 * address: "switched off" and "cannot be dialled" are different facts, and dropping either
 * would make the device look like it had never been there.
 */
@Composable
private fun LanPeerChoice(peer: LanPeer, selected: Boolean, onSelect: () -> Unit) {
    val notes =
        buildList {
            if (!peer.here) add(stringResource(Res.string.sync_lan_peer_left))
            if (peer.socket == null) add(stringResource(Res.string.sync_lan_peer_unreachable))
        }

    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(shortDeviceId(peer.deviceId), style = MaterialTheme.typography.bodyMedium)
            if (notes.isNotEmpty()) Hint(notes.joinToString(" · "))
        }
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
private fun ConflictCard(
    conflict: SyncConflict,
    names: Map<String, String>,
    onDecide: (Boolean) -> Unit,
) {
    val ordering = conflict.ordering

    SectionCard {
        Text(
            when (conflict.kind) {
                SyncConflictKind.EntryMismatch -> stringResource(Res.string.sync_conflict_entry_mismatch)
                SyncConflictKind.PeerDeleted -> stringResource(Res.string.sync_conflict_peer_deleted)
                SyncConflictKind.LocalDeleted -> stringResource(Res.string.sync_conflict_local_deleted)
                SyncConflictKind.OrderingMismatch ->
                    stringResource(
                        Res.string.sync_conflict_ordering,
                        if (ordering?.top == true) {
                            stringResource(Res.string.sync_conflict_ordering_top)
                        } else {
                            ordering?.localName.orEmpty()
                        },
                    )
                SyncConflictKind.Unknown -> stringResource(Res.string.sync_conflict_heading)
            },
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )

        if (ordering != null) {
            // Both arrangements are shown, each named by the device it came from. Which list is
            // which is the whole question, and "local" and "incoming" are not answers a user can
            // work with.
            Hint(stringResource(Res.string.sync_conflict_ordering_hint))
            if (!ordering.top && ordering.localName != ordering.incomingName) {
                Hint(
                    stringResource(
                        Res.string.sync_conflict_ordering_renamed,
                        ordering.localName,
                        ordering.incomingName,
                    ),
                )
            }
            ArrangementRow(stringResource(Res.string.sync_conflict_ordering_local), ordering.local, names)
            ArrangementRow(stringResource(Res.string.sync_conflict_ordering_incoming), ordering.incoming, names)
        } else {
            if (conflict.kind == SyncConflictKind.PeerDeleted && conflict.localIsNewer) {
                Hint(stringResource(Res.string.sync_conflict_local_is_newer))
            }

            SideRow(stringResource(Res.string.sync_conflict_side_local), conflict.local)
            SideRow(stringResource(Res.string.sync_conflict_side_incoming), conflict.incoming)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            OutlinedButton(onClick = { onDecide(false) }) {
                Text(
                    when (conflict.kind) {
                        SyncConflictKind.PeerDeleted -> stringResource(Res.string.action_keep_entry)
                        SyncConflictKind.LocalDeleted -> stringResource(Res.string.action_keep_deleted)
                        SyncConflictKind.OrderingMismatch -> stringResource(Res.string.action_use_local_order)
                        else -> stringResource(Res.string.action_keep_local)
                    },
                )
            }
            Button(onClick = { onDecide(true) }) {
                Text(
                    when (conflict.kind) {
                        SyncConflictKind.PeerDeleted -> stringResource(Res.string.action_accept_deletion)
                        SyncConflictKind.LocalDeleted -> stringResource(Res.string.action_restore_entry)
                        SyncConflictKind.OrderingMismatch -> stringResource(Res.string.action_use_incoming_order)
                        else -> stringResource(Res.string.action_take_incoming)
                    },
                )
            }
        }
    }
}

/**
 * One device's arrangement of a group, in order.
 *
 * The entries are named rather than numbered by their identifiers: what the user is choosing
 * between is the order they see on their own screen, and an identifier is not something they have
 * ever seen. An entry that is in the arrangement but not in this vault - the peer's copy of a
 * group can name one this device has not received - is shown by its position, which is at least
 * what the arrangement is about.
 */
@Composable
private fun ArrangementRow(label: String, ids: List<String>, names: Map<String, String>) {
    Column {
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
        if (ids.isEmpty()) {
            Text(stringResource(Res.string.sync_conflict_ordering_empty))
        } else {
            ids.forEachIndexed { index, id ->
                Text("${index + 1}. ${names[id] ?: shortDeviceId(id)}")
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
