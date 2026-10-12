package dev.qualia.qualia2fa

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Picks an image with the platform file dialog and converts it to grayscale.
 *
 * The dialog runs on the calling thread, which is the user interface thread: that is what
 * makes the dialog modal to the window and is the usual Swing arrangement.
 *
 * The conversion is the simple luma weighting (0.299/0.587/0.114) rather than a plain
 * average: a code's dark and light modules can differ in colour as much as in brightness,
 * and averaging channels makes coloured modules harder to tell apart, not easier.
 */
actual fun pickGrayImage(dialogTitle: String, imageFilesLabel: String): GrayPixels? {
    val chooser =
        JFileChooser().apply {
            this.dialogTitle = dialogTitle
            fileFilter = FileNameExtensionFilter(imageFilesLabel, "png", "jpg", "jpeg", "webp", "bmp", "gif")
        }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null

    val file: File = chooser.selectedFile ?: return null
    val image: BufferedImage =
        try {
            ImageIO.read(file) ?: return null
        } catch (_: Exception) {
            return null
        }

    val width = image.width
    val height = image.height
    val gray = ByteArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val rgb = image.getRGB(x, y)
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            gray[y * width + x] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
        }
    }
    return GrayPixels(width, height, gray)
}
