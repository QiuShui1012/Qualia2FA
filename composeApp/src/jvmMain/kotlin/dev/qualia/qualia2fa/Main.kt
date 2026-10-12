package dev.qualia.qualia2fa

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay

/**
 * The desktop entry point.
 *
 * It currently shows the Rust round trip only: the window is the M5 spike, not the
 * application. The register/login split, the entry list and the rest of the UI replace
 * this body as they land.
 */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Qualia2FA") {
        MaterialTheme {
            SpikeScreen()
        }
    }
}

@Composable
private fun SpikeScreen() {
    var secret by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf<String?>(null) }
    var secondsLeft by remember { mutableStateOf(0L) }
    var coreVersion by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        coreVersion = Qualia.coreVersion()
        secret = Qualia.newSecret()
    }

    LaunchedEffect(secret) {
        while (true) {
            val now = System.currentTimeMillis() / 1000
            code = secret?.let { Qualia.totp(it, now) }
            secondsLeft = 30 - (now % 30)
            delay(500)
        }
    }

    Column(Modifier.padding(24.dp)) {
        Text("Qualia2FA", style = MaterialTheme.typography.headlineMedium)
        Text("Rust core: ${coreVersion ?: "loading"}")

        Text(
            text = "Code: ${code ?: "----"}",
            style = MaterialTheme.typography.displaySmall,
        )
        Text("Next in ${secondsLeft}s")
        Text("Secret: ${secret ?: "loading"}")
    }
}
