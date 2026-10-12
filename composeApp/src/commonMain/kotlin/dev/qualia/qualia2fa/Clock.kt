package dev.qualia.qualia2fa

/**
 * The current time in Unix seconds.
 *
 * Time never comes from the Rust side: the core library has no clock, the host passes the
 * time in, which is what keeps the RFC 6238 vectors testable.
 */
expect fun currentTimeSeconds(): Long
