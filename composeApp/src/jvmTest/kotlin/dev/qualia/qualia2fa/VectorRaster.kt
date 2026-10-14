package dev.qualia.qualia2fa

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asSkiaPath
import androidx.compose.ui.graphics.vector.PathParser
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PaintStrokeJoin
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Rasterizing a shipped Android vector drawable the way the application reads it.
 *
 * Every target draws these through the same multiplatform parser, so what is checked here is
 * what the interface does: the file parses as a vector, its path data is understood, and the
 * drawing lands inside the viewport. A wrong viewport or a path outside it shows up as a blank
 * raster or as a filled square, which is exactly the failure that would otherwise be found
 * when the window opens — or, on Android, on a device.
 *
 * The transform a `<group>` carries is applied the way the format defines it: the translation
 * is outermost, then the rotation and the scale about the group's pivot.
 */
internal object Vector {
    /** The XML namespace every attribute of a vector drawable lives in. */
    private const val ANDROID = "http://schemas.android.com/apk/res/android"

    /** Rasterizes [xml] into a [size]×[size] square. */
    fun rasterize(xml: ByteArray, size: Int): Raster = Raster.of(render(xml, size))

    /** Draws [xml] into a bitmap of [size]×[size]. */
    fun render(xml: ByteArray, size: Int): Bitmap {
        val root =
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(xml))
                .documentElement

        val bitmap = Bitmap()
        check(bitmap.allocPixels(ImageInfo.makeN32Premul(size, size))) {
            "a raster surface of $size×$size must be allocatable"
        }

        val canvas = Canvas(bitmap)
        canvas.scale(
            size / requireNotNull(root.attribute("viewportWidth")) { "a drawable needs a viewport" }.toFloat(),
            size / requireNotNull(root.attribute("viewportHeight")) { "a drawable needs a viewport" }.toFloat(),
        )
        draw(canvas, root)
        return bitmap
    }

    /** Draws every child of [parent], which is the root or a group. */
    private fun draw(canvas: Canvas, parent: Element) {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index) as? Element ?: continue
            when (child.tagName) {
                "group" -> {
                    canvas.save()
                    canvas.translate(child.number("translateX", 0f), child.number("translateY", 0f))
                    val pivotX = child.number("pivotX", 0f)
                    val pivotY = child.number("pivotY", 0f)
                    canvas.translate(pivotX, pivotY)
                    canvas.rotate(child.number("rotation", 0f))
                    canvas.scale(child.number("scaleX", 1f), child.number("scaleY", 1f))
                    canvas.translate(-pivotX, -pivotY)
                    draw(canvas, child)
                    canvas.restore()
                }
                "path" -> canvas.drawShape(child)
                else -> error("<${child.tagName}> is not part of a vector drawable")
            }
        }
    }

    /** Draws one `<path>`, with the fill and the stroke the element declares. */
    private fun Canvas.drawShape(element: Element) {
        val data = requireNotNull(element.attribute("pathData")) { "a path needs its path data" }
        val path = PathParser().parsePathString(data).toPath(Path())
        val skia = path.asSkiaPath()

        element.color("fillColor")?.let { fill ->
            drawPath(
                skia,
                Paint().apply {
                    color = fill
                    mode = PaintMode.FILL
                    isAntiAlias = true
                },
            )
        }

        element.color("strokeColor")?.let { stroke ->
            drawPath(
                skia,
                Paint().apply {
                    color = stroke
                    mode = PaintMode.STROKE
                    isAntiAlias = true
                    strokeWidth = element.attribute("strokeWidth")?.toFloat() ?: 1f
                    strokeCap = strokeCap(element.attribute("strokeLineCap"))
                    strokeJoin = strokeJoin(element.attribute("strokeLineJoin"))
                },
            )
        }
    }

    private fun strokeCap(name: String?): PaintStrokeCap =
        when (name) {
            null, "", "butt" -> PaintStrokeCap.BUTT
            "round" -> PaintStrokeCap.ROUND
            "square" -> PaintStrokeCap.SQUARE
            else -> error("$name is not a stroke cap")
        }

    private fun strokeJoin(name: String?): PaintStrokeJoin =
        when (name) {
            null, "", "miter" -> PaintStrokeJoin.MITER
            "round" -> PaintStrokeJoin.ROUND
            "bevel" -> PaintStrokeJoin.BEVEL
            else -> error("$name is not a stroke join")
        }

    /** An attribute of the element, or `null` when it is not there. */
    private fun Element.attribute(name: String): String? =
        getAttributeNS(ANDROID, name).takeIf { it.isNotBlank() }

    /** A numeric attribute, or [fallback] when it is not there. */
    private fun Element.number(name: String, fallback: Float): Float =
        attribute(name)?.toFloat() ?: fallback

    /** A `#AARRGGBB` attribute as Skia's color, or `null` when it is not there. */
    private fun Element.color(name: String): Int? =
        attribute(name)?.removePrefix("#")?.toLong(16)?.toInt()
}
