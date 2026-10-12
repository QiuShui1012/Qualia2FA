package dev.qualia.qualia2fa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The application window.
 *
 * It shows either the first-run choice or the entry list, depending on whether this
 * device already holds a master key and a vault.
 *
 * [blurCount] increases every time the window loses focus; any increase locks the vault,
 * because a two-factor code left on screen is a code somebody else can read. The vault is
 * also locked after [SESSION_TIMEOUT_SECONDS] regardless of activity, and locking drops
 * the only reference to the key material, which is wiped by the Rust side when it is
 * dropped.
 */
@Composable
fun App(storage: VaultStorage, blurCount: Int = 0) {
    MaterialTheme {
        var vault by remember { mutableStateOf<Vault?>(null) }
        var status by remember { mutableStateOf("正在打开本机保险库…") }
        val clipboard = LocalClipboardManager.current

        fun lock(reason: String) {
            vault = null
            status = reason
            clipboard.setText(AnnotatedString(""))
        }

        LaunchedEffect(Unit) {
            val store = storage.masterKeyStore
            val data = storage.loadVault()
            vault =
                when {
                    data == null -> null
                    !Qualia.hasMasterKey(store) -> null
                    else -> Qualia.openVault(data, store)
                }
            if (vault != null) {
                status = "已解锁"
            }
        }

        LaunchedEffect(blurCount) {
            if (blurCount > 0 && vault != null) {
                lock("窗口失去焦点，已锁定")
            }
        }

        LaunchedEffect(vault) {
            if (vault == null) return@LaunchedEffect
            delay(SESSION_TIMEOUT_SECONDS * 1_000L)
            lock("超过 $SESSION_TIMEOUT_SECONDS 秒未解锁，已锁定")
        }

        val current = vault
        if (current == null) {
            FirstRunScreen(
                storage = storage,
                status = status,
                onCreated = { created, detail ->
                    vault = created
                    status = detail
                },
            )
        } else {
            VaultScreen(
                vault = current,
                storage = storage,
                onLock = { lock("已锁定") },
            )
        }
    }
}

/** How long an unlocked vault stays unlocked with no way for the user to extend it. */
private const val SESSION_TIMEOUT_SECONDS = 900

/**
 * The label of this device's identity key pair in the platform key store.
 *
 * One label names one key pair for the lifetime of the device, so it is a constant rather
 * than something derived from user input.
 */
internal const val DEVICE_LABEL = "qualia2fa-device"

