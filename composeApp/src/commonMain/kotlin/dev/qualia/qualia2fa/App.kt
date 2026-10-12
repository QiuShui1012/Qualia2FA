package dev.qualia.qualia2fa

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.error_no_platform_key_store
import dev.qualia.qualia2fa.resources.error_serialize_failed
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

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

    val selectLanguage: (AppLanguage) -> Unit = { chosen ->
        language = chosen
        settings.setLanguage(chosen)
    }

    // Resolved here because the callbacks below cannot read a resource; the messages they
    // produce are the ones that outlive a screen change.
    val serializeFailed = stringResource(Res.string.error_serialize_failed)
    val noKeyStore = stringResource(Res.string.error_no_platform_key_store)

    /** Reads the vault this device already has, and goes wherever that leads. */
    fun openExisting() {
        phase = Phase.Loading
        val store = storage.masterKeyStore
        val data = storage.loadVault()
        when {
            // Nothing here at all: the first run. This is the only state in which creating a
            // vault is safe, because there is no master key to replace.
            !Qualia.hasMasterKey(store) -> {
                vault = null
                phase = Phase.Login(LoginMode.FirstRun, null)
            }

            // A key without a vault file: the file is gone, so there is nothing to open and
            // nothing that could be unlocked.
            data == null -> {
                vault = null
                phase = Phase.Login(LoginMode.VaultMissing, null)
            }

            else -> {
                val opened = Qualia.openVault(data, store)
                if (opened == null) {
                    vault = null
                    phase = Phase.Login(LoginMode.Unreadable, null)
                } else {
                    vault = opened
                    message = null
                    phase = Phase.Main(Tab.TwoFactor)
                }
            }
        }
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

    CompositionLocalProvider(LocalAppLocale provides language.tag) {
        key(language) {
            QualiaTheme {
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
                            onUnlock = { openExisting() },
                        )

                    Phase.Joining ->
                        JoinAccountScreen(
                            storage = storage,
                            onJoined = { joined ->
                                vault = joined
                                message = null
                                // The design sends a device that just logged in to the sync
                                // screen, which is where its entries come from: logging in
                                // brings the key, not the content.
                                phase = Phase.Main(Tab.Sync)
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
                                        )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** How long an open vault stays open with no way for the user to extend it. */
private const val SESSION_TIMEOUT_SECONDS = 900

/**
 * The label this device's identity is kept under in the platform key store.
 *
 * A device has one identity, and it is the same one whatever label is used to look it up;
 * the label exists so that the store's entries can be told apart from other applications'.
 */
internal const val DEVICE_LABEL = "qualia2fa-device"
