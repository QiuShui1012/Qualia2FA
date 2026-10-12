package dev.qualia.qualia2fa

import java.security.SecureRandom

private val secureRandom = SecureRandom()

actual fun randomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)
