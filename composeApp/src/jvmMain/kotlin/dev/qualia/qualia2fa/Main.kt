package dev.qualia.qualia2fa

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.qualia2fa_logo
import org.jetbrains.compose.resources.painterResource

/**
 * The desktop entry point.
 *
 * It wires the on-disk storage and the preferences to the user interface, and nothing else:
 * every decision lives in the shared code or in the Rust core.
 *
 * # Focus loss does not lock the vault
 *
 * It used to. The problem is that the file dialog is a window of its own: choosing an image
 * takes the focus away from this window, so a vault locked on focus loss locks *before* the
 * user can pick the file. Reading a QR code out of an image is a normal way to add an entry,
 * so the lock was fighting the feature. What is left is the inactivity timeout, which no
 * dialog can defeat, and locking on demand from the settings.
 *
 * The window icon is the same mark the interface shows, so the taskbar entry, the Alt-Tab
 * switcher and the window itself are recognizably one application.
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Qualia2FA",
        icon = painterResource(Res.drawable.qualia2fa_logo),
    ) {
        App(
            storage = FileVaultStorage(AppPaths.dataDirectory),
            settings = remember { FileSettingsStore(AppPaths.settingsFile) },
        )
    }
}
