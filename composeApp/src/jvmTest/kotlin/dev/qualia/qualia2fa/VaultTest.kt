package dev.qualia.qualia2fa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultTest {
    private val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    /**
     * Registration is the path that creates a device identity, which is what pairing and
     * syncing later prove. It needs a working platform key store, so a machine without one
     * skips rather than fails — and says so, because a silently skipped test is a test
     * nobody knows the state of.
     *
     * The label is fixed, and the key store reuses the key pair for a label, so repeated
     * runs leave one identity behind rather than one per run. It is not deleted here: the
     * facade has no delete yet, and deleting a device identity is the kind of thing a user
     * should confirm anyway.
     */
    @Test
    fun registrationCreatesADeviceIdentity() {
        val store = MemoryStore()
        val registration = Qualia.register(store, "qualia2fa-test-device")
        if (registration == null) {
            println("skipping: no usable platform key store on this machine")
            return
        }

        assertTrue(
            registration.backend == "tpm" || registration.backend == "software",
            "the backend must be reported as one of the two, got ${registration.backend}",
        )
        assertEquals(
            registration.backend == "tpm",
            registration.hardwareBacked,
            "the hardware flag must agree with the backend it reports",
        )
        assertEquals(0, registration.vault.entryCount, "a fresh registration has no entries")
        assertNotNull(store.load(), "registration must write the master key to the store")

        val data = assertNotNull(registration.vault.serialize())
        assertNotNull(Qualia.openVault(data, store), "the registered vault must reopen")
    }

    /**
     * A whole pairing, both sides, in one process.
     *
     * The host registers, starts a pairing and hands out its payload; the joiner reads it,
     * sends a request, and the host authorizes it, which is the call that hands over the
     * master key. The joiner must then be able to open the **host's** vault file, which is
     * the property that makes the whole flow worth anything.
     *
     * It needs a platform key store on both sides, so a machine without one skips.
     */
    @Test
    fun twoDevicesCompleteAPairing() {
        val hostStore = MemoryStore()
        val registration = Qualia.register(hostStore, "qualia2fa-test-host")
        if (registration == null) {
            println("skipping: no usable platform key store on this machine")
            return
        }

        val hostVault = registration.vault
        assertNotNull(hostVault.addEntry("Shared", "Example", "", secret, OtpAlgorithm.Sha1, 6, 30, null, "", 1_700_000_000))
        val hostVaultFile = assertNotNull(hostVault.serialize())

        val host = assertNotNull(Qualia.beginPairing(), "the host side must start")
        val payload = host.payload
        assertTrue(payload.isNotBlank(), "the pairing payload must not be empty")

        val joiner = assertNotNull(
            Qualia.joinPairing(payload, "qualia2fa-test-joiner"),
            "the payload must be accepted as a pairing code",
        )
        // Everything after the first payload travels in the text form a code channel can
        // carry, which is the path the user interface takes.
        val requestText = assertNotNull(joiner.requestText(), "the joiner must produce a request")
        assertNotNull(joiner.identity(), "the joiner must have an identity once it asked")

        val replyText = assertNotNull(host.replyTo(requestText, hostVault), "the host must authorize the request")
        assertNotNull(host.joinerDeviceId, "the host must be able to show who is asking")

        val joinerStore = MemoryStore()
        val joined = assertNotNull(joiner.completeText(replyText, joinerStore), "the joiner must complete")
        assertEquals(0, joined.entryCount, "pairing hands over the key, not the entries")

        val asSeenByJoiner = assertNotNull(
            Qualia.openVault(hostVaultFile, joinerStore),
            "the joined device must be able to open the host's vault",
        )
        assertEquals(1, asSeenByJoiner.entryCount)
        assertEquals("Shared", asSeenByJoiner.entries().first().name)

        assertNull(
            Qualia.joinPairing("not a pairing code", "qualia2fa-test-joiner"),
            "a payload that is not a pairing code must be rejected",
        )
    }

    /**
     * The code channel through the facade: the text the interface would draw as a code
     * comes back out of the pixels, and the two ways it can fail come back as `null`
     * rather than as an exception the user interface would have to catch.
     */
    @Test
    fun aQrCodeRoundTripsThroughTheFacade() {
        val text = "otpauth://totp/Example:alice?secret=MZXW6YTBOI"
        val image = assertNotNull(Qualia.qrImage(text), "the text must fit in a code")
        assertEquals(image.width, image.height, "a QR code is square")
        assertTrue(image.width > 20, "the code must have real modules in it")
        assertEquals(image.width * image.height, image.pixels.size)

        // A code drawn by this application carries the transport's envelope, so the text read back
        // out of the pixels is the envelope and not what was drawn: unwrapping it is what gives
        // back the text that went in.
        val read = assertNotNull(
            Qualia.qrTextFromImage(image.pixels, image.width, image.height),
            "a code this application drew must be readable",
        )
        assertEquals(
            text,
            Qualia.unwrapQrText(read),
            "the text must come back out of the pixels",
        )

        assertNull(Qualia.qrImage("a".repeat(8_000)), "text that cannot fit must be refused, not truncated")
        assertNull(
            Qualia.qrTextFromImage(ByteArray(64 * 64) { -1 }, 64, 64),
            "a blank image has no code in it",
        )
    }

    @Test
    fun aLocalVaultRoundTripsThroughItsFile() {
        val store = MemoryStore()
        val vault = assertNotNull(Qualia.createLocalVault(store), "creating a vault must succeed")

        val id = assertNotNull(
            vault.addEntry(
                name = "Test",
                issuer = "Example",
                account = "alice@example.com",
                secretBase32 = secret,
                algorithm = OtpAlgorithm.Sha1,
                digits = 6,
                periodSeconds = 30,
                counter = null,
                note = "",
                unixTime = 1_700_000_000,
            ),
            "adding an entry must succeed",
        )

        assertEquals(1, vault.entryCount)
        assertEquals(Qualia.totp(secret, 59), vault.code(id, 59))

        val data = assertNotNull(vault.serialize(), "serializing must succeed")

        val reopened = assertNotNull(Qualia.openVault(data, store), "the key must open its own file")
        assertEquals(1, reopened.entryCount)
        assertEquals("Test", reopened.entries().first().name)
        assertEquals("Example", reopened.entries().first().issuer)
    }

    @Test
    fun editingKeepsTheIdentifierAndChangesTheCode() {
        val store = MemoryStore()
        val vault = assertNotNull(Qualia.createLocalVault(store))
        val id = assertNotNull(vault.addEntry("Test", "", "", secret, OtpAlgorithm.Sha1, 6, 30, null, "", 1_700_000_000))
        val before = vault.code(id, 59)

        val other = "MZXW6YTBOI======"
        assertTrue(
            vault.updateEntry(id, "Renamed", "Issuer", "", other, OtpAlgorithm.Sha1, 6, 30, null, "", 1_700_000_001),
            "editing must succeed",
        )

        assertEquals(1, vault.entryCount, "editing must not add an entry")
        val entry = assertNotNull(vault.entries().firstOrNull())
        assertEquals(id, entry.id, "the identifier must not change")
        assertEquals("Renamed", entry.name)
        assertEquals(Qualia.totp(other, 59), vault.code(id, 59))
        assertTrue(before != vault.code(id, 59), "the code must follow the new secret")
    }

    @Test
    fun editingARejectedSecretChangesNothing() {
        val store = MemoryStore()
        val vault = assertNotNull(Qualia.createLocalVault(store))
        val id = assertNotNull(vault.addEntry("Test", "", "", secret, OtpAlgorithm.Sha1, 6, 30, null, "", 1_700_000_000))

        assertTrue(!vault.updateEntry(id, "Bad", "", "", "not base32!", OtpAlgorithm.Sha1, 6, 30, null, "", 1_700_000_001))
        assertEquals("Test", assertNotNull(vault.entries().firstOrNull()).name)
    }

    /**
     * A recovery file carries the master key, not the entries: the entries live in the
     * vault file, so recovering on another device means importing the file **and** having
     * the vault file (which is what a backup is). This test walks exactly that path.
     */
    @Test
    fun aRecoveryFileRestoresTheKeyThatOpensTheVault() {
        val store = MemoryStore()
        val vault = assertNotNull(Qualia.createLocalVault(store))
        assertNotNull(vault.addEntry("Test", "Example", "", secret, OtpAlgorithm.Sha1, 6, 30, null, "", 1_700_000_000))
        val vaultData = assertNotNull(vault.serialize())

        val file = assertNotNull(vault.recoveryFile("correct horse battery staple"), "exporting must succeed")
        assertTrue(file.size > 100, "the recovery file must not be the bare key")

        val fresh = MemoryStore()
        val unlocked = assertNotNull(
            Qualia.importRecoveryFile(file, "correct horse battery staple", fresh),
            "the passphrase must restore the key",
        )
        assertEquals(0, unlocked.entryCount, "the recovery file carries no entries")
        assertNotNull(fresh.load(), "the imported master key must reach the store")

        val reopened = assertNotNull(
            Qualia.openVault(vaultData, fresh),
            "the recovered key must open the vault file",
        )
        assertEquals(1, reopened.entryCount)
        assertEquals("Test", reopened.entries().first().name)

        assertNull(
            Qualia.importRecoveryFile(file, "wrong passphrase", MemoryStore()),
            "a wrong passphrase must not unlock anything",
        )
    }

    @Test
    fun anotherKeyCannotOpenTheFile() {
        val store = MemoryStore()
        val vault = assertNotNull(Qualia.createLocalVault(store))
        val data = assertNotNull(vault.serialize())

        val stranger = MemoryStore(ByteArray(32) { 7 })
        assertNull(Qualia.openVault(data, stranger), "a different master key must not open the vault")
    }

    @Test
    fun removalLeavesTheVaultEmpty() {
        val store = MemoryStore()
        val vault = assertNotNull(Qualia.createLocalVault(store))
        val id = assertNotNull(
            vault.addEntry("Test", "", "", secret, OtpAlgorithm.Sha1, 6, 30, null, "", 1_700_000_000),
        )

        assertTrue(vault.removeEntry(id, 1_700_000_001), "removing an entry must report success")
        assertEquals(0, vault.entryCount)
        assertTrue(vault.isEmpty)
    }

    @Test
    fun invalidSecretsAreRejected() {
        val store = MemoryStore()
        val vault = assertNotNull(Qualia.createLocalVault(store))

        assertNull(
            vault.addEntry("Bad", "", "", "not base32!", OtpAlgorithm.Sha1, 6, 30, null, "", 1_700_000_000),
            "an invalid secret must be rejected rather than stored",
        )
        assertEquals(0, vault.entryCount)
    }

    /**
     * Adds a counter-based entry of the RFC's own seed and returns its identifier.
     *
     * The seed is the ASCII `"12345678901234567890"` that RFC 4226 and RFC 6238 both publish, in
     * Base32, which is what makes the vectors below reproducible rather than merely stable.
     */
    private fun addCounterEntry(vault: Vault, counter: Long = 0): String =
        assertNotNull(
            vault.addEntry(
                name = "RFC 4226",
                issuer = "Qualia",
                account = "appendix-d",
                secretBase32 = secret,
                algorithm = OtpAlgorithm.Sha1,
                digits = 6,
                periodSeconds = 30,
                counter = counter,
                note = "",
                unixTime = 1_700_000_000,
            ),
            "a counter-based entry must be accepted",
        )

    /**
     * **RFC 4226 Appendix D**: a counter-based entry produces the published HOTP values.
     *
     * All ten vectors of the appendix are pinned, and they are the ones the core crate pins in
     * its own `rfc_vectors` test, so this also says the fields survived both boundaries on the
     * way to the HMAC: a counter, a digit count or a secret lost in between would produce a
     * different code rather than an error, and a test that only asked for "six digits came out"
     * would pass for every one of them.
     */
    @Test
    fun aCounterBasedEntryProducesTheRfc4226AppendixDVectors() {
        // RFC 4226 Appendix D, transcribed line by line: the Count column and the 6-digit
        // decimal HOTP value beside it.
        val vectors =
            listOf(
                0L to "755224",
                1L to "287082",
                2L to "359152",
                3L to "969429",
                4L to "338314",
                5L to "254676",
                6L to "287922",
                7L to "162583",
                8L to "399871",
                9L to "520489",
            )

        val vault = assertNotNull(Qualia.createLocalVault(MemoryStore()), "a local vault must be creatable")
        val id = addCounterEntry(vault)

        val stored = assertNotNull(vault.entries().firstOrNull { it.id == id }, "the entry must be in the vault")
        assertTrue(stored.isCounterBased, "an entry added with a counter must come back counter based")
        assertEquals(0L, stored.counter, "the counter must survive the vault as the counter it was given")

        for ((counter, want) in vectors) {
            assertEquals(want, vault.counterCode(id, counter), "RFC 4226 Appendix D, Count = $counter")
        }
    }

    /**
     * A counter is consumed by use, so the entry moves to the next one — and the move is what the
     * vault file keeps.
     *
     * This is the whole reason the counter is stored rather than derived: the code a server
     * accepted belongs to the counter it was computed at, and the next code has to be the next
     * one, across a restart as well.
     */
    @Test
    fun aCounterBasedEntryMovesToItsNextCounterAndKeepsItInTheFile() {
        val store = MemoryStore()
        val vault = assertNotNull(Qualia.createLocalVault(store))
        val id = addCounterEntry(vault)

        assertEquals("755224", vault.counterCode(id, 0), "the first code is the first appendix D vector")

        assertTrue(vault.advanceCounter(id, 1_700_000_001), "the counter must advance")
        val moved = assertNotNull(vault.entries().firstOrNull { it.id == id }, "the entry must still be there")
        assertEquals(1L, moved.counter, "the stored counter must be the next one")
        assertEquals(
            "287082",
            vault.codeFor(moved, 1_700_000_001),
            "the card must show the code of the counter the entry now holds, not of the one it had",
        )

        val reopened =
            assertNotNull(
                Qualia.openVault(assertNotNull(vault.serialize(), "the vault must serialize"), store),
                "the key must open its own file",
            )
        val stored = assertNotNull(reopened.entries().firstOrNull { it.id == id }, "the entry must come back")
        assertEquals(1L, stored.counter, "the counter must survive the file")
        assertEquals("287082", reopened.codeFor(stored, 1_700_000_002), "and it must still be the code shown")
    }

    /**
     * The two kinds answer for themselves and refuse each other.
     *
     * A time-based code asked of a counter-based entry would be a plausible code that never
     * works, and the reverse is the same mistake; the vault is where that is caught, so a screen
     * cannot make it by asking the wrong one.
     */
    @Test
    fun theTwoKindsOfCodeRefuseEachOther() {
        val vault = assertNotNull(Qualia.createLocalVault(MemoryStore()))
        val counterId = addCounterEntry(vault)
        val timeId =
            assertNotNull(
                vault.addEntry("RFC 6238", "Qualia", "appendix-b", secret, OtpAlgorithm.Sha1, 6, 30, null, "", 1_700_000_000),
                "a time-based entry must be accepted",
            )

        assertNull(vault.code(counterId, 59), "a counter-based entry has no code at a moment in time")
        assertNull(vault.counterCode(timeId, 0), "a time-based entry has no code at a counter")
        assertEquals(Qualia.totp(secret, 59), vault.code(timeId, 59), "the time-based entry is unaffected")

        assertFalse(vault.advanceCounter(timeId, 1_700_000_001), "a time-based entry has no counter to advance")
        assertFalse(vault.advanceCounter("0".repeat(32), 1_700_000_001), "an entry that does not exist cannot move")

        // An edit that drops the counter is what turns an entry back into a time-based one.
        assertTrue(
            vault.updateEntry(
                id = counterId,
                name = "RFC 4226",
                issuer = "Qualia",
                account = "appendix-d",
                secretBase32 = secret,
                algorithm = OtpAlgorithm.Sha1,
                digits = 6,
                periodSeconds = 30,
                counter = null,
                note = "",
                unixTime = 1_700_000_002,
            ),
            "the edit must be accepted",
        )
        val edited = assertNotNull(vault.entries().firstOrNull { it.id == counterId }, "the entry must be there")
        assertFalse(edited.isCounterBased, "dropping the counter must make the entry time based")
        assertEquals(Qualia.totp(secret, 59), vault.code(counterId, 59))
    }
}
