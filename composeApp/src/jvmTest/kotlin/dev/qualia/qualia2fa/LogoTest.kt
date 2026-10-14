package dev.qualia.qualia2fa

import dev.qualia.qualia2fa.resources.Res
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The application mark: it has to be part of the package, and the renderer the application
 * ships has to be able to draw it.
 *
 * The second half is the one worth a test. A drawable that is missing, truncated, or written
 * with a feature the vector parser does not implement is caught by nothing at compile time:
 * the application finds out when the window opens, which is the worst possible moment. Drawing
 * it here, through the parser the interface uses, turns that into a failing test.
 *
 * The mark is the one drawing that is not a glyph: it is a plate with line work on it, and it
 * is drawn untinted, so its own two colors are what the interface shows. The checks below
 * are about that composition — a rounded plate rather than a square one, enough line work to
 * read as the mark, and nothing in between that is neither.
 */
class LogoTest {
    /** Side length of the raster the mark is drawn into; its own view box is 90. */
    private val size = 110

    @Test
    fun theMarkShipsAndTheRendererDrawsIt() =
        runBlocking {
            val bytes = Res.readBytes("drawable/qualia2fa_logo.xml")
            assertTrue(bytes.isNotEmpty(), "the mark must be part of the application's resources")

            val raster = Vector.rasterize(bytes, size)

            // "How bright is this pixel" rather than "which channel is it in": the channel
            // order depends on the platform.
            var clear = 0
            var plate = 0
            var stroke = 0
            var edge = 0
            for (index in 0 until raster.size) {
                if (!raster.isDrawn(index)) {
                    clear++
                    continue
                }
                val channels = raster.channels(index)
                when {
                    channels.min() > 180 -> plate++
                    channels.max() < 80 -> stroke++
                    else -> edge++
                }
            }

            assertTrue(
                clear > 0,
                "the plate is a rounded square, so the corners of the raster must be empty; " +
                    "the whole square being drawn means the plate lost its corner radius",
            )
            assertTrue(stroke > 100, "the line work must be drawn, only $stroke pixels are dark")
            assertTrue(plate > stroke, "the plate is most of the mark: plate=$plate stroke=$stroke")
            assertTrue(
                edge * 10 < raster.size,
                "too many pixels are neither plate nor line ($edge of ${raster.size}): the drawing is garbled",
            )
        }
}
