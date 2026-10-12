package dev.qualia.qualia2fa

import dev.qualia.qualia2fa.resources.Res
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Data
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.svg.SVGDOM
import kotlin.test.Test
import kotlin.test.assertNotNull
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
 * build yet. When that target arrives, the mark needs a vector drawable there; this comment
 * and this test are where that is written down.
 */
class LogoTest {
    /** Side length of the raster the mark is drawn into; its own view box is 110. */
    private val size = 110

    @Test
    fun theMarkShipsAndTheRendererDrawsIt() =
        runBlocking {
            val bytes = Res.readBytes("drawable/qualia2fa_logo.svg")
            assertTrue(bytes.isNotEmpty(), "the mark must be part of the application's resources")

            val bitmap = Bitmap()
            assertTrue(
                bitmap.allocPixels(ImageInfo.makeN32Premul(size, size)),
                "a raster surface of $size×$size must be allocatable",
            )
            val dom = SVGDOM(Data.makeFromBytes(bytes))
            dom.setContainerSize(size.toFloat(), size.toFloat())
            dom.render(Canvas(bitmap))

            // Four bytes per pixel. Which channel is red depends on the platform (Skia's N32
            // is BGRA on Windows and RGBA elsewhere), so the classification below asks how
            // bright a pixel is, which both orders agree on.
            val pixels = assertNotNull(bitmap.readPixels(), "the raster must hand back its pixels")
            var clear = 0
            var plate = 0
            var stroke = 0
            var edge = 0
            for (index in pixels.indices step 4) {
                val alpha = pixels[index + 3].toInt() and 0xFF
                val channels =
                    intArrayOf(
                        pixels[index].toInt() and 0xFF,
                        pixels[index + 1].toInt() and 0xFF,
                        pixels[index + 2].toInt() and 0xFF,
                    )
                when {
                    alpha < 128 -> clear++
                    channels.min() > 180 -> plate++
                    channels.max() < 80 -> stroke++
                    else -> edge++
                }
            }
            val total = clear + plate + stroke + edge
            assertTrue(total > size * size - 16, "every pixel must be classified, got $total")

            assertTrue(
                clear > 0,
                "the mark has a transparent margin; a plate that filled the square means the wrong drawing",
            )
            assertTrue(stroke > 100, "the line work must be drawn, only $stroke pixels are dark")
            assertTrue(plate > stroke, "the plate is most of the mark: plate=$plate stroke=$stroke")
            assertTrue(
                edge * 10 < total,
                "too many pixels are neither plate nor line ($edge of $total): the drawing is garbled",
            )
        }
}
