package dev.qualia.qualia2fa

import dev.qualia.qualia2fa.ffi.FfiAlgorithm
import dev.qualia.qualia2fa.ffi.FfiDigits
import dev.qualia.qualia2fa.ffi.coreVersion as ffiCoreVersion
import dev.qualia.qualia2fa.ffi.generateSecretBase32 as ffiGenerateSecretBase32
import dev.qualia.qualia2fa.ffi.totpCode as ffiTotpCode

/**
 * The narrow facade over the generated UniFFI bindings.
 *
 * This is the only place in the application that names a generated type or function, so
 * the binding layer can be replaced, patched or forked without touching the UI. Callers
 * pass and receive plain Kotlin values: the generated `Ffi*` types and the `FfiException`
 * error type do not escape this file.
 *
 * The imports are aliased so that a facade member can never shadow the generated function
 * of the same name and turn a call into infinite recursion.
 */
object Qualia {
    /**
     * The version of the Rust core library that is actually loaded, as reported by the
     * library itself rather than by the build configuration.
     */
    fun coreVersion(): String = ffiCoreVersion()

    /** A fresh Base32 secret, drawn from the platform random number generator. */
    fun newSecret(): String = ffiGenerateSecretBase32()

    /**
     * The RFC 6238 code for [secretBase32] at [unixTime], or `null` if the secret is not
     * valid Base32 or the parameters are out of range.
     */
    fun totp(
        secretBase32: String,
        unixTime: Long,
        periodSeconds: Long = 30,
        digits: Int = 6,
    ): String? =
        try {
            ffiTotpCode(
                secretBase32 = secretBase32,
                unixTime = unixTime.toULong(),
                period = periodSeconds.toULong(),
                algorithm = FfiAlgorithm.SHA1,
                digits = if (digits == 8) FfiDigits.EIGHT else FfiDigits.SIX,
            )
        } catch (_: Exception) {
            null
        }
}
