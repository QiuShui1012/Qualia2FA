package dev.qualia.qualia2fa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.action_authorize_device
import dev.qualia.qualia2fa.resources.action_done
import dev.qualia.qualia2fa.resources.action_pair_new_device
import dev.qualia.qualia2fa.resources.action_scan_request
import dev.qualia.qualia2fa.resources.error_image_has_no_request
import dev.qualia.qualia2fa.resources.error_image_unreadable
import dev.qualia.qualia2fa.resources.field_joiner_request
import dev.qualia.qualia2fa.resources.lan_permission_refused
import dev.qualia.qualia2fa.resources.message_copied
import dev.qualia.qualia2fa.resources.message_request_read
import dev.qualia.qualia2fa.resources.pair_authorized
import dev.qualia.qualia2fa.resources.pair_authorized_with_requester
import dev.qualia.qualia2fa.resources.pair_error_no_key_store
import dev.qualia.qualia2fa.resources.pair_error_request_rejected
import dev.qualia.qualia2fa.resources.pair_intro
import dev.qualia.qualia2fa.resources.pair_lan_enable
import dev.qualia.qualia2fa.resources.pair_lan_explainer
import dev.qualia.qualia2fa.resources.pair_lan_listening
import dev.qualia.qualia2fa.resources.pair_lan_no_identity
import dev.qualia.qualia2fa.resources.pair_lan_reply_sent
import dev.qualia.qualia2fa.resources.pair_lan_request_from
import dev.qualia.qualia2fa.resources.pair_lan_section
import dev.qualia.qualia2fa.resources.pair_lan_unavailable
import dev.qualia.qualia2fa.resources.pair_lan_window_over
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import org.jetbrains.compose.resources.stringResource

/**
 * The side that already holds the account, in the sync screen's language.
 *
 * It shows a payload for the other device to read, takes that device's request, and hands
 * back a reply. Authorizing is the moment the master key is committed to the session, so the
 * requester's identity is shown next to it and the reply is only handed over when the user
 * asks for it: until the reply reaches the other device, nothing has been given away.
 *
 * # The local network is an addition, not a replacement
 *
 * With it switched on, the code also names this device on the network, and the request comes
 * in by itself instead of being carried by hand. Everything else is the same flow over the
 * same input field, and with the switch off the screen is the one it has always been: the
 * payload alone, a field, and a reply to hand back.
 */
