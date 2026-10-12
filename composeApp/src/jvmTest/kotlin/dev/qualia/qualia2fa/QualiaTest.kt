package dev.qualia.qualia2fa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the whole chain — Kotlin, JNA, the Rust cdylib — on the host platform.
 *
 * The expected codes are the RFC 6238 Appendix B vectors, so a passing test also says the
 * library that got loaded is the real core and not a stale one.
 */
class QualiaTest {
    @Test
    fun coreVersionIsReportedByTheLoadedLibrary() {
        val version = Qualia.coreVersion()
        assertTrue(version.isNotBlank(), "core_version returned an empty string")
    }

    @Test
    fun generatedSecretsAreUsable() {
        val secret = Qualia.newSecret()
        assertTrue(secret.length >= 16, "generated secret is too short: ${secret.length}")
        assertTrue(Qualia.totp(secret, 1_700_000_000) != null, "the generated secret must produce a code")
    }

    @Test
    fun totpMatchesTheRfc6238Vectors() {
        // RFC 6238 Appendix B: the ASCII secret "12345678901234567890" in Base32.
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        assertEquals("94287082", Qualia.totp(secret, 59, 30, 8))
        assertEquals("07081804", Qualia.totp(secret, 1_111_111_109, 30, 8))
        assertEquals("14050471", Qualia.totp(secret, 1_111_111_111, 30, 8))
    }

    @Test
    fun invalidSecretsFailInsteadOfThrowing() {
        assertEquals(null, Qualia.totp("not base32!", 59))
    }
}
