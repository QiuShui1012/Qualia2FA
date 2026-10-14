package dev.qualia.qualia2fa

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.dialog_pick_image
import dev.qualia.qualia2fa.resources.error_no_platform_key_store
import dev.qualia.qualia2fa.resources.error_serialize_failed
import dev.qualia.qualia2fa.resources.filter_image_files
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Where the application is.
 *
 * A value rather than a set of flags: "loading", "at the login screen", "logging in" and
 * "inside the main screens" are mutually exclusive, and a state that says so cannot end up
 * showing two of them.
 */
private sealed interface Phase {
    /** Working out whether this device has a vault, and opening it if it does. */
    data object Loading : Phase

    /**
     * The login screen, in the state that matches this device.
     *
     * [reason] is why the vault is locked, when the user is looking at a lock rather than at
     * a first run.
     */
    data class Login(val mode: LoginMode, val reason: Status?) : Phase

    /** Logging in on a new device: the pairing handshake, without navigation. */
    data object Joining : Phase

    /** Inside the main screens, on [tab]. */
    data class Main(val tab: Tab) : Phase
}

/**
 * The application window.
 *
 * It owns the two things every screen depends on: **which language** everything is read in,
 * and **whether the vault is open**. Everything else belongs to the screens.
 *
 * # Locking
 *
 * Two things lock the vault: [SESSION_TIMEOUT_SECONDS] without the phase changing, and the
 * user asking for it from the settings. Locking drops the only reference to the key material,
 * which the Rust side wipes when it is dropped, and clears the clipboard. Unlocking reads the
 * master key out of the platform key store again — there is no password to type, the store
 * *is* the protection.
 *
 * Losing focus deliberately does **not** lock: the file dialog is a window of its own, so a
 * lock on focus loss fires while the user is still choosing the image to read a QR code out
 * of, which is one of the normal ways to add an entry.
 *
 * # The language wraps the screens, not the vault
 *
 * The vault state lives **outside** the key that a language change recreates: switching the
 * language must not lock the vault, read the master key again, or restart the timeout.
 * Everything below the key is rebuilt, so a screen's transient messages are dropped as well —
 * they were resolved in the language that is no longer in effect.
 */
