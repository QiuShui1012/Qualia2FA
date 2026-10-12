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
