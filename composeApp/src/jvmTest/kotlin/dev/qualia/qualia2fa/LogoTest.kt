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
 * with an SVG feature Skia does not implement is caught by nothing at compile time: the
 * application finds out when the window opens, which is the worst possible moment. Drawing it
 * here through the same library the interface uses (`painterResource` renders through Skia)
 * turns that into a failing test.
 *
 * # Android is the exception
 *
 * Compose resources support SVG everywhere **except Android**, which this project does not
 * build yet. When that target arrives, the mark and the icons need vector drawables there;
 * this comment and this test are where that is written down.
 */
class LogoTest {
    /** Side length of the raster the mark is drawn into; its own view box is 110. */
    private val size = 110

    @Test
    fun theMarkShipsAndTheRendererDrawsIt() =
        runBlocking {
            val bytes = Res.readBytes("drawable/qualia2fa_logo.svg")
            assertTrue(bytes.isNotEmpty(), "the mark must be part of the application's resources")

            val raster = Svg.rasterize(bytes, size)

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
                "the mark has a transparent margin; a plate that filled the square means the wrong drawing",
            )
            assertTrue(stroke > 100, "the line work must be drawn, only $stroke pixels are dark")
            assertTrue(plate > stroke, "the plate is most of the mark: plate=$plate stroke=$stroke")
            assertTrue(
                edge * 10 < raster.size,
                "too many pixels are neither plate nor line ($edge of ${raster.size}): the drawing is garbled",
            )
        }
}
