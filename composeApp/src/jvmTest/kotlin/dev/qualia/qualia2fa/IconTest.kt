package dev.qualia.qualia2fa

import dev.qualia.qualia2fa.resources.Res
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The interface's icons: they have to be in the package, and the renderer the application
 * ships has to be able to draw them.
 *
 * # Why this is worth a test
 *
 * One of the shipped icons carries its stroke in a `<style>` block with a class, and the
 * filled ones use a `viewBox` outside the usual 0–24 range. Both are places where an icon can
 * fail to draw **without failing to compile**: the application would show a hole where a
 * button should be. Drawing every icon here catches that.
 *
 * # The icons are drawn through `Icon`
 *
 * Their own colours are placeholders: `Icon` tints whatever it is given. What matters is the
 * shape's coverage — enough pixels to be a glyph, not so many that it is a filled square
 * (which is what a broken `viewBox` produces).
 */
class IconTest {
    /** The icons the interface uses, by their name in the resources. */
    private val icons =
        listOf(
            "icon_2fa",
            "icon_add",
            "icon_menu",
            "icon_select",
            "icon_select_all",
            "icon_selected",
            "icon_settings",
            "icon_sync",
        )

    /** The size the icons are drawn at here, which is also the size they are used at. */
    private val size = 24

    @Test
    fun everyIconShipsAndDrawsAGlyph(): Unit =
        runBlocking {
            for (icon in icons) {
                val bytes = Res.readBytes("drawable/$icon.svg")
                assertTrue(bytes.isNotEmpty(), "$icon.svg must be part of the application's resources")

                val raster = Svg.rasterize(bytes, size)
                val drawn = (0 until raster.size).count { raster.isDrawn(it) }
                val share = drawn.toDouble() / raster.size

                assertTrue(
                    share > 0.02,
                    "$icon draws almost nothing ($drawn of ${raster.size} pixels): the renderer could not read it",
                )
                assertTrue(
                    share < 0.75,
                    "$icon covers $drawn of ${raster.size} pixels, which is a filled square rather than a glyph",
                )
            }
        }

    /** A name that is not in the package must be reported, not silently drawn empty. */
    @Test
    fun aMissingIconIsAnError(): Unit =
        runBlocking {
            val failure = runCatching { Res.readBytes("drawable/icon_that_does_not_exist.svg") }
            assertNotNull(failure.exceptionOrNull(), "reading a missing drawable must fail")
        }
}
