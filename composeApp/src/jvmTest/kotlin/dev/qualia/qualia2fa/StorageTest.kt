package dev.qualia.qualia2fa

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What the on-disk storage keeps, and what a reset takes away.
 *
 * A reset is the one destructive thing this application offers, so it is checked against the
 * files themselves rather than against a test double: what it promises is that the two files a
 * registered device leaves behind are gone afterward, and a fake store would only show that
 * the call was made.
 */
class StorageTest {
    /** A storage in a fresh temporary directory, with the directory it was given. */
    private class Temporary(val directory: Path) {
        val storage: FileVaultStorage = FileVaultStorage(directory)
    }

    private fun temporary(): Temporary = Temporary(Files.createTempDirectory("qualia2fa-storage"))

    @Test
    fun theVaultReadsBackFromANewStorage() {
        val temporary = temporary()
        assertNull(temporary.storage.loadVault(), "a directory with nothing in it holds no vault")

        val data = byteArrayOf(1, 2, 3, 4)
        temporary.storage.saveVault(data)
        assertEquals(
            data.toList(),
            FileVaultStorage(temporary.directory).loadVault()?.toList(),
            "the vault reads back from a storage that did not write it, which is what a restart does",
        )
    }

    @Test
    fun aResetLeavesTheDeviceAsItWasBeforeItWasRegistered() {
        val storage = temporary().storage
        storage.saveVault(byteArrayOf(9, 9, 9))
        storage.masterKeyStore.store(ByteArray(Qualia.MASTER_KEY_BYTES) { 7 })
        assertNotNull(storage.loadVault())
        assertNotNull(storage.masterKeyStore.load())

        storage.reset()

        assertNull(storage.loadVault(), "the vault file must be gone")
        assertNull(storage.masterKeyStore.load(), "the master key must be gone")
        assertFalse(
            Qualia.hasMasterKey(storage.masterKeyStore),
            "a reset device must report itself as never registered",
        )
    }

    @Test
    fun resettingADeviceThatHasNothingIsNotAnError() {
        val storage = temporary().storage
        storage.reset()
        storage.reset()
        assertNull(storage.loadVault())
        assertNull(storage.masterKeyStore.load())
    }

    @Test
    fun aRealVaultIsRemovedByAReset() {
        val storage = temporary().storage
        val vault = Qualia.createLocalVault(storage.masterKeyStore)
        assertNotNull(vault, "a local vault is the one path that needs no platform key store")
        storage.saveVault(requireNotNull(vault.serialize()))

        storage.reset()

        assertNull(storage.loadVault())
        assertNull(storage.masterKeyStore.load())
    }

    @Test
    fun aResetLeavesNoTemporaryFileBehind() {
        val temporary = temporary()
        temporary.storage.saveVault(byteArrayOf(1))
        temporary.storage.reset()

        val leftovers =
            Files.list(temporary.directory).use { entries ->
                entries.map { it.fileName.toString() }.toList()
            }
        assertEquals(emptyList(), leftovers, "a reset must leave the directory empty, not a stray temporary")
    }
}
