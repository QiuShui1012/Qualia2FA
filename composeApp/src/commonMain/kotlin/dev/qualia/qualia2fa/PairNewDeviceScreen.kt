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

/**
 * The side that already holds the account, driven by text.
 *
 * It shows a payload for the other device to read, takes that device's request, and hands
 * back a reply. Authorising is the moment the master key is committed to the session, so
 * the requester's identity is shown next to it and the reply is only handed over when the
 * user asks for it: until the reply reaches the other device, nothing has been given away.
 *
 * The text here is what a QR code will carry once the code channel reaches the screen.
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

    if (host == null) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("配对新设备", style = MaterialTheme.typography.headlineSmall)
            Text("无法开始配对：本机没有可用的平台密钥库。", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onDone) { Text("返回") }
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("配对新设备", style = MaterialTheme.typography.headlineSmall)
        Text(
            "把下面这段配对码交给新设备（只在这一步有效）。它读完之后会给你一段请求。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(host.payload, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            clipboard.setText(AnnotatedString(host.payload))
            message = "已复制。"
        }) {
            Text("复制配对码")
        }

        OutlinedTextField(
            value = requestText,
            onValueChange = { requestText = it },
            label = { Text("新设备的请求") },
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                val reply = host.replyTo(requestText.trim(), vault)
                if (reply == null) {
                    message = "这段请求不被接受：它可能不是这次配对的请求。"
                } else {
                    replyText = reply
                    message = null
                }
            },
            enabled = requestText.isNotBlank() && replyText == null,
        ) {
            Text("授权这台设备（把主密钥交给本次会话）")
        }

        replyText?.let { reply ->
            val requester = host.joinerDeviceId
            Text(
                if (requester == null) {
                    "已授权。把下面这段应答交给新设备即可完成。"
                } else {
                    "请求方身份：$requester。把下面这段应答交给它即可完成；在交出去之前，什么都还没有给它。"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(reply, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(reply))
                message = "已复制。"
            }) {
                Text("复制应答")
            }
        }

        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = onDone) { Text("完成") }
    }
}
