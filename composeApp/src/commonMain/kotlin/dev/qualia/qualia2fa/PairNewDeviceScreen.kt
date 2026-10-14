package dev.qualia.qualia2fa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.action_authorize_device
import dev.qualia.qualia2fa.resources.action_done
import dev.qualia.qualia2fa.resources.action_pair_new_device
import dev.qualia.qualia2fa.resources.action_scan_request
import dev.qualia.qualia2fa.resources.error_image_has_no_request
import dev.qualia.qualia2fa.resources.error_image_unreadable
import dev.qualia.qualia2fa.resources.field_joiner_request
import dev.qualia.qualia2fa.resources.message_copied
import dev.qualia.qualia2fa.resources.message_request_read
import dev.qualia.qualia2fa.resources.pair_authorized
import dev.qualia.qualia2fa.resources.pair_authorized_with_requester
import dev.qualia.qualia2fa.resources.pair_error_no_key_store
import dev.qualia.qualia2fa.resources.pair_error_request_rejected
import dev.qualia.qualia2fa.resources.pair_intro
import org.jetbrains.compose.resources.stringResource

/**
 * The side that already holds the account, in the sync screen's language.
 *
 * It shows a payload for the other device to read, takes that device's request, and hands
 * back a reply. Authorizing is the moment the master key is committed to the session, so the
 * requester's identity is shown next to it and the reply is only handed over when the user
 * asks for it: until the reply reaches the other device, nothing has been given away.
 */
@Composable
fun PairNewDeviceScreen(
    vault: Vault,
    onDone: () -> Unit,
) {
    val host = remember { Qualia.beginPairing() }
    var requestText by remember { mutableStateOf("") }
    var replyText by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val copied = stringResource(Res.string.message_copied)
    val unreadableImage = stringResource(Res.string.error_image_unreadable)
    val noRequest = stringResource(Res.string.error_image_has_no_request)
    val requestRead = stringResource(Res.string.message_request_read)
    val requestRejected = stringResource(Res.string.pair_error_request_rejected)

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

    Column(
        modifier = Modifier.fillMaxSize().padding(Space.l).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        ScreenHeader(title = stringResource(Res.string.action_pair_new_device)) {
            TextButton(onClick = onDone) { Text(stringResource(Res.string.action_done)) }
        }
        Hint(stringResource(Res.string.pair_intro))

        SectionCard {
            CodeBlock(code = host.payload, onCopied = { message = copied })
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
