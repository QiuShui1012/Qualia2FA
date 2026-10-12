package dev.qualia.qualia2fa

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The three HMAC algorithms, through the whole chain.
 *
 * # Why this is worth a test of its own
 *
 * Dropping the algorithm fails **silently**. An entry whose secret is SHA-256 but whose codes
 * are computed as SHA-1 still produces the right number of digits; they are simply rejected
 * by the server every time. Nothing in the application can tell the user that, so the only
 * place this can be caught is here.
 *
 * # Two implementations and a published vector
 *
 * The expected codes are computed in this file with the **JDK's own HMAC** — a second
 * implementation, written independently of the Rust one under test — and those results are
 * themselves checked against RFC 6238's Appendix B. Either alone would be weaker: an
 * independent implementation that is wrong in the same way as the one under test agrees with
 * it, and a copied table proves only that a constant was typed correctly once.
 */
class AlgorithmTest {
    /**
     * RFC 6238 Appendix B's secrets: the ASCII digits `1234567890` repeated to the block
     * length of the hash.
     */
    private val secrets =
        mapOf(
            OtpAlgorithm.Sha1 to "12345678901234567890",
            OtpAlgorithm.Sha256 to "12345678901234567890123456789012",
            OtpAlgorithm.Sha512 to
                "1234567890123456789012345678901234567890123456789012345678901234",
        )

    /** RFC 6238 Appendix B at T = 59 s, eight digits, a 30-second step. */
    private val published =
        mapOf(
            OtpAlgorithm.Sha1 to "94287082",
            OtpAlgorithm.Sha256 to "46119246",
            OtpAlgorithm.Sha512 to "90693936",
        )

    /** RFC 4648 §6, written here so the secret is not handed to the vault in its own dialect. */
    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    @Test
    fun everyAlgorithmComputesThePublishedCode() {
        val vault = assertNotNull(Qualia.createLocalVault(MemoryStore()), "a local vault must be creatable")

        for (algorithm in OtpAlgorithm.entries) {
            val secret = secrets.getValue(algorithm)
            val expected = published.getValue(algorithm)

            assertEquals(
                expected,
                codeFrom(secret.toByteArray(Charsets.US_ASCII), unixTime = 59, algorithm = algorithm, digits = 8, periodSeconds = 30),
                "this test's own arithmetic, for $algorithm",
            )

            val id =
                assertNotNull(
                    vault.addEntry(
                        name = algorithm.label,
                        issuer = "Qualia",
                        account = "test",
                        secretBase32 = base32(secret.toByteArray(Charsets.US_ASCII)),
                        algorithm = algorithm,
                        digits = 8,
                        periodSeconds = 30,
                        note = "",
                        unixTime = 59,
                    ),
                    "an entry using $algorithm must be accepted",
                )

            assertEquals(
                expected,
                vault.code(id, 59),
                "the vault must compute $algorithm the way RFC 6238 does",
            )
        }
    }

    /**
     * The failure mode itself: three entries, one secret, three algorithms — and therefore
     * three different codes at the same second.
     */
    @Test
    fun theThreeAlgorithmsDisagreeAboutOneSecret() {
        val vault = assertNotNull(Qualia.createLocalVault(MemoryStore()))
        val secret = base32(secrets.getValue(OtpAlgorithm.Sha256).toByteArray(Charsets.US_ASCII))

        val codes =
            OtpAlgorithm.entries.map { algorithm ->
                val id =
                    assertNotNull(
                        vault.addEntry(
                            name = algorithm.label,
                            issuer = "Qualia",
                            account = "test",
                            secretBase32 = secret,
                            algorithm = algorithm,
                            digits = 8,
                            periodSeconds = 30,
                            note = "",
                            unixTime = 59,
                        ),
                    )
                assertNotNull(vault.code(id, 59), "$algorithm must produce a code")
            }

        assertEquals(
            codes.size,
            codes.toSet().size,
            "one secret with three algorithms produced ${codes.toSet().size} distinct code(s): the algorithm is being dropped",
        )
    }

