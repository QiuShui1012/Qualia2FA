package dev.qualia.qualia2fa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A master key store kept in memory, so the tests never touch the user's files. */
private class MemoryStore(initial: ByteArray? = null) : MasterKeyStore {
    private var key: ByteArray? = initial

    override fun load(): ByteArray? = key

    override fun store(key: ByteArray) {
        // The contract requires a copy: the caller wipes its buffer right after this.
        this.key = key.copyOf()
    }

    override fun delete() {
        key = null
    }

    override val isHardwareBacked: Boolean = false
}

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
     * sends a request, and the host authorises it, which is the call that hands over the
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
        assertNotNull(hostVault.addEntry("Shared", "Example", "", secret, 6, 30, "", 1_700_000_000))
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

        val replyText = assertNotNull(host.replyTo(requestText, hostVault), "the host must authorise the request")
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

        assertEquals(
            text,
            Qualia.qrTextFromImage(image.pixels, image.width, image.height),
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
                digits = 6,
                periodSeconds = 30,
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
        val id = assertNotNull(vault.addEntry("Test", "", "", secret, 6, 30, "", 1_700_000_000))
        val before = vault.code(id, 59)

        val other = "MZXW6YTBOI======"
        assertTrue(
            vault.updateEntry(id, "Renamed", "Issuer", "", other, 6, 30, "", 1_700_000_001),
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
        val id = assertNotNull(vault.addEntry("Test", "", "", secret, 6, 30, "", 1_700_000_000))

        assertTrue(!vault.updateEntry(id, "Bad", "", "", "not base32!", 6, 30, "", 1_700_000_001))
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
        assertNotNull(vault.addEntry("Test", "Example", "", secret, 6, 30, "", 1_700_000_000))
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
            vault.addEntry("Test", "", "", secret, 6, 30, "", 1_700_000_000),
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
            vault.addEntry("Bad", "", "", "not base32!", 6, 30, "", 1_700_000_000),
            "an invalid secret must be rejected rather than stored",
        )
        assertEquals(0, vault.entryCount)
    }
}
