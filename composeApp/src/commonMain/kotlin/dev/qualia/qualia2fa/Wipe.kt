package dev.qualia.qualia2fa

/**
 * Overwrites key material held in a byte array.
 *
 * The Rust side zeroizes its own copies when they are dropped; this is for the short
 * lived copies the host makes, so that a heap dump right after an operation does not
 * contain a master key.
 */
expect fun wipe(bytes: ByteArray)