    /** The shape a real service hands out: an algorithm, eight digits and a longer step. */
    @Test
    fun aLinkCarriesItsAlgorithmAndItsStep() {
        val link =
            assertNotNull(
                Qualia.parseOtpAuth(
                    "otpauth://totp/NuGet:tester447%40nuget.org" +
                        "?secret=BLZMYP76JDMBUBI4GDZQIZRTPYTN66XZ&issuer=NuGet" +
                        "&algorithm=SHA256&digits=8&period=60",
                ),
                "the link a real service hands out must parse",
            )

        assertEquals("NuGet", link.issuer)
        assertEquals("tester447@nuget.org", link.account)
        assertEquals("BLZMYP76JDMBUBI4GDZQIZRTPYTN66XZ", link.secretBase32)
        assertEquals(OtpAlgorithm.Sha256, link.algorithm)
        assertEquals(8, link.digits)
        assertEquals(60L, link.periodSeconds)

        // And the entry built from it computes what the same three parameters say it should.
        val vault = assertNotNull(Qualia.createLocalVault(MemoryStore()))
        val id =
            assertNotNull(
                vault.addEntry(
                    name = "NuGet",
                    issuer = link.issuer,
                    account = link.account,
                    secretBase32 = link.secretBase32,
                    algorithm = link.algorithm,
                    digits = link.digits,
                    periodSeconds = link.periodSeconds,
                    note = "",
                    unixTime = 1_700_000_000,
                ),
            )

        assertEquals(
            codeFrom(
                // The link carries the secret in Base32, so the reference decodes it here
                // rather than being handed the key: the decoding is part of what is checked.
                secret = base32Decode(link.secretBase32),
                unixTime = 1_700_000_000,
                algorithm = OtpAlgorithm.Sha256,
                digits = 8,
                periodSeconds = 60,
            ),
            vault.code(id, 1_700_000_000),
        )
    }

    /** A link that names no algorithm means SHA-1: that is the scheme's own default. */
    @Test
    fun aLinkWithoutAnAlgorithmMeansSha1() {
        val link =
            assertNotNull(
                Qualia.parseOtpAuth("otpauth://totp/Qualia:test?secret=JBSWY3DPEHPK3PXP"),
            )
        assertEquals(OtpAlgorithm.Sha1, link.algorithm)
    }

    /** A link naming an algorithm nobody implements is refused, not downgraded. */
    @Test
    fun anUnknownAlgorithmIsRefused() {
        val link =
            Qualia.parseOtpAuth(
                "otpauth://totp/Qualia:test?secret=JBSWY3DPEHPK3PXP&algorithm=SHA3-256",
            )
        assertEquals(null, link, "an unrecognised algorithm must not fall back to SHA-1")
    }

    /**
     * An independent TOTP: RFC 4226 §5.3's truncation over RFC 6238 §4.2's counter, with the
     * JDK's HMAC. [secret] is the raw key, already decoded.
     */
    private fun codeFrom(
        secret: ByteArray,
        unixTime: Long,
        algorithm: OtpAlgorithm,
        digits: Int,
        periodSeconds: Long,
    ): String {
        val counter = unixTime / periodSeconds
        val message = ByteArray(8) { index -> ((counter shr (8 * (7 - index))) and 0xFF).toByte() }

        val mac =
            Mac.getInstance(
                when (algorithm) {
                    OtpAlgorithm.Sha1 -> "HmacSHA1"
                    OtpAlgorithm.Sha256 -> "HmacSHA256"
                    OtpAlgorithm.Sha512 -> "HmacSHA512"
                },
            )
        mac.init(SecretKeySpec(secret, mac.algorithm))

        val hash = mac.doFinal(message)
        val offset = hash.last().toInt() and 0x0F
        val truncated =
            ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)

        var modulus = 1
        repeat(digits) { modulus *= 10 }
        return (truncated % modulus).toString().padStart(digits, '0')
    }

    /** Base32, upper case, without padding: what the vault stores and the scheme carries. */
    private fun base32(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(alphabet[(buffer shr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        if (bits > 0) {
            out.append(alphabet[(buffer shl (5 - bits)) and 0x1F])
        }
        return out.toString()
    }

    /** RFC 4648 §6 decoding, written here for the same reason the encoder is. */
    private fun base32Decode(text: String): ByteArray {
        val clean = text.trim().trimEnd('=').uppercase()
        val out = ByteArray(clean.length * 5 / 8)
        var buffer = 0
        var bits = 0
        var written = 0
        for (character in clean) {
            val value = alphabet.indexOf(character)
            require(value >= 0) { "not Base32: '$character'" }
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                out[written++] = ((buffer shr (bits - 8)) and 0xFF).toByte()
                bits -= 8
            }
        }
        return out
    }
}
