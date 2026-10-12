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
import dev.qualia.qualia2fa.resources.action_back
import dev.qualia.qualia2fa.resources.action_complete_login
import dev.qualia.qualia2fa.resources.action_copy_request
import dev.qualia.qualia2fa.resources.action_generate_request
import dev.qualia.qualia2fa.resources.action_join_existing_account
import dev.qualia.qualia2fa.resources.action_read_pairing_code_from_image
import dev.qualia.qualia2fa.resources.action_read_reply_from_image
import dev.qualia.qualia2fa.resources.dialog_pick_image
import dev.qualia.qualia2fa.resources.error_image_has_no_pairing_code
import dev.qualia.qualia2fa.resources.error_image_has_no_reply
import dev.qualia.qualia2fa.resources.error_image_unreadable
import dev.qualia.qualia2fa.resources.error_joined_but_not_serialized
import dev.qualia.qualia2fa.resources.field_pairing_code
import dev.qualia.qualia2fa.resources.field_reply
import dev.qualia.qualia2fa.resources.filter_image_files
import dev.qualia.qualia2fa.resources.join_error_bad_code
import dev.qualia.qualia2fa.resources.join_error_reply_rejected
import dev.qualia.qualia2fa.resources.join_error_request_failed
import dev.qualia.qualia2fa.resources.join_hand_back_request
import dev.qualia.qualia2fa.resources.join_intro
import dev.qualia.qualia2fa.resources.message_copied
import dev.qualia.qualia2fa.resources.message_pairing_code_read
import dev.qualia.qualia2fa.resources.message_reply_read
import org.jetbrains.compose.resources.stringResource

/**
 * The side that is joining an account, driven by text.
 *
 * Three steps, and the text that moves between them is exactly what a QR code carries:
 * paste the host's payload, hand the request back, paste the host's reply. Keeping the flow
 * before the pixels means the two can be swapped independently.
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
    val clipboard = LocalClipboardManager.current

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
    val pickImageTitle = stringResource(Res.string.dialog_pick_image)
    val imageFilesLabel = stringResource(Res.string.filter_image_files)

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.action_join_existing_account), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(Res.string.join_intro), style = MaterialTheme.typography.bodyMedium)

        OutlinedTextField(
            value = payload,
            onValueChange = { payload = it },
            label = { Text(stringResource(Res.string.field_pairing_code)) },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedButton(onClick = {
            val image = pickGrayImage(pickImageTitle, imageFilesLabel)
            if (image == null) {
                message = unreadableImage
            } else {
                val read = Qualia.qrTextFromImage(image.pixels, image.width, image.height)
                if (read == null) {
                    message = noPairingCode
                } else {
                    payload = read
                    message = pairingCodeRead
                }
            }
        }) {
            Text(stringResource(Res.string.action_read_pairing_code_from_image))
        }

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

        requestText?.let { request ->
            Text(stringResource(Res.string.join_hand_back_request), style = MaterialTheme.typography.bodyMedium)
            // The other device reads this back; the text underneath is what it types or
            // pastes when scanning is not possible.
            remember(request) { Qualia.qrImage(request) }?.let { code ->
                QrCodeView(code)
            }
            Text(request, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(request))
                message = copied
            }) {
                Text(stringResource(Res.string.action_copy_request))
            }

            OutlinedTextField(
                value = replyText,
                onValueChange = { replyText = it },
                label = { Text(stringResource(Res.string.field_reply)) },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedButton(onClick = {
                val image = pickGrayImage(pickImageTitle, imageFilesLabel)
                if (image == null) {
                    message = unreadableImage
                } else {
                    val read = Qualia.qrTextFromImage(image.pixels, image.width, image.height)
                    if (read == null) {
                        message = noReply
                    } else {
                        replyText = read
                        message = replyRead
                    }
                }
            }) {
                Text(stringResource(Res.string.action_read_reply_from_image))
            }

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

        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = onCancel) { Text(stringResource(Res.string.action_back)) }
    }
}
