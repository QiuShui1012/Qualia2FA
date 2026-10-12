package dev.qualia.qualia2fa

/**
 * Cryptographically secure random bytes from the platform generator.
 *
 * Key material never comes from a general purpose random source: on the JVM this is
 * `SecureRandom`, which the platform feeds from the operating system.
 */
expect fun randomBytes(size: Int): ByteArray