@Composable
fun App(storage: VaultStorage, settings: SettingsStore) {
    var language by remember { mutableStateOf(settings.language()) }
    var phase by remember { mutableStateOf<Phase>(Phase.Loading) }
    var vault by remember { mutableStateOf<Vault?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    // Unlocking again from the login screen starts the same read the window started with, and
    // that read waits for the loading screen to have been up long enough: it is a coroutine.
    val scope = rememberCoroutineScope()

    val selectLanguage: (AppLanguage) -> Unit = { chosen ->
        language = chosen
        settings.setLanguage(chosen)
    }

    // Resolved here because the callbacks below cannot read a resource; the messages they
    // produce are the ones that outlive a screen change.
    val serializeFailed = stringResource(Res.string.error_serialize_failed)
    val noKeyStore = stringResource(Res.string.error_no_platform_key_store)

    val picker = rememberFilePicker()
    val camera = hasCamera()
    val pickImageTitle = stringResource(Res.string.dialog_pick_image)
    val imageFilesLabel = stringResource(Res.string.filter_image_files)

    /** The code being asked for, while the scanner is open. */
    var scanning by remember { mutableStateOf<ScanRequest?>(null) }

    /**
     * The way in that every screen is given.
     *
     * A device with a camera is shown the scanner, because that is the way in that needs
     * nothing prepared in advance; a device without one goes straight to picking an image,
     * since a screen holding nothing but a black rectangle would be a step in the way.
     */
    val scanner =
        remember(picker, camera, pickImageTitle, imageFilesLabel) {
            CodeScanner { request ->
                if (camera) {
                    scanning = request
                } else {
                    picker.readCodeFromImage(
                        dialogTitle = pickImageTitle,
                        imageFilesLabel = imageFilesLabel,
                        unreadable = request.unreadable,
                        noCode = request.noCode,
                        onText = request.onText,
                        onMessage = request.onMessage,
                    )
                }
            }
        }

    /**
     * Reads the vault this device already has, and goes wherever that leads.
     *
     * The loading screen stays up for at least [LOADING_MINIMUM], whether there was anything
     * to wait for or not: opening a vault is a key store access and a file read, and on a
     * warm device both are over before the first frame is drawn. A screen that appears and
     * vanishes inside a frame reads as a flicker rather than as progress.
     */
    suspend fun openExisting() {
        phase = Phase.Loading
        val shown = TimeSource.Monotonic.markNow()

        val store = storage.masterKeyStore
        val data = storage.loadVault()
        val hasKey = Qualia.hasMasterKey(store)

        val target =
            when {
                // Nothing here at all: the first run. This is the only state in which creating a
                // vault is safe, because there is no master key to replace.
                data == null && !hasKey -> {
                    vault = null
                    Phase.Login(LoginMode.FirstRun, null)
                }

                // A vault file whose master key is gone. The key store's entry can be lost — on
                // Android the wrapping key is not backed up and anything that resets the device's
                // credentials takes it with them — and what is left can never be opened again.
                // Reporting the first run here would invite the user to create a vault over the
                // one they still have.
                !hasKey -> {
                    vault = null
                    Phase.Login(LoginMode.Unreadable, null)
                }

                // A key without a vault file: the file is gone, so there is nothing to open and
                // nothing that could be unlocked.
                data == null -> {
                    vault = null
                    Phase.Login(LoginMode.VaultMissing, null)
                }

                else -> {
                    val opened = Qualia.openVault(data, store)
                    if (opened == null) {
                        vault = null
                        Phase.Login(LoginMode.Unreadable, null)
                    } else {
                        vault = opened
                        message = null
                        Phase.Main(Tab.TwoFactor)
                    }
                }
            }

        val remaining = LOADING_MINIMUM - shown.elapsedNow()
        if (remaining.isPositive()) delay(remaining)
        phase = target
    }

    /** Drops the key material and returns to the login screen, which now says why. */
    fun lock(reason: Status) {
        vault = null
        phase = Phase.Login(LoginMode.Locked, reason)
        clipboard.setText(AnnotatedString(""))
    }

    LaunchedEffect(Unit) { openExisting() }

    LaunchedEffect(phase) {
        if (phase !is Phase.Main) return@LaunchedEffect
        delay(SESSION_TIMEOUT_SECONDS * 1_000L)
        lock(Status.LockedAfterTimeout(SESSION_TIMEOUT_SECONDS))
    }

    // Read once per vault: opening the platform identity is a key store access, and a
    // hardware one on the machines that have it.
    val identity = remember(vault) { vault?.let { Qualia.deviceIdentity(DEVICE_LABEL) } }

    CompositionLocalProvider(
        LocalAppLocale provides language.tag,
        LocalCodeScanner provides scanner,
    ) {
        key(language) {
            QualiaTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (val current = phase) {
                        Phase.Loading -> LoadingScreen()

                        is Phase.Login ->
                            LoginScreen(
                                mode = current.mode,
                                reason = current.reason,
                                hardwareBacked = storage.masterKeyStore.isHardwareBacked,
                                message = message,
                                language = language,
                                onSelectLanguage = selectLanguage,
                                onCreateVault = {
                                    val store = storage.masterKeyStore
                                    val registration = Qualia.register(store, DEVICE_LABEL)
                                    if (registration != null) {
                                        val data = registration.vault.serialize()
                                        if (data == null) {
                                            message = serializeFailed
                                        } else {
                                            storage.saveVault(data)
                                            vault = registration.vault
                                            message = null
                                            phase = Phase.Main(Tab.TwoFactor)
                                        }
                                    } else {
                                        // The platform key store holds the device identity, and
                                        // without it this device cannot pair or sync. A local
                                        // vault is still useful, but it is a different thing and
                                        // the settings screen says so.
                                        val created = Qualia.createLocalVault(store)
                                        val data = created?.serialize()
                                        if (created == null || data == null) {
                                            message = noKeyStore
                                        } else {
                                            storage.saveVault(data)
                                            vault = created
                                            message = null
                                            phase = Phase.Main(Tab.TwoFactor)
                                        }
                                    }
                                },
                                onJoin = {
                                    message = null
                                    phase = Phase.Joining
                                },
                                onUnlock = { scope.launch { openExisting() } },
                            )

                        Phase.Joining ->
                            JoinAccountScreen(
                                storage = storage,
                                onJoined = { joined ->
                                    vault = joined
                                    message = null
                                    // The account's entries arrive by a sync rather than by the
                                    // login itself, but that is the user's next step, not their
                                    // destination: the device lands where a device always lands,
                                    // and asks for the sync from there.
                                    phase = Phase.Main(Tab.TwoFactor)
                                },
                                onCancel = {
                                    vault = null
                                    phase = Phase.Login(LoginMode.FirstRun, null)
                                },
                            )

                        is Phase.Main -> {
                            val currentVault = vault
                            if (currentVault == null) {
                                // The vault is dropped only by locking, which also changes the
                                // phase; this is unreachable and says so rather than showing an
                                // empty screen.
                                phase = Phase.Login(LoginMode.Locked, null)
                            } else {
                                MainScreen(
                                    tab = current.tab,
                                    onSelectTab = { selected -> phase = Phase.Main(selected) },
                                ) {
                                    when (current.tab) {
                                        Tab.TwoFactor ->
                                            TwoFactorScreen(
                                                vault = currentVault,
                                                onPersist = { currentVault.serialize()?.let(storage::saveVault) },
                                            )

                                        Tab.Sync ->
                                            SyncScreen(
                                                vault = currentVault,
                                                deviceId = identity?.id,
                                                onChanged = { currentVault.serialize()?.let(storage::saveVault) },
                                            )

                                        Tab.Settings ->
                                            SettingsScreen(
                                                vault = currentVault,
                                                storage = storage,
                                                identity = identity,
                                                language = language,
                                                onSelectLanguage = selectLanguage,
                                                onLock = { lock(Status.Locked) },
                                                onImported = { imported ->
                                                    // The account changed under this device: the
                                                    // file on disk belongs to the old one, so the
                                                    // new vault is written before anything reads it.
                                                    imported.serialize()?.let(storage::saveVault)
                                                    vault = imported
                                                    phase = Phase.Main(Tab.TwoFactor)
                                                },
                                                onReset = {
                                                    // The reset removes what this device holds, so
                                                    // the phase goes back to the first run: there
                                                    // is no vault to show, and the login screen is
                                                    // where a new one is made.
                                                    storage.reset()
                                                    vault = null
                                                    message = null
                                                    phase = Phase.Login(LoginMode.FirstRun, null)
                                                },
                                            )
                                    }
                                }
                            }
                        }
                    }

                    // The scanner covers the screen that asked for a code, rather than
                    // replacing it: what that screen is holding — a half-typed code, a
                    // message — is still there when the scanner closes.
                    scanning?.let { request ->
                        ScanScreen(
                            request = request,
                            picker = picker,
                            onDismiss = { scanning = null },
                        )
                    }
                }
            }
        }
    }
}

/** How long an open vault stays open with no way for the user to extend it. */
private const val SESSION_TIMEOUT_SECONDS = 900

/**
 * How long the loading screen stays up, at least.
 *
 * Long enough to be read as a screen rather than as a flicker, and short enough not to be a
 * wait of its own.
 */
private val LOADING_MINIMUM = 1.seconds

/**
 * The label this device's identity is kept under in the platform key store.
 *
 * A device has one identity, and it is the same one whatever label is used to look it up;
 * the label exists so that the store's entries can be told apart from other applications'.
 */
internal const val DEVICE_LABEL = "qualia2fa-device"