@Composable
fun PairNewDeviceScreen(
    vault: Vault,
    deviceId: String?,
    onDone: () -> Unit,
) {
    val host = remember { Qualia.beginPairing() }
    var requestText by remember { mutableStateOf("") }
    var replyText by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var lanOn by remember { mutableStateOf(false) }
    var lanChannel by remember { mutableStateOf<LanChannel?>(null) }
    var lanLobby by remember { mutableStateOf<LanLobby?>(null) }
    var lanSender by remember { mutableStateOf<String?>(null) }
    var lanReplySent by remember { mutableStateOf(false) }

    val copied = stringResource(Res.string.message_copied)
    val unreadableImage = stringResource(Res.string.error_image_unreadable)
    val noRequest = stringResource(Res.string.error_image_has_no_request)
    val requestRead = stringResource(Res.string.message_request_read)
    val requestRejected = stringResource(Res.string.pair_error_request_rejected)
    val lanUnavailable = stringResource(Res.string.pair_lan_unavailable)
    val lanNoIdentity = stringResource(Res.string.pair_lan_no_identity)
    val lanRefused = stringResource(Res.string.lan_permission_refused)
    val lanWindowOver = stringResource(Res.string.pair_lan_window_over)
    val multicast = rememberMulticastHold()
    val permission = rememberLanPermission()

    if (host == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            ScreenHeader(title = stringResource(Res.string.action_pair_new_device))
            MessageLine(stringResource(Res.string.pair_error_no_key_store))
            OutlinedButton(onClick = onDone) { Text(stringResource(Res.string.action_done)) }
        }
        return
    }

    val lanRecord = remember(lanChannel) { lanChannel?.record() }
    val code = if (lanRecord == null) host.payload else host.codeText(lanRecord)

    /** Opens an endpoint and its lobby, which is what being findable on the network costs. */
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
        lanOn = true
        message = null
    }

    /** Closes the endpoint and its lobby, and gives the multicast hold back. */
    fun closeLan() {
        lanLobby?.close()
        lanChannel?.close()
        multicast.release()
        lanLobby = null
        lanChannel = null
        lanSender = null
        lanReplySent = false
        lanOn = false
    }

    /**
     * Hands the reply back over the network, when that is where the request came from.
     *
     * A send that does not go through is not reported: the reply is on screen either way, and
     * what a failure means is that the text has to be carried by hand — which is the route the
     * screen describes before any of this is switched on.
     */
    fun replyOverLan(reply: String) {
        val sender = lanSender ?: return
        val channel = lanChannel ?: return
        val bytes = decodeFromText(reply) ?: return
        lanLobby?.rememberPeer(channel, sender)
        if (channel.sendTo(sender, bytes)) lanReplySent = true
    }

    LaunchedEffect(lanChannel) {
        val channel = lanChannel ?: return@LaunchedEffect
        val deadline = TimeSource.Monotonic.markNow() + LAN_REQUEST_WINDOW
        var arrived = false
        while (!arrived && !deadline.hasPassedNow() && requestText.isBlank() && replyText == null) {
            val taken = channel.receive(LAN_POLL_MS) ?: continue
            requestText = encodeToText(taken.message)
            lanSender = taken.sender
            lanReplySent = false
            message = null
            lanLobby?.rememberPeer(channel, taken.sender)
            arrived = true
        }

        if (!arrived) {
            closeLan()
            if (requestText.isBlank() && replyText == null) message = lanWindowOver
        }
    }

    DisposableEffect(Unit) {
        onDispose { closeLan() }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(Space.l).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        ScreenHeader(title = stringResource(Res.string.action_pair_new_device)) {
            TextButton(onClick = onDone) { Text(stringResource(Res.string.action_done)) }
        }
        Hint(stringResource(Res.string.pair_intro))

        SectionCard {
            CodeBlock(code = code, onCopied = { message = copied })
        }

        SectionCard(title = stringResource(Res.string.pair_lan_section)) {
            Hint(stringResource(Res.string.pair_lan_explainer))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                Switch(
                    checked = lanOn,
                    onCheckedChange = { wanted ->
                        if (!wanted) {
                            closeLan()
                            message = null
                        } else if (deviceId == null) {
                            message = lanNoIdentity
                        } else {
                            permission.request { allowed -> if (allowed) openLan() else message = lanRefused }
                        }
                    },
                )
                Text(stringResource(Res.string.pair_lan_enable))
            }
            if (lanOn) {
                Hint(stringResource(Res.string.pair_lan_listening))
            }
            lanSender?.let { sender ->
                MessageLine(stringResource(Res.string.pair_lan_request_from, shortDeviceId(sender)), isError = false)
            }
            if (lanReplySent) {
                MessageLine(stringResource(Res.string.pair_lan_reply_sent), isError = false)
            }
        }

        SectionCard(title = stringResource(Res.string.field_joiner_request)) {
            CodeField(
                value = requestText,
                onValueChange = { requestText = it },
                label = stringResource(Res.string.field_joiner_request),
                scanLabel = stringResource(Res.string.action_scan_request),
                unreadable = unreadableImage,
                noCode = noRequest,
                onScanned = { scanned ->
                    // The code carries this application's envelope; what belongs in the
                    // field is what was put into it.
                    requestText = Qualia.unwrapQrText(scanned) ?: scanned
                    message = requestRead
                },
                onMessage = { message = it },
            )
            Button(
                onClick = {
                    val reply = host.replyTo(requestText.trim(), vault)
                    if (reply == null) {
                        message = requestRejected
                    } else {
                        replyText = reply
                        message = null
                        replyOverLan(reply)
                    }
                },
                enabled = requestText.isNotBlank() && replyText == null,
            ) {
                Text(stringResource(Res.string.action_authorize_device))
            }
        }

        replyText?.let { reply ->
            val requester = host.joinerDeviceId
            SectionCard {
                Text(
                    if (requester == null) {
                        stringResource(Res.string.pair_authorized)
                    } else {
                        stringResource(Res.string.pair_authorized_with_requester, requester)
                    },
                )
                CodeBlock(code = reply, onCopied = { message = copied })
            }
        }

        MessageLine(message)
    }
}

/**
 * How long the switch waits for a request before it stops listening.
 *
 * The endpoint is opened by an explicit action and closes itself after this, so a screen left
 * open does not announce this device into the network indefinitely. A request that was typed
 * or read out of an image in the meantime belongs to the user's own route: the endpoint closes
 * all the same, but the message about the wait is left out rather than talking over them.
 */
private val LAN_REQUEST_WINDOW = 2.minutes

/** How long one attempt at taking a message waits, within [LAN_REQUEST_WINDOW]. */
private const val LAN_POLL_MS = 2_000L
