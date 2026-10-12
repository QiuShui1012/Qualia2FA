package dev.qualia.qualia2fa

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/**
 * The desktop entry point.
 *
 * It wires the on-disk storage to the user interface and nothing else: every decision
 * lives in the shared code or in the Rust core.
 */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Qualia2FA") {
        App(FileVaultStorage())
    }
}
