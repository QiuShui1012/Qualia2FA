package dev.qualia.qualia2fa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.action_back
import dev.qualia.qualia2fa.resources.action_complete_login
import dev.qualia.qualia2fa.resources.action_generate_request
import dev.qualia.qualia2fa.resources.action_join_existing_account
import dev.qualia.qualia2fa.resources.action_lan_request
import dev.qualia.qualia2fa.resources.action_scan_pairing_code
import dev.qualia.qualia2fa.resources.action_scan_reply
import dev.qualia.qualia2fa.resources.error_image_has_no_pairing_code
import dev.qualia.qualia2fa.resources.error_image_has_no_reply
import dev.qualia.qualia2fa.resources.error_image_unreadable
import dev.qualia.qualia2fa.resources.error_joined_but_not_serialized
import dev.qualia.qualia2fa.resources.field_pairing_code
import dev.qualia.qualia2fa.resources.field_reply
import dev.qualia.qualia2fa.resources.join_error_bad_code
import dev.qualia.qualia2fa.resources.join_error_reply_rejected
import dev.qualia.qualia2fa.resources.join_error_request_failed
import dev.qualia.qualia2fa.resources.join_hand_back_request
import dev.qualia.qualia2fa.resources.join_intro
import dev.qualia.qualia2fa.resources.join_lan_explainer
import dev.qualia.qualia2fa.resources.join_lan_failed
import dev.qualia.qualia2fa.resources.join_lan_looking
import dev.qualia.qualia2fa.resources.join_lan_no_device
import dev.qualia.qualia2fa.resources.join_lan_section
import dev.qualia.qualia2fa.resources.join_lan_timed_out
import dev.qualia.qualia2fa.resources.join_lan_unavailable
import dev.qualia.qualia2fa.resources.join_lan_waiting
import dev.qualia.qualia2fa.resources.lan_permission_refused
import dev.qualia.qualia2fa.resources.message_copied
import dev.qualia.qualia2fa.resources.message_pairing_code_read
import dev.qualia.qualia2fa.resources.message_reply_read
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.jetbrains.compose.resources.stringResource

/**
 * The joining side of a login: a new device that has no master key yet.
 *
 * The design asks for this flow to run on the sync screen **without navigation**, and that is
 * what it is: the same three code-carrying steps as a sync, in the same visual language, with
 * no navigation around it because the device is not in the account yet and the main screens
 * have nothing to show.
 *
 * The transfer itself cannot be a sync: a sync payload is encrypted with the master key, and
 * this device does not have it. What travels here is the key, through the pairing handshake —
 * one direction, host to joiner — protected by the out-of-band code the user carries.
 *
 * # The local network shortens the middle step
 *
 * A code that names the other device on the network lets this device send its request straight
 * there and wait for the reply, with nothing carried by hand. Every failure of that route —
 * the device is not on this network, it does not answer, the reply does not verify — falls
 * back to the route that always works: the request text on this screen, the reply pasted back
 * into the field below it.
 */
