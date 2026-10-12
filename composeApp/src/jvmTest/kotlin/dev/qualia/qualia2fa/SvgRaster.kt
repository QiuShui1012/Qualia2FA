package dev.qualia.qualia2fa

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Data
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.svg.SVGDOM

/**
 * Rasterizing a shipped SVG with the renderer the application itself uses.
 *
 * `painterResource` draws through Skia on every target this application has, so a drawable
 * that Skia cannot parse fails at runtime and not at compile time — a missing file, a
 * truncated one, or one that uses an SVG feature outside the supported set. Rendering it in a
 * test is what turns that into a failing test.
 *
 * The helper only exposes what the checks need: a raster and the pixels in it.
 */
internal object Svg {
    /** Rasterizes [svg] into a [size]×[size] square. */
    fun rasterize(svg: ByteArray, size: Int): Raster {
        val bitmap = Bitmap()
        check(bitmap.allocPixels(ImageInfo.makeN32Premul(size, size))) {
            "a raster surface of $size×$size must be allocatable"
        }

        val dom = SVGDOM(Data.makeFromBytes(svg))
        dom.setContainerSize(size.toFloat(), size.toFloat())
        dom.render(Canvas(bitmap))

        val pixels = requireNotNull(bitmap.readPixels()) { "the raster must hand back its pixels" }
        return Raster(size, size, pixels)
    }
}

/**
 * The pixels of one rasterized drawing.
 *
 * Four bytes per pixel. **Which channel is red depends on the platform** — Skia's N32 layout
 * is BGRA on Windows and RGBA elsewhere — so a check that wants to be portable asks how
 * bright a pixel is rather than which channel it is in.
 */
internal class Raster(val width: Int, val height: Int, val pixels: ByteArray) {
    /** How many pixels the raster holds. */
    val size: Int get() = width * height

    /** Whether the pixel at [index] is opaque enough to count as drawn. */
    fun isDrawn(index: Int): Boolean = ((pixels[index * 4 + 3].toInt() and 0xFF) >= 128)

    /** The three colour channels of the pixel at [index], in the platform's own order. */
    fun channels(index: Int): IntArray =
        intArrayOf(
            pixels[index * 4].toInt() and 0xFF,
            pixels[index * 4 + 1].toInt() and 0xFF,
            pixels[index * 4 + 2].toInt() and 0xFF,
        )

    /** How many pixels satisfy [check], which is given the channels of each pixel. */
    fun count(check: (IntArray) -> Boolean): Int {
        var matched = 0
        for (index in 0 until size) {
            if (check(channels(index))) matched++
        }
        return matched
    }
}
