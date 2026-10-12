package dev.qualia.qualia2fa

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.language_system
import dev.qualia.qualia2fa.resources.status_created_hardware
import dev.qualia.qualia2fa.resources.status_created_software
import dev.qualia.qualia2fa.resources.status_joined
import dev.qualia.qualia2fa.resources.status_local_vault
import dev.qualia.qualia2fa.resources.status_locked
import dev.qualia.qualia2fa.resources.status_locked_on_blur
import dev.qualia.qualia2fa.resources.status_locked_on_timeout
import dev.qualia.qualia2fa.resources.status_opening_vault
import dev.qualia.qualia2fa.resources.status_unlocked
import org.jetbrains.compose.resources.stringResource

/**
 * The language the interface is shown in.
 *
 * [tag] is the BCP 47 tag handed to [LocalAppLocale]; `null` means "follow the operating
 * system", which is the default. The names shown in the selector are **endonyms** — a user
 * looking for their own language has to recognize it, and "Chinese" is of no use to
 * somebody who only reads Chinese.
 *
 * The value is persisted by name, so renaming a constant loses the user's choice (falling
 * back to [System] rather than to a wrong language).
 */
enum class AppLanguage(val tag: String?) {
    /** Follow the operating system's language. */
    System(null),
    /** English, the default locale of the resources. */
    English("en-US"),
    /** Chinese. */
    Chinese("zh-CN"),
    ;

    companion object {
        /** The stored name of [language], or [System] when the name is not one of ours. */
        fun fromName(language: String?): AppLanguage =
            entries.firstOrNull { it.name == language } ?: System
    }
}

/**
 * The locale the resource library resolves strings with.
 *
 * Compose Multiplatform has no common public API for this yet, so the application carries
 * the platform-specific part itself: on the desktop, providing a value here sets the JVM
 * default locale, which is what the resource library reads. Providing `null` restores the
 * locale that was in effect when it was first asked for.
 *
 * The caller must also change a key around its content (see `App`): the resource library
 * resolves a string when it is composed, and without a key the old text would stay on
 * screen after a switch.
 */
expect object LocalAppLocale {
    /** The locale currently in effect, as a tag. */
    val current: String

    /** Provides [value] (or the system default when `null`) to the content below. */
    @Composable
    infix fun provides(value: String?): ProvidedValue<*>
}

/** The label of this language in the language selector. */
@Composable
fun AppLanguage.label(): String =
    when (this) {
        AppLanguage.System -> stringResource(Res.string.language_system)
        AppLanguage.English -> "English"
        AppLanguage.Chinese -> "中文"
    }

/**
 * What the status line says.
 *
 * A value rather than a translated string: the language can be switched while the line is
 * on screen, and a stored `String` would keep the text of the language it was built in.
 */
sealed interface Status {
    /** The vault is being opened. */
    data object OpeningVault : Status

    /** The vault is open. */
    data object Unlocked : Status

    /** The user locked the vault. */
    data object Locked : Status

    /** The window lost focus, which locks the vault. */
    data object LockedOnBlur : Status

    /** The vault was locked after [seconds] without activity. */
    data class LockedAfterTimeout(val seconds: Int) : Status

    /** The device joined an account through pairing. */
    data object JoinedThroughPairing : Status

    /**
     * A device identity was created in [backend].
     *
     * [hardwareBacked] must be reported as the platform key store answered it: a key that
     * is not hardware protected is a fact the user is entitled to read.
     */
    data class CreatedDeviceIdentity(val backend: String, val hardwareBacked: Boolean) : Status

    /** The vault exists only on this device, so it cannot pair or sync. */
    data object LocalVaultOnly : Status
}

/** The text of this status in the language currently in effect. */
@Composable
fun Status.text(): String =
    when (this) {
        Status.OpeningVault -> stringResource(Res.string.status_opening_vault)
        Status.Unlocked -> stringResource(Res.string.status_unlocked)
        Status.Locked -> stringResource(Res.string.status_locked)
        Status.LockedOnBlur -> stringResource(Res.string.status_locked_on_blur)
        is Status.LockedAfterTimeout -> stringResource(Res.string.status_locked_on_timeout, seconds)
        Status.JoinedThroughPairing -> stringResource(Res.string.status_joined)
        is Status.CreatedDeviceIdentity ->
            stringResource(
                if (hardwareBacked) Res.string.status_created_hardware else Res.string.status_created_software,
                backend,
            )
        Status.LocalVaultOnly -> stringResource(Res.string.status_local_vault)
    }
