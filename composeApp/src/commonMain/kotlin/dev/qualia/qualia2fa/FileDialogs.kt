package dev.qualia.qualia2fa

/**
 * Writes [bytes] to a file the user picks.
 *
 * Returns the path it was written to, or `null` when the user cancelled or the write failed.
 * The dialog's own text is a parameter rather than something this layer looks up: the dialog
 * is opened from a callback, where no resource can be resolved, so the caller passes the text
 * it resolved while it was composing.
 *
 * [extension] is appended when the user does not type one, so that the file keeps the name
 * the filter and the documentation talk about.
 */
expect fun saveBytes(
    suggestedName: String,
    dialogTitle: String,
    filterLabel: String,
    extension: String,
    bytes: ByteArray,
): String?

/**
 * Reads a file the user picks, or `null` when they cancelled or the file cannot be read.
 *
 * The bytes are returned as they are: **decoding and validating them is not this layer's
 * job**, it belongs to the core that defines the format.
 */
expect fun pickBytes(dialogTitle: String, filterLabel: String, extension: String): ByteArray?
