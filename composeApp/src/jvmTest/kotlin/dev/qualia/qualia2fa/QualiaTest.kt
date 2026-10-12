package dev.qualia.qualia2fa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    @Test
    fun anOtpauthLinkIsParsedIntoTheFieldsAnEntryNeeds() {
        val link =
            assertNotNull(
                Qualia.parseOtpAuth(
                    "otpauth://totp/ACME%20Co:alice%40example.com" +
                        "?secret=JBSWY3DPEHPK3PXP&issuer=ACME%20Co&digits=8&period=60",
                ),
                "a well-formed otpauth link must parse",
            )

        assertEquals("ACME Co", link.issuer)
        assertEquals("alice@example.com", link.account)
        assertEquals("JBSWY3DPEHPK3PXP", link.secretBase32)
        assertEquals(8, link.digits)
        assertEquals(60L, link.periodSeconds)
    }

    @Test
    fun linksThatAreNotOtpauthAreRefused() {
        assertEquals(null, Qualia.parseOtpAuth("https://example.com/?secret=JBSWY3DPEHPK3PXP"))
        assertEquals(null, Qualia.parseOtpAuth("otpauth://totp/alice?secret=not%20base32"))
        assertEquals(null, Qualia.parseOtpAuth(""))
    }

    /**
     * The path the "+" menu's link option takes: parse a link, add what it describes, and
     * compute a code from it.
     *
     * The expected code is RFC 6238's vector, so this also says the fields were carried
     * across intact — a digit count or a period lost on the way would produce a different
     * code rather than an error.
     */
    @Test
    fun anEntryImportedFromALinkProducesTheRfcCode() {
        val link =
            assertNotNull(
                Qualia.parseOtpAuth(
                    "otpauth://totp/Qualia:test?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&digits=8&period=30",
                ),
            )

        val store = MemoryStore()
        val vault = assertNotNull(Qualia.createLocalVault(store), "a local vault must be creatable")
        val id =
            assertNotNull(
                vault.addEntry(
                    name = "Qualia",
                    issuer = link.issuer,
                    account = link.account,
                    secretBase32 = link.secretBase32,
                    digits = link.digits,
                    periodSeconds = link.periodSeconds,
                    note = "",
                    unixTime = 59,
                ),
                "the entry the link describes must be accepted",
            )

        assertEquals("94287082", vault.code(id, 59))
    }
}
