package dev.qualia.qualia2fa

/**
 * Base64, used for the text form of a pairing message.
 *
 * Pairing messages are binary, while the channel that carries them (a QR code, or the
 * clipboard) is text. The text form is part of the facade rather than of the screens, so
 * every caller spells it the same way.
 */
expect fun encodeToText(bytes: ByteArray): String

/** The inverse of [encodeToText]; returns `null` on anything that is not that form. */
expect fun decodeFromText(text: String): ByteArray?
