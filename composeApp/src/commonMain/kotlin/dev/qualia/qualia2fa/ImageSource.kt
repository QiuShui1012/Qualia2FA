package dev.qualia.qualia2fa

/**
 * A grayscale image: one byte per pixel, `0` black and `255` white, row major.
 */
class GrayPixels(val width: Int, val height: Int, val pixels: ByteArray)

/**
 * Asks the user for an image file and converts it to grayscale pixels.
 *
 * Decoding the image **format** stays on the platform side (`ImageIO` here, the platform
 * API elsewhere): format decoders are where the historical vulnerabilities live, and the
 * Rust side only accepts pixels. Returns `null` when the user cancels or the file cannot
 * be decoded.
 *
 * The dialog's own text is a parameter rather than something this layer looks up: the
 * dialog is opened from a callback, where no resource can be resolved, so the caller
 * passes the text it resolved while it was composing.
 */
expect fun pickGrayImage(dialogTitle: String, imageFilesLabel: String): GrayPixels?
