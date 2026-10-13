package dev.qualia.qualia2fa

import androidx.compose.runtime.Composable

/**
 * A grayscale image: one byte per pixel, `0` black and `255` white, row major.
 */
class GrayPixels(val width: Int, val height: Int, val pixels: ByteArray)

/**
 * Everything that leaves or enters this application as a file, as the platform does it.
 *
 * # Why this is an interface with callbacks rather than three functions
 *
 * The two platforms disagree about the *shape* of these operations. A desktop file dialog
 * blocks until the user answers it, so the result is there on the next line. Android has no
 * such thing: the system picker is a separate activity, started with a launcher that must be
 * registered while composing, and its result arrives later, on the main thread, after the
 * call has returned.
 *
 * An interface with a callback is the shape both can implement, and the callback is also what
 * the screens need: they update their own state when the answer arrives, whichever thread the
 * platform answers on.
 *
 * # What a platform implementation must not do
 *
 * It forwards and decides nothing else: which file, whether the passphrase is right and what
 * the bytes mean are decided by the caller and by the core. Image **format** decoding lives
 * here, because it is the one operation whose platform API is not portable, and it returns
 * pixels rather than a picture.
 *
 * [extension] is appended to a saved name when the user does not type one, so that the file
 * keeps the name the filter and the documentation talk about.
 */
interface FilePicker {
    /**
     * Asks for an image file and hands back its grayscale pixels.
     *
     * [onResult] is called with `null` when the user cancels or the file cannot be decoded.
     */
    fun pickGrayImage(dialogTitle: String, imageFilesLabel: String, onResult: (GrayPixels?) -> Unit)

    /**
     * Writes [bytes] to a file the user picks, and hands back where it went.
     *
     * [onResult] is called with `null` when the user cancels or the write fails.
     */
    fun saveBytes(
        suggestedName: String,
        dialogTitle: String,
        filterLabel: String,
        extension: String,
        bytes: ByteArray,
        onResult: (String?) -> Unit,
    )

    /**
     * Reads a file the user picks.
     *
     * [onResult] is called with `null` when the user cancels or the file cannot be read. The
     * bytes are handed over as they are: **decoding and validating them is not this layer's
     * job**, it belongs to the core that defines the format.
     */
    fun pickBytes(dialogTitle: String, filterLabel: String, extension: String, onResult: (ByteArray?) -> Unit)
}

/**
 * The file picker of the platform this is composed on.
 *
 * The dialog text is passed in rather than resolved here: the dialog is opened from a
 * callback, where no resource can be resolved, so the caller passes the text it resolved
 * while it was composing.
 */
@Composable
expect fun rememberFilePicker(): FilePicker

/**
 * Reads the code out of an image the user picks.
 *
 * Every screen that reads a code has the same three outcomes to tell apart: the image could
 * not be read at all ([unreadable]), it held no code ([noCode]), or it held one ([onText]).
 * They are passed in rather than resolved here for the same reason the dialog text is, and
 * keeping them in one place is what stops the three branches from drifting apart between
 * screens.
 */
fun FilePicker.readCodeFromImage(
    dialogTitle: String,
    imageFilesLabel: String,
    unreadable: String,
    noCode: String,
    onText: (String) -> Unit,
    onMessage: (String?) -> Unit,
) {
    pickGrayImage(dialogTitle, imageFilesLabel) { image ->
        if (image == null) {
            onMessage(unreadable)
        } else {
            val text = Qualia.qrTextFromImage(image.pixels, image.width, image.height)
            if (text == null) onMessage(noCode) else onText(text)
        }
    }
}
