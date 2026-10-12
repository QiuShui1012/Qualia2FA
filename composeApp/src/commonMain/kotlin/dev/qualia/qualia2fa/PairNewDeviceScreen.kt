package dev.qualia.qualia2fa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import dev.qualia.qualia2fa.resources.action_authorize_device
import dev.qualia.qualia2fa.resources.action_back
import dev.qualia.qualia2fa.resources.action_copy_pairing_code
import dev.qualia.qualia2fa.resources.action_copy_reply
import dev.qualia.qualia2fa.resources.action_done
import dev.qualia.qualia2fa.resources.action_pair_new_device
import dev.qualia.qualia2fa.resources.action_read_request_from_image
import dev.qualia.qualia2fa.resources.dialog_pick_image
import dev.qualia.qualia2fa.resources.error_image_has_no_request
import dev.qualia.qualia2fa.resources.error_image_unreadable
import dev.qualia.qualia2fa.resources.field_joiner_request
import dev.qualia.qualia2fa.resources.filter_image_files
import dev.qualia.qualia2fa.resources.message_copied
import dev.qualia.qualia2fa.resources.message_request_read
import dev.qualia.qualia2fa.resources.pair_authorized
import dev.qualia.qualia2fa.resources.pair_authorized_with_requester
import dev.qualia.qualia2fa.resources.pair_error_no_key_store
import dev.qualia.qualia2fa.resources.pair_error_request_rejected
import dev.qualia.qualia2fa.resources.pair_intro
import org.jetbrains.compose.resources.stringResource

/**
 * The side that already holds the account, driven by text.
 *
 * It shows a payload for the other device to read, takes that device's request, and hands
 * back a reply. Authorising is the moment the master key is committed to the session, so
 * the requester's identity is shown next to it and the reply is only handed over when the
 * user asks for it: until the reply reaches the other device, nothing has been given away.
 *
 * The text here is what a QR code carries, and the text stays underneath the code: a code
 * that is too dense to scan must still leave the user a way to continue.
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
    val clipboard = LocalClipboardManager.current

    val copied = stringResource(Res.string.message_copied)
    val unreadableImage = stringResource(Res.string.error_image_unreadable)
    val noRequest = stringResource(Res.string.error_image_has_no_request)
    val requestRead = stringResource(Res.string.message_request_read)
    val requestRejected = stringResource(Res.string.pair_error_request_rejected)
    val pickImageTitle = stringResource(Res.string.dialog_pick_image)
    val imageFilesLabel = stringResource(Res.string.filter_image_files)

    if (host == null) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.action_pair_new_device), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(Res.string.pair_error_no_key_store), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onDone) { Text(stringResource(Res.string.action_back)) }
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.action_pair_new_device), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(Res.string.pair_intro), style = MaterialTheme.typography.bodyMedium)
        // The code is shown when it fits; the text stays underneath either way, because a
        // code that is too dense to scan must still leave the user a way to continue.
        remember(host.payload) { Qualia.qrImage(host.payload) }?.let { code ->
            QrCodeView(code)
        }
        Text(host.payload, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            clipboard.setText(AnnotatedString(host.payload))
            message = copied
        }) {
            Text(stringResource(Res.string.action_copy_pairing_code))
        }

        OutlinedTextField(
            value = requestText,
            onValueChange = { requestText = it },
            label = { Text(stringResource(Res.string.field_joiner_request)) },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedButton(onClick = {
            val image = pickGrayImage(pickImageTitle, imageFilesLabel)
            if (image == null) {
                message = unreadableImage
            } else {
                val read = Qualia.qrTextFromImage(image.pixels, image.width, image.height)
                if (read == null) {
                    message = noRequest
                } else {
                    requestText = read
                    message = requestRead
                }
            }
        }) {
            Text(stringResource(Res.string.action_read_request_from_image))
        }

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

        replyText?.let { reply ->
            val requester = host.joinerDeviceId
            Text(
                if (requester == null) {
                    stringResource(Res.string.pair_authorized)
                } else {
                    stringResource(Res.string.pair_authorized_with_requester, requester)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            remember(reply) { Qualia.qrImage(reply) }?.let { code ->
                QrCodeView(code)
            }
            Text(reply, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(reply))
                message = copied
            }) {
                Text(stringResource(Res.string.action_copy_reply))
            }
        }

        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = onDone) { Text(stringResource(Res.string.action_done)) }
    }
}
