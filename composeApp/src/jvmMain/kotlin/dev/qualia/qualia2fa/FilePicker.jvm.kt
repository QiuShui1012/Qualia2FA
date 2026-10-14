package dev.qualia.qualia2fa

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * The desktop file picker: a Swing dialog, answered before the call returns.
 *
 * The dialog runs on the calling thread, which is the user interface thread: that is what
 * makes it modal to the window and is the usual Swing arrangement. The `onResult` callback of
 * the picker is therefore called before this returns, and a caller may rely on neither more
 * nor less than that.
 *
 * The image extensions offered are the ones `ImageIO` reads without an extra plugin; a file
 * in another format is reported as unreadable rather than guessed at.
 */
@Composable
actual fun rememberFilePicker(): FilePicker = remember { DesktopFilePicker() }

private class DesktopFilePicker : FilePicker {
    override fun pickGrayImage(dialogTitle: String, imageFilesLabel: String, onResult: (GrayPixels?) -> Unit) {
        val chooser =
            JFileChooser().apply {
                this.dialogTitle = dialogTitle
                fileFilter =
                    FileNameExtensionFilter(imageFilesLabel, "png", "jpg", "jpeg", "webp", "bmp", "gif")
            }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return onResult(null)

        val file = chooser.selectedFile ?: return onResult(null)
        onResult(decodeGray(file))
    }

    override fun saveBytes(
        suggestedName: String,
        dialogTitle: String,
        filterLabel: String,
        extension: String,
        bytes: ByteArray,
        onResult: (String?) -> Unit,
    ) {
        val chooser =
            JFileChooser().apply {
                this.dialogTitle = dialogTitle
                fileFilter = FileNameExtensionFilter(filterLabel, extension)
                selectedFile = File(suggestedName)
            }
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return onResult(null)

        val chosen = chooser.selectedFile ?: return onResult(null)
        val target =
            if (chosen.name.lowercase().endsWith(".${extension.lowercase()}")) {
                chosen
            } else {
                File(chosen.parentFile, "${chosen.name}.$extension")
            }
        onResult(
            try {
                target.writeBytes(bytes)
                target.absolutePath
            } catch (_: Exception) {
                null
            },
        )
    }

    override fun pickBytes(
        dialogTitle: String,
        filterLabel: String,
        extension: String,
        onResult: (ByteArray?) -> Unit,
    ) {
        val chooser =
            JFileChooser().apply {
                this.dialogTitle = dialogTitle
                fileFilter = FileNameExtensionFilter(filterLabel, extension)
            }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return onResult(null)

        val file = chooser.selectedFile ?: return onResult(null)
        onResult(
            try {
                Files.readAllBytes(file.toPath())
            } catch (_: Exception) {
                null
            },
        )
    }

    /**
     * Reads an image file and converts it to grayscale.
     *
     * The conversion is the simple luma weighting (0.299/0.587/0.114) rather than a plain
     * average: a code's dark and light modules can differ in color as much as in brightness,
     * and averaging channels makes colored modules harder to tell apart, not easier.
     */
    private fun decodeGray(file: File): GrayPixels? {
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
}
