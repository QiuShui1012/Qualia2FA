package dev.qualia.qualia2fa

import java.io.File
import java.nio.file.Files
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Saves bytes through the platform file dialog.
 *
 * The dialog runs on the calling thread, which is the user interface thread: that is what
 * makes it modal to the window and is the usual Swing arrangement.
 */
actual fun saveBytes(
    suggestedName: String,
    dialogTitle: String,
    filterLabel: String,
    extension: String,
    bytes: ByteArray,
): String? {
    val chooser =
        JFileChooser().apply {
            this.dialogTitle = dialogTitle
            fileFilter = FileNameExtensionFilter(filterLabel, extension)
            selectedFile = File(suggestedName)
        }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null

    val chosen = chooser.selectedFile ?: return null
    val target = if (chosen.name.lowercase().endsWith(".${extension.lowercase()}")) chosen else File(chosen.parentFile, "${chosen.name}.$extension")
    return try {
        target.writeBytes(bytes)
        target.absolutePath
    } catch (_: Exception) {
        null
    }
}

/** Reads a file through the platform file dialog. */
actual fun pickBytes(dialogTitle: String, filterLabel: String, extension: String): ByteArray? {
    val chooser =
        JFileChooser().apply {
            this.dialogTitle = dialogTitle
            fileFilter = FileNameExtensionFilter(filterLabel, extension)
        }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null

    val file = chooser.selectedFile ?: return null
    return try {
        Files.readAllBytes(file.toPath())
    } catch (_: Exception) {
        null
    }
}