@Composable
private fun FirstRunScreen(
    storage: VaultStorage,
    status: String,
    onCreated: (Vault, String) -> Unit,
) {
    val store = storage.masterKeyStore
    var message by remember { mutableStateOf<String?>(null) }
    var joining by remember { mutableStateOf(false) }

    if (joining) {
        JoinAccountScreen(
            storage = storage,
            onJoined = { onCreated(it, "已通过配对登录") },
            onCancel = { joining = false },
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Qualia2FA", style = MaterialTheme.typography.headlineMedium)
        Text("核心库 ${Qualia.coreVersion()}（$status）", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(4.dp))

        Text(
            "这台设备上还没有保险库。创建之后，条目只保存在本机，验证码离线可算。",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (!store.isHardwareBacked) {
            Text(
                "注意：本机没有接上硬件密钥库，主密钥将写在一个只有你的操作系统账户能读的文件里。" +
                    "继续即表示你接受这一点；TPM 支持尚未接上。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(onClick = {
            val registration = Qualia.register(store, DEVICE_LABEL)
            if (registration != null) {
                val data = registration.vault.serialize()
                if (data == null) {
                    message = "创建失败：核心无法序列化保险库"
                } else {
                    storage.saveVault(data)
                    onCreated(registration.vault, "设备身份在 ${registration.backend} 里" +
                        if (registration.hardwareBacked) "（硬件保护）" else "（非硬件保护）")
                }
            } else {
                // The platform key store is what holds the device identity, and without it
                // this device cannot pair or sync. Creating a vault anyway is still useful,
                // but it is a different thing and the user is told so.
                val created = Qualia.createLocalVault(store)
                val data = created?.serialize()
                if (created == null || data == null) {
                    message = "创建失败：平台密钥库不可用，核心也拒绝了本机保险库"
                } else {
                    storage.saveVault(data)
                    onCreated(created, "本机保险库（无设备身份，不能配对或同步）")
                }
            }
        }) {
            Text("创建保险库")
        }

        OutlinedButton(onClick = { joining = true }) {
            Text("登录到已有账号")
        }

        Text(
            "登录会从一台已解锁的设备取得主密钥：那台设备显示配对码，本机用它生成请求，" +
                "对方确认后把应答交回来。主密钥由二维码这一层带外传递，不经过任何服务器。",
            style = MaterialTheme.typography.bodySmall,
        )

        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun VaultScreen(
    vault: Vault,
    storage: VaultStorage,
    onLock: () -> Unit,
) {
    var now by remember { mutableStateOf(currentTimeSeconds()) }
    var entries by remember { mutableStateOf(vault.entries()) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Entry?>(null) }
    var pairing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            now = currentTimeSeconds()
            delay(250)
        }
    }

    LaunchedEffect(copied) {
        val value = copied ?: return@LaunchedEffect
        delay(30_000)
        if (clipboard.getText()?.text == value) {
            clipboard.setText(AnnotatedString(""))
        }
        copied = null
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Qualia2FA", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(12.dp))
            Text("${entries.size} 条", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onLock) { Text("锁定") }
        }

        Text(
            "核心库 ${Qualia.coreVersion()}；主密钥存放在" +
                if (storage.masterKeyStore.isHardwareBacked) "硬件密钥库中。" else "本机文件里（非硬件保护）。",
            style = MaterialTheme.typography.bodySmall,
        )

        TextButton(onClick = { adding = true }) { Text("添加条目") }
        TextButton(onClick = { pairing = true }) { Text("配对新设备") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { it.id }) { entry ->
                EntryCard(
                    entry = entry,
                    code = vault.code(entry.id, now),
                    now = now,
                    onCopy = { code ->
                        clipboard.setText(AnnotatedString(code))
                        copied = code
                        message = "已复制；30 秒后自动清空剪贴板。"
                    },
                    onDelete = {
                        vault.removeEntry(entry.id, currentTimeSeconds())
                        vault.serialize()?.let(storage::saveVault)
                        entries = vault.entries()
                    },
                    onEdit = { editing = entry },
                )
            }
        }
    }

    if (pairing) {
        PairNewDeviceScreen(vault = vault, onDone = { pairing = false })
    }

    if (adding || editing != null) {
        val beingEdited = editing
        EntryDialog(
            initial = beingEdited,
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = { name, issuer, account, secret, digits, period ->
                val accepted =
                    if (beingEdited == null) {
                        vault.addEntry(
                            name = name,
                            issuer = issuer,
                            account = account,
                            secretBase32 = secret,
                            digits = digits,
                            periodSeconds = period,
                            note = "",
                            unixTime = currentTimeSeconds(),
                        ) != null
                    } else {
                        vault.updateEntry(
                            id = beingEdited.id,
                            name = name,
                            issuer = issuer,
                            account = account,
                            secretBase32 = secret,
                            digits = digits,
                            periodSeconds = period,
                            note = beingEdited.note,
                            unixTime = currentTimeSeconds(),
                        )
                    }

                adding = false
                editing = null
                if (!accepted) {
                    message = "没有保存：种子或参数不被接受（种子必须是合法 Base32）"
                } else {
                    vault.serialize()?.let(storage::saveVault)
                    entries = vault.entries()
                    message = null
                }
            },
        )
    }
}

@Composable
private fun EntryCard(
    entry: Entry,
    code: String?,
    now: Long,
    onCopy: (String) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    val period = entry.periodSeconds.coerceAtLeast(1)
    val remaining = period - (now % period)

    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                val subtitle = listOfNotNull(entry.issuer.takeIf { it.isNotBlank() }, entry.account.takeIf { it.isNotBlank() })
                if (subtitle.isNotEmpty()) {
                    Text(subtitle.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    text = code?.chunked(3)?.joinToString(" ") ?: "------",
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                )
                if (code != null && !entry.isCounterBased) {
                    Text("$remaining 秒后更换", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (code != null && !entry.isCounterBased) {
                CircularProgressIndicator(
                    progress = { remaining.toFloat() / period.toFloat() },
                    modifier = Modifier.width(36.dp),
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { code?.let(onCopy) }, enabled = code != null) { Text("复制") }
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

@Composable
private fun EntryDialog(
    initial: Entry?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, Int, Long) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var issuer by remember { mutableStateOf(initial?.issuer.orEmpty()) }
    var account by remember { mutableStateOf(initial?.account.orEmpty()) }
    var secret by remember { mutableStateOf(initial?.secretBase32.orEmpty()) }
    var digits by remember { mutableStateOf((initial?.digits ?: 6).toString()) }
    var period by remember { mutableStateOf((initial?.periodSeconds ?: 30L).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加条目" else "编辑条目") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") })
                OutlinedTextField(value = issuer, onValueChange = { issuer = it }, label = { Text("服务") })
                OutlinedTextField(value = account, onValueChange = { account = it }, label = { Text("账号") })
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text("种子（Base32）") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = digits,
                        onValueChange = { digits = it },
                        label = { Text("位数") },
                        singleLine = true,
                        modifier = Modifier.width(110.dp),
                    )
                    OutlinedTextField(
                        value = period,
                        onValueChange = { period = it },
                        label = { Text("周期（秒）") },
                        singleLine = true,
                        modifier = Modifier.width(140.dp),
                    )
                }
                TextButton(onClick = { secret = Qualia.newSecret() }) { Text("生成随机种子") }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        name.ifBlank { "未命名" },
                        issuer,
                        account,
                        secret.trim().uppercase(),
                        digits.toIntOrNull() ?: 6,
                        period.toLongOrNull() ?: 30L,
                    )
                },
                enabled = secret.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
