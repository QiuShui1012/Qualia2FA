package dev.qualia.qualia2fa

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/**
 * The desktop implementation of [LocalAppLocale].
 *
 * The resource library resolves strings against the JVM's default locale, so providing a
 * language here means calling [Locale.setDefault] and re-providing the composition local.
 * The locale that was in effect the first time a value was provided is remembered, so
 * "follow the system" remains reachable after switching back and forth.
 */
actual object LocalAppLocale {
    /** The locale in effect before the application changed anything. */
    private var systemDefault: Locale? = null

    /** The locale the content below this provider is composed with. */
    private val local = staticCompositionLocalOf { Locale.getDefault().toString() }

    actual val current: String
        @Composable get() = local.current

    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        val resolved =
            when (value) {
                null -> systemDefault ?: Locale.getDefault().also { systemDefault = it }
                else -> Locale.forLanguageTag(value)
            }
        Locale.setDefault(resolved)
        return local.provides(resolved.toString())
    }
}
