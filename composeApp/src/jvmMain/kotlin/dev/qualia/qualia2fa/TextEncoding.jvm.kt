package dev.qualia.qualia2fa

import java.util.Base64

actual fun encodeToText(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

actual fun decodeFromText(text: String): ByteArray? =
    try {
        Base64.getDecoder().decode(text.trim())
    } catch (_: IllegalArgumentException) {
        null
    }
