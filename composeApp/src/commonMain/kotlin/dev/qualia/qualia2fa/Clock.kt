package dev.qualia.qualia2fa

/**
 * The current time in Unix seconds.
 *
 * Time never comes from the Rust side: the core library has no clock, the host passes the
 * time in, which is what keeps the RFC 6238 vectors testable.
 */
expect fun currentTimeSeconds(): Long

/**
 * The same clock in Unix milliseconds.
 *
 * Something that has to act on a second boundary needs finer than a second to find it: a fixed
 * delay from the last wake drifts, and a countdown that drifts shows the same second twice and
 * then skips one. These milliseconds say how far into the current second the clock already is.
 */
expect fun currentTimeMillis(): Long
