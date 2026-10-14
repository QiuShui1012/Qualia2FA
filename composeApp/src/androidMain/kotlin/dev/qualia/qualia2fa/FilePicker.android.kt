package dev.qualia.qualia2fa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * The Android file picker, built on the system's own pickers.
 *
 * # Why the answer arrives later
 *
 * Nothing here blocks. A picker is a separate activity, started with a launcher that has to
 * be registered while composing, so the result arrives on the main thread after the call that
 * asked for it has returned; [FilePicker]'s callbacks are where it is handed back. The pending
 * callback is held in composition state because the launcher's own callback is created once,
 * before any of it is known.
 *
 * # What is used
 *
 * * Images go through the photo picker (`PickVisualMedia`), which grants access to the one
 *   image the user chose and asks for no permission at all; on releases without it the
 *   contract falls back to opening a document.
 * * Reading and writing other files go through the Storage Access Framework, whose grant is
 *   per file and lasts as long as the operation does.
 *
 * An image is decoded here because that is the one part with no portable API, and it is
 * decoded **downscaled**: a picture straight out of a modern camera is larger than the
 * decoder needs to read a code out of it, and decoding it whole is how such a screen runs out
 * of memory.
 */
@Composable
actual fun rememberFilePicker(): FilePicker {
    val context = LocalContext.current
    var pendingImage by remember { mutableStateOf<((GrayPixels?) -> Unit)?>(null) }
    var pendingRead by remember { mutableStateOf<((ByteArray?) -> Unit)?>(null) }
    var pendingWrite by remember { mutableStateOf<PendingWrite?>(null) }

    val image =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val answer = pendingImage
            pendingImage = null
            answer?.invoke(uri?.let { decodeGray(context, it) })
        }

    val reader =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val answer = pendingRead
            pendingRead = null
            answer?.invoke(uri?.let { read(context, it) })
        }

    val writer =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(OCTET_STREAM)) { uri ->
            val request = pendingWrite
            pendingWrite = null
            if (request == null) return@rememberLauncherForActivityResult
            if (uri == null) return@rememberLauncherForActivityResult request.answer(null)
            request.answer(write(context, uri, request.bytes))
        }

    return remember(image, reader, writer) {
        AndroidFilePicker(
            pickImage = { image.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            pickDocument = { reader.launch(arrayOf(ANY_TYPE)) },
            createDocument = { name -> writer.launch(name) },
            holdImage = { pendingImage = it },
            holdRead = { pendingRead = it },
            holdWrite = { pendingWrite = it },
        )
    }
}

/** One file being written, and where to hand the answer. */
private class PendingWrite(val bytes: ByteArray, val answer: (String?) -> Unit)

/** The type a document with no registered type of its own is offered and read as. */
private const val OCTET_STREAM = "application/octet-stream"

/** The tag diagnostics about a picked file are written under. */
private const val TAG = "Qualia2FA"

/** The filter that matches every file: an application-specific extension has no type. */
private const val ANY_TYPE = "*/*"

/** The longest edge an image is decoded at, in pixels. */
private const val MAX_IMAGE_EDGE = 2048

private class AndroidFilePicker(
    private val pickImage: () -> Unit,
    private val pickDocument: () -> Unit,
    private val createDocument: (String) -> Unit,
    private val holdImage: (((GrayPixels?) -> Unit)?) -> Unit,
    private val holdRead: (((ByteArray?) -> Unit)?) -> Unit,
    private val holdWrite: (PendingWrite?) -> Unit,
) : FilePicker {
    override fun pickGrayImage(dialogTitle: String, imageFilesLabel: String, onResult: (GrayPixels?) -> Unit) {
        holdImage(onResult)
        pickImage()
    }

    override fun saveBytes(
        suggestedName: String,
        dialogTitle: String,
        filterLabel: String,
        extension: String,
        bytes: ByteArray,
        onResult: (String?) -> Unit,
    ) {
        holdWrite(PendingWrite(bytes, onResult))
        val name = if (suggestedName.lowercase().endsWith(".${extension.lowercase()}")) suggestedName else "$suggestedName.$extension"
        createDocument(name)
    }

    override fun pickBytes(
        dialogTitle: String,
        filterLabel: String,
        extension: String,
        onResult: (ByteArray?) -> Unit,
    ) {
        holdRead(onResult)
        pickDocument()
    }
}

/** Reads the whole document at [uri], or `null` when it cannot be opened or read. */
private fun read(context: Context, uri: Uri): ByteArray? =
    try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (_: Exception) {
        null
    }

/** Writes [bytes] to the document at [uri], and answers with where they went. */
private fun write(context: Context, uri: Uri, bytes: ByteArray): String? =
    try {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
        uri.toString()
    } catch (_: Exception) {
        null
    }

/**
 * Decodes the image at [uri] into grayscale pixels, downscaled to [MAX_IMAGE_EDGE].
 *
 * The conversion is the simple luma weighting (0.299/0.587/0.114) rather than a plain
 * average: a code's dark and light modules can differ in color as much as in brightness,
 * and averaging channels makes colored modules harder to tell apart, not easier.
 *
 * The size is read first, without decoding anything, so that a picture far larger than the
 * decoder needs is sampled down on the way in instead of being held in memory whole.
 */
private fun decodeGray(context: Context, uri: Uri): GrayPixels? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    } catch (error: Exception) {
        Log.w(TAG, "the chosen image could not be opened", error)
        return null
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        Log.w(TAG, "the chosen file has no decodable image in it: ${bounds.outWidth}x${bounds.outHeight}")
        return null
    }

    val options =
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    val bitmap =
        try {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (error: Exception) {
            Log.w(TAG, "the chosen image could not be decoded", error)
            null
        } ?: run {
        Log.w(TAG, "the chosen image decoded to nothing")
        return null
    }

    val width = bitmap.width
    val height = bitmap.height
    val colors = IntArray(width * height)
    bitmap.getPixels(colors, 0, width, 0, 0, width, height)
    bitmap.recycle()

    val gray = ByteArray(width * height)
    for (index in colors.indices) {
        val rgb = colors[index]
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        gray[index] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
    }
    return GrayPixels(width, height, gray)
}

/** The power of two a [width]×[height] image is decoded at, so that neither edge exceeds the limit. */
private fun sampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (width / sample > MAX_IMAGE_EDGE || height / sample > MAX_IMAGE_EDGE) {
        sample *= 2
    }
    return sample
}
