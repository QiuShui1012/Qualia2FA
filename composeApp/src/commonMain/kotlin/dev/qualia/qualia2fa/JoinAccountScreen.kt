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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.action_back
import dev.qualia.qualia2fa.resources.action_complete_login
import dev.qualia.qualia2fa.resources.action_copy_request
import dev.qualia.qualia2fa.resources.action_generate_request
import dev.qualia.qualia2fa.resources.action_join_existing_account
import dev.qualia.qualia2fa.resources.action_scan_pairing_code
import dev.qualia.qualia2fa.resources.action_scan_reply
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
    val scanner = LocalCodeScanner.current

    Column(
        modifier = Modifier.fillMaxSize().padding(Space.l).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        ScreenHeader(title = stringResource(Res.string.action_join_existing_account)) {
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.action_back)) }
        }
        Hint(stringResource(Res.string.join_intro))

        SectionCard(title = stringResource(Res.string.field_pairing_code)) {
            OutlinedTextField(
                value = payload,
                onValueChange = { payload = it },
                label = { Text(stringResource(Res.string.field_pairing_code)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = {
                scanner.readCode(
                    unreadable = unreadableImage,
                    noCode = noPairingCode,
                    onText = {
                        payload = it
                        message = pairingCodeRead
                    },
                    onMessage = { message = it },
                )
            }) {
                Text(stringResource(Res.string.action_scan_pairing_code))
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
        }

        requestText?.let { request ->
            SectionCard(title = stringResource(Res.string.join_hand_back_request)) {
                CodeBlock(code = request, onCopied = { message = copied })
                OutlinedTextField(
                    value = replyText,
                    onValueChange = { replyText = it },
                    label = { Text(stringResource(Res.string.field_reply)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = {
                    scanner.readCode(
                        unreadable = unreadableImage,
                        noCode = noReply,
                        onText = {
                            replyText = it
                            message = replyRead
                        },
                        onMessage = { message = it },
                    )
                }) {
                    Text(stringResource(Res.string.action_scan_reply))
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
        }

        MessageLine(message)
    }
}
