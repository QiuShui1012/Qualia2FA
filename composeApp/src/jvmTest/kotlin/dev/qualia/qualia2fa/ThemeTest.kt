package dev.qualia.qualia2fa

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The two color schemes, checked by arithmetic rather than by looking at a screen.
 *
 * # Why this is a test
 *
 * A color scheme is a table of numbers, and whether a foreground can be read on its background
 * is decided by the **contrast ratio** of the two - a quantity, not an opinion. The pairs below
 * are the ones the interface actually draws (it uses Material 3 roles, and every one of these
 * roles is used somewhere), so a scheme that fails one of them is a screen where something is
 * illegible on somebody's display, in one of the two themes. That is exactly the class of
 * mistake a machine should be catching.
 *
 * # What is measured
 *
 * The WCAG 2.1 relative luminance of each color (sRGB transfer function, then the Rec. 709
 * weights) and the ratio `(lighter + 0.05) / (darker + 0.05)`. Text needs **4.5:1** to pass
 * level AA, which is the threshold used here; a surface that only has to be **told apart** from
 * the one behind it is held to a much lower bar, and that bar is written out where it is used.
 *
 * # The dark scheme is the light one, not a second palette
 *
 * The two tests after the contrast one pin that down: the mark's two tones swap roles, and a
 * surface is still a surface (raised in both schemes, with a secondary surface that can be told
 * from the background). A dark theme that introduced its own colors would be a different
 * application wearing the same name.
 */
class ThemeTest {
    /** One pair the interface draws: what a name says, and the two colors involved. */
    private data class Pair(val what: String, val foreground: Color, val background: Color)

    /** The pairs that carry text, and the threshold the specification asks of them. */
    private fun textPairs(scheme: ColorScheme): List<Pair> =
        listOf(
            Pair("body text on the background", scheme.onBackground, scheme.background),
            Pair("body text on a surface", scheme.onSurface, scheme.surface),
            Pair("secondary text on a surface", scheme.onSurfaceVariant, scheme.surface),
            Pair("secondary text on a raised surface", scheme.onSurfaceVariant, scheme.surfaceVariant),
            Pair("a label on the primary action", scheme.onPrimary, scheme.primary),
            Pair("a label on a primary surface", scheme.onPrimaryContainer, scheme.primaryContainer),
            Pair("a label on a secondary surface", scheme.onSecondaryContainer, scheme.secondaryContainer),
            Pair("a label on the action color", scheme.onSecondary, scheme.secondary),
            Pair("an error message on the background", scheme.error, scheme.background),
            Pair("an error message on a surface", scheme.error, scheme.surface),
            Pair("a label on an error", scheme.onError, scheme.error),
            Pair("a label on an error surface", scheme.onErrorContainer, scheme.errorContainer),
        )

    /** Every text pair passes WCAG AA (4.5:1), in both schemes. */
    @Test
    fun every_text_pair_is_readable_in_both_schemes() {
        for ((name, scheme) in schemes()) {
            for (pair in textPairs(scheme)) {
                val ratio = contrast(pair.foreground, pair.background)
                assertTrue(
                    ratio >= 4.5,
                    "$name: ${pair.what} has a contrast of ${round(ratio)}:1, below the 4.5:1 " +
                        "the specification asks of text",
                )
            }
        }
    }
    /** The dark scheme is the light one with the mark's two tones swapped. */
    @Test
    fun the_dark_scheme_swaps_the_marks_two_tones() {
        val light = QualiaLightColors
        val dark = QualiaDarkColors

        assertTrue(
            dark.background == light.onBackground,
            "the dark background is the ink the light scheme writes with",
        )
        assertTrue(
            dark.onBackground == light.background,
            "the dark foreground is the plate the light scheme sits on",
        )
        assertTrue(
            dark.primary == light.background && dark.onPrimary == light.primary,
            "the primary action inverts the same way",
        )
        assertTrue(
            dark.scrim == light.onBackground,
            "and so does the scrim, which is the ink in both schemes",
        )
    }

    /** A surface is still a surface: raised off the background, and separable from it. */
    @Test
    fun a_surface_is_raised_in_both_schemes() {
        for ((name, scheme) in schemes()) {
            assertTrue(
                luminance(scheme.surface) > luminance(scheme.background),
                "$name: a surface must be lifted off the background it sits on",
            )
            for ((what, surface) in listOf("a surface" to scheme.surface, "a raised surface" to scheme.surfaceVariant)) {
                val ratio = contrast(surface, scheme.background)
                assertTrue(
                    ratio >= 1.1,
                    "$name: $what has a contrast of ${round(ratio)}:1 against the background, " +
                        "which is not enough to see its edge",
                )
            }
        }
    }

    /** The two schemes, named for the failure messages. */
    private fun schemes(): List<kotlin.Pair<String, ColorScheme>> =
        listOf("the light scheme" to QualiaLightColors, "the dark scheme" to QualiaDarkColors)

    /**
     * The WCAG 2.1 relative luminance of a color.
     *
     * Compose's `red` / `green` / `blue` are the encoded sRGB components, so each one goes
     * through the sRGB transfer function first and the Rec. 709 weights are applied after.
     */
    private fun luminance(color: Color): Double {
        fun linear(component: Float): Double {
            val value = component.toDouble()
            return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }

    /** The WCAG contrast ratio of two colors, which is symmetric. */
    private fun contrast(one: Color, other: Color): Double {
        val first = luminance(one)
        val second = luminance(other)
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }

    /** A ratio with two decimals, for a message a person has to read. */
    private fun round(value: Double): String = ((value * 100).toInt() / 100.0).toString()
}