@Composable
fun JoinAccountScreen(
    storage: VaultStorage,
    onJoined: (Vault) -> Unit,
    onCancel: () -> Unit,
) {
    var payload by remember { mutableStateOf("") }
    var joiner by remember { mutableStateOf<PairingJoiner?>(null) }
    var requestText by remember { mutableStateOf<String?>(null) }
    var replyText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var lanStage by remember { mutableStateOf<LanStage?>(null) }

    val deviceId = remember(joiner) { if (joiner?.hostRecord == null) null else Qualia.deviceId(DEVICE_LABEL) }
    val multicast = rememberMulticastHold()
    val permission = rememberLanPermission()
    val scope = rememberCoroutineScope()

    val copied = stringResource(Res.string.message_copied)
    val unreadableImage = stringResource(Res.string.error_image_unreadable)
    val noPairingCode = stringResource(Res.string.error_image_has_no_pairing_code)
    val pairingCodeRead = stringResource(Res.string.message_pairing_code_read)
    val noReply = stringResource(Res.string.error_image_has_no_reply)
    val replyRead = stringResource(Res.string.message_reply_read)
    val badCode = stringResource(Res.string.join_error_bad_code)
    val requestFailed = stringResource(Res.string.join_error_request_failed)
    val replyRejected = stringResource(Res.string.join_error_reply_rejected)
    val notSerialized = stringResource(Res.string.error_joined_but_not_serialized)
    val lanRefused = stringResource(Res.string.lan_permission_refused)
    val lanUnavailable = stringResource(Res.string.join_lan_unavailable)
    val lanNoDevice = stringResource(Res.string.join_lan_no_device)
    val lanFailed = stringResource(Res.string.join_lan_failed)
    val lanTimedOut = stringResource(Res.string.join_lan_timed_out)

    /**
     * Sends the request to the device the code names, and finishes the login from its answer.
     *
     * This device announces itself first, because answering needs an address and an
     * announcement is the only thing that carries one: the reply can only reach a device the
     * other side has heard about.
     *
     * The manual route stays on screen throughout: this is the shortcut, and every way it can
     * fail ends with the text and the reply field, which is what the screen has always shown.
     */
    fun sendRequestOverLan() {
        val current = joiner ?: return
        val record = current.hostRecord ?: return
        val id = deviceId ?: return

        permission.request { allowed ->
            if (!allowed) {
                message = lanRefused
            } else {
                scope.launch {
                    lanStage = LanStage.Looking
                    message = null
                    var channel: LanChannel? = null
                    var lobby: LanLobby? = null
                    try {
                        val opened = Qualia.lanChannel(id)
                        if (opened == null) {
                            message = lanUnavailable
                            return@launch
                        }
                        channel = opened
                        multicast.acquire()

                        val found = opened.openLobby()
                        if (found == null) {
                            message = lanUnavailable
                            return@launch
                        }
                        lobby = found
                        found.announceNow()

                        val host = awaitHost(found, record)
                        if (host == null || !opened.remember(host)) {
                            message = lanNoDevice
                            return@launch
                        }

                        val request = current.request()
                        if (request == null || !opened.sendTo(host.deviceId, request)) {
                            message = lanFailed
                            return@launch
                        }

                        lanStage = LanStage.Waiting
                        val answer = opened.receive(LAN_REPLY_TIMEOUT_MS)
                        if (answer == null) {
                            message = lanTimedOut
                            return@launch
                        }

                        val joined = current.complete(answer.message, storage.masterKeyStore)
                        if (joined == null) {
                            message = replyRejected
                            return@launch
                        }
                        val data = joined.serialize()
                        if (data == null) {
                            message = notSerialized
                            return@launch
                        }
                        storage.saveVault(data)
                        onJoined(joined)
                    } finally {
                        lanStage = null
                        lobby?.close()
                        channel?.close()
                        multicast.release()
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(Space.l).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        ScreenHeader(title = stringResource(Res.string.action_join_existing_account)) {
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.action_back)) }
        }
        Hint(stringResource(Res.string.join_intro))

        SectionCard(title = stringResource(Res.string.field_pairing_code)) {
            CodeField(
                value = payload,
                onValueChange = { payload = it },
                label = stringResource(Res.string.field_pairing_code),
                scanLabel = stringResource(Res.string.action_scan_pairing_code),
                unreadable = unreadableImage,
                noCode = noPairingCode,
                onScanned = { scanned ->
                    // The code carries this application's envelope; what belongs in the
                    // field is what was put into it.
                    payload = Qualia.unwrapQrText(scanned) ?: scanned
                    message = pairingCodeRead
                },
                onMessage = { message = it },
            )
            Button(
                onClick = {
                    val started = Qualia.joinPairing(payload.trim(), DEVICE_LABEL)
                    if (started == null) {
                        message = badCode
                    } else {
                        val request = started.requestText()
                        if (request == null) {
                            message = requestFailed
                        } else {
                            joiner = started
                            requestText = request
                            message = null
                        }
                    }
                },
                enabled = payload.isNotBlank() && joiner == null,
            ) {
                Text(stringResource(Res.string.action_generate_request))
            }
        }

        requestText?.let { request ->
            SectionCard(title = stringResource(Res.string.join_hand_back_request)) {
                CodeBlock(code = request, onCopied = { message = copied })
                CodeField(
                    value = replyText,
                    onValueChange = { replyText = it },
                    label = stringResource(Res.string.field_reply),
                    scanLabel = stringResource(Res.string.action_scan_reply),
                    unreadable = unreadableImage,
                    noCode = noReply,
                    onScanned = { scanned ->
                        replyText = Qualia.unwrapQrText(scanned) ?: scanned
                        message = replyRead
                    },
                    onMessage = { message = it },
                )
                Button(
                    onClick = {
                        val current = joiner
                        val joined = current?.completeText(replyText.trim(), storage.masterKeyStore)
                        if (joined == null) {
                            message = replyRejected
                        } else {
                            val data = joined.serialize()
                            if (data == null) {
                                message = notSerialized
                            } else {
                                storage.saveVault(data)
                                onJoined(joined)
                            }
                        }
                    },
                    enabled = replyText.isNotBlank(),
                ) {
                    Text(stringResource(Res.string.action_complete_login))
                }
            }

            if (joiner?.hostRecord != null) {
                SectionCard(title = stringResource(Res.string.join_lan_section)) {
                    Hint(stringResource(Res.string.join_lan_explainer))
                    Button(onClick = { sendRequestOverLan() }, enabled = lanStage == null) {
                        Text(stringResource(Res.string.action_lan_request))
                    }
                    when (lanStage) {
                        LanStage.Looking -> {
                            CircularProgressIndicator(modifier = Modifier.size(Space.xl))
                            Hint(stringResource(Res.string.join_lan_looking))
                        }

                        LanStage.Waiting -> {
                            CircularProgressIndicator(modifier = Modifier.size(Space.xl))
                            Hint(stringResource(Res.string.join_lan_waiting, LAN_REPLY_SECONDS))
                        }

                        null -> Unit
                    }
                }
            }
        }

        MessageLine(message)
    }
}

/** What the network route is doing, so that the screen can say where it is. */
private enum class LanStage {
    /** Looking for the device the code names. */
    Looking,

    /** Waiting for that device to confirm the request. */
    Waiting,
}

/** How long the reply is waited for once the request has been sent. */
private const val LAN_REPLY_TIMEOUT_MS = 60_000L

/** The same wait in whole seconds, which is what the interface counts in. */
private val LAN_REPLY_SECONDS = (LAN_REPLY_TIMEOUT_MS / 1_000L).toInt()

/** How long the device the code names is looked for on this network. */
private val LAN_DISCOVERY_TIMEOUT = 15.seconds

/** How often the lobby is asked while looking for it. */
private const val LAN_DISCOVERY_MS = 500L

/**
 * Waits for the device named by [record] to announce itself, or gives up.
 *
 * A record and an announcement carry the same bytes for the device that sent them, so this
 * lookup is what turns "the code names a device" into "that device is here, and this is where
 * it can be reached". A device that said goodbye, or one whose announcement arrived with no
 * source address, cannot be dialled, so the wait continues for as long as there is time.
 */
private suspend fun awaitHost(lobby: LanLobby, record: ByteArray): LanPeer? {
    val deadline = TimeSource.Monotonic.markNow() + LAN_DISCOVERY_TIMEOUT
    while (true) {
        val peer = lobby.peerWith(record)
        if (peer != null && peer.here && peer.socket != null) return peer
        if (deadline.hasPassedNow()) return null
        delay(LAN_DISCOVERY_MS)
    }
}
