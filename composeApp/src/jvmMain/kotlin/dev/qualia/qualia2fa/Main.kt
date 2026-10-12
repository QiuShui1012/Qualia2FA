package dev.qualia.qualia2fa

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

/**
 * The desktop entry point.
 *
 * It wires the on-disk storage to the user interface, watches the window focus, and
 * nothing else: every decision lives in the shared code or in the Rust core.
 *
 * Losing focus locks the vault. The window is the only place that can know about it, so
 * it counts the events and the interface reacts.
 */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Qualia2FA") {
        var blurCount by remember { mutableStateOf(0) }

        val window = window
        androidx.compose.runtime.DisposableEffect(window) {
            val listener =
                object : WindowFocusListener {
                    override fun windowGainedFocus(event: WindowEvent?) = Unit

                    override fun windowLostFocus(event: WindowEvent?) {
                        blurCount += 1
                    }
                }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }

        App(storage = FileVaultStorage(), blurCount = blurCount)
    }
}
