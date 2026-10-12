package dev.qualia.qualia2fa

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.language_system
import dev.qualia.qualia2fa.resources.status_locked
import dev.qualia.qualia2fa.resources.status_locked_on_timeout
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
 * Why the vault is locked, as the login screen reports it.
 *
 * A value rather than a translated string: the language can be switched while the screen is
 * up, and a stored `String` would keep the language it was built in. Only the ways a vault
 * *becomes* locked are here — "opened" and "created" are not states the interface shows,
 * because what is worth telling the user about a key store belongs in the settings, where it
 * stays readable instead of passing by.
 */
sealed interface Status {
    /** The user locked the vault from the settings. */
    data object Locked : Status

    /** The vault was locked after [seconds] without the phase changing. */
    data class LockedAfterTimeout(val seconds: Int) : Status
}

/** The text of this status in the language currently in effect. */
@Composable
fun Status.text(): String =
    when (this) {
        Status.Locked -> stringResource(Res.string.status_locked)
        is Status.LockedAfterTimeout -> stringResource(Res.string.status_locked_on_timeout, seconds)
    }
