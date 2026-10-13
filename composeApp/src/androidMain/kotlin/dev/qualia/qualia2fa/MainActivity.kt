package dev.qualia.qualia2fa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import java.nio.file.Path

/**
 * The Android entry point.
 *
 * It wires the storage and the preferences to the same user interface the desktop build
 * shows, and nothing else: every decision lives in the shared code or in the Rust core.
 *
 * # Where the files live
 *
 * [android.content.Context.getFilesDir] is the application's own private directory: no other
 * application can read it, and it is removed when this one is uninstalled. The master key
 * itself is wrapped by a key in the platform keystore, which never leaves it.
 *
 * # The window
 *
 * The application draws behind the system bars, and the composition is inset by the area they
 * and the display's cutout occupy. The window itself is the plate colour, so what shows at the
 * edges is the application's own background rather than a flash of the platform's.
 *
 * The keyboard is deliberately **not** part of that inset. The activity asks the platform to
 * resize it when the keyboard appears (`adjustResize` in the manifest), so the composition is
 * handed a smaller window and everything in it — including an open dialog — moves up on its
 * own. Layering the keyboard's insets on top of that would say the same thing twice, and it
 * also breaks the dialogs: with the keyboard's insets in the layout, Android's dialog windows
 * are dismissed the moment a text field is touched, taking the typed input with them. Only the
 * system bars and the cutout are therefore consumed here.
 *
 * # Configuration changes
 *
 * The activity handles orientation and screen size itself rather than being recreated. The
 * interface is built for a window that changes shape, and recreating the activity would take
 * the vault's in-memory state with it while the user is reading a code.
 *
 * # Why the Rust library is loaded here
 *
 * The device identity is a key in the platform keystore, and the platform keystore is reached
 * from Rust over JNI — so that code needs a `JavaVM`, and it takes one in `JNI_OnLoad`, which
 * the runtime calls when the **JVM** loads the library.
 *
 * JNA, which the generated bindings use to reach everything else, does not load it that way:
 * it opens the file itself, and a library opened that way never sees `JNI_OnLoad`. Without
 * this line the device identity reports that it was loaded without a Java VM, which is true,
 * and the vault can neither pair nor sync.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        System.loadLibrary("qualia2fa_ffi")

        val dataDirectory: Path = filesDir.toPath()
        setContent {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
            ) {
                App(
                    storage =
                        FileVaultStorage(
                            dataDirectory = dataDirectory,
                            masterKeyStore =
                                AndroidKeystoreMasterKeyStore(
                                    path = dataDirectory.resolve("master.key"),
                                    context = this@MainActivity,
                                ),
                        ),
                    settings = FileSettingsStore(dataDirectory.resolve("settings.properties")),
                )
            }
        }
    }
}
