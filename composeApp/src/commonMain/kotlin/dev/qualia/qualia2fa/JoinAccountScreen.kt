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
 * The side that is joining an account, driven by text.
 *
 * Three steps, and the text that moves between them is exactly what a QR code will carry
 * once the code channel is wired to the screen: paste the host's payload, hand the request
 * back, paste the host's reply. Keeping the flow before the pixels means the two can be
 * swapped independently.
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

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("登录到已有账号", style = MaterialTheme.typography.headlineSmall)
        Text(
            "在一台已经解锁的设备上选择「配对新设备」，把那里显示的配对码贴到下面。",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = payload,
            onValueChange = { payload = it },
            label = { Text("配对码（来自已解锁的设备）") },
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                val started = Qualia.joinPairing(payload.trim(), DEVICE_LABEL)
                if (started == null) {
                    message = "这不是一个可用的配对码，或者本机没有可用的平台密钥库。"
                } else {
                    val request = started.requestText()
                    if (request == null) {
                        message = "无法生成请求：平台密钥库拒绝了这次操作。"
                    } else {
                        joiner = started
                        requestText = request
                        message = null
                    }
                }
            },
            enabled = payload.isNotBlank() && joiner == null,
        ) {
            Text("生成请求")
        }

        requestText?.let { request ->
            Text("把下面这段交回给那台设备：", style = MaterialTheme.typography.bodyMedium)
            Text(request, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(request))
                message = "已复制。"
            }) {
                Text("复制请求")
            }

            OutlinedTextField(
                value = replyText,
                onValueChange = { replyText = it },
                label = { Text("应答（来自那台设备）") },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    val current = joiner
                    val joined = current?.completeText(replyText.trim(), storage.masterKeyStore)
                    if (joined == null) {
                        message = "应答没有通过验证：可能不是这次配对的应答，或者口令/会话对不上。"
                    } else {
                        val data = joined.serialize()
                        if (data == null) {
                            message = "加入成功，但保险库无法序列化。"
                        } else {
                            storage.saveVault(data)
                            onJoined(joined)
                        }
                    }
                },
                enabled = replyText.isNotBlank(),
            ) {
                Text("完成登录")
            }
        }

        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = onCancel) { Text("返回") }
    }
}
