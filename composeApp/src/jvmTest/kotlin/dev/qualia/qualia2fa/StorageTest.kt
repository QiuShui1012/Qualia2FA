package dev.qualia.qualia2fa

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        val storage: FileVaultStorage =
            assertNotNull(
                FileVaultStorage.open(directory),
                "this machine must have a platform key store for the storage tests to mean anything",
            )
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
            assertNotNull(FileVaultStorage.open(temporary.directory)).loadVault()?.toList(),
            "the vault reads back from a storage that did not write it, which is what a restart does",
        )
    }

    @Test
    fun aResetLeavesTheDeviceAsItWasBeforeItWasRegistered() {
        val storage = temporary().storage
        storage.saveVault(byteArrayOf(9, 9, 9))
        assertNotNull(storage.masterKeyStore).store(ByteArray(Qualia.MASTER_KEY_BYTES) { 7 })
        assertNotNull(storage.loadVault())
        assertNotNull(assertNotNull(storage.masterKeyStore).load())

        storage.reset()

        assertNull(storage.loadVault(), "the vault file must be gone")
        assertNull(assertNotNull(storage.masterKeyStore).load(), "the master key must be gone")
        assertFalse(
            Qualia.hasMasterKey(assertNotNull(storage.masterKeyStore)),
            "a reset device must report itself as never registered",
        )
    }

    @Test
    fun resettingADeviceThatHasNothingIsNotAnError() {
        val storage = temporary().storage
        storage.reset()
        storage.reset()
        assertNull(storage.loadVault())
        assertNull(assertNotNull(storage.masterKeyStore).load())
    }

    @Test
    fun aRealVaultIsRemovedByAReset() {
        val storage = temporary().storage
        val vault = Qualia.createLocalVault(assertNotNull(storage.masterKeyStore))
        assertNotNull(vault, "a local vault is the one path that needs no platform identity")
        storage.saveVault(requireNotNull(vault.serialize()))

        storage.reset()

        assertNull(storage.loadVault())
        assertNull(assertNotNull(storage.masterKeyStore).load())
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

    /**
     * What the application persists is the wrapped form, and the wrapped form is not the key.
     *
     * This is the property the whole split between the two halves rests on: the file in the
     * data directory is what a backup or a thief would carry away, and it must not be
     * something that opens the vault on its own.
     */
    @Test
    fun theMasterKeyIsPersistedInItsWrappedForm() {
        val temporary = temporary()
        val store = assertNotNull(temporary.storage.masterKeyStore)
        val key = ByteArray(Qualia.MASTER_KEY_BYTES) { (it * 7).toByte() }
        store.store(key)

        val written = Files.readAllBytes(temporary.directory.resolve(MASTER_KEY_FILE))
        assertFalse(
            key.contentEquals(written),
            "the master key must not be what is written to the data directory",
        )
        assertEquals(key.toList(), assertNotNull(store.load())?.toList(), "the key must read back")
    }

    /** The round trip through the machine's own platform key store, and what it says about
     * itself.
     *
     * The application's own path, end to end: a storage in a temporary directory, a master
     * key stored through it, a second storage over the same directory reading it back, and a
     * vault opened with what came back. The backend and the hardware flag are printed rather
     * than asserted, because both are properties of the machine this runs on: a CI runner has
     * no usable TPM, and a test that demanded one would fail there rather than say so.
     */
    @Test
    fun theWrappedFormRoundTripsThroughThePlatformStore() {
        val directory = Files.createTempDirectory("qualia2fa-wrapped")
        val storage = assertNotNull(FileVaultStorage.open(directory))
        val store = assertNotNull(storage.masterKeyStore)

        println("platform master key store: backend=${store.backend} hardwareBacked=${store.isHardwareBacked}")

        val vault = assertNotNull(
            Qualia.createLocalVault(store),
            "creating a local vault through the wrapped store must succeed",
        )
        assertNotNull(
            vault.addEntry(
                "Round trip",
                "Example",
                "",
                "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
                OtpAlgorithm.Sha1,
                6,
                30,
                null,
                "",
                1_700_000_000,
            ),
        )
        val data = assertNotNull(vault.serialize())
        storage.saveVault(data)
        assertTrue(store.exists, "the wrapped bytes must be on this device after storing a key")

        val reopened = assertNotNull(
            FileVaultStorage.open(directory),
            "the platform store must be openable a second time",
        )
        val reopenedStore = assertNotNull(reopened.masterKeyStore)
        val reopenedVault = assertNotNull(
            Qualia.openVault(assertNotNull(reopened.loadVault()), reopenedStore),
            "a second storage over the same directory must open the vault with what was persisted",
        )
        assertEquals(1, reopenedVault.entryCount)
        assertTrue(
            store.backend == reopenedStore.backend,
            "one machine must not report two backends for the same store",
        )

        // What the application persists is bound to the entry that produced it: another label
        // is another key-encryption key inside the platform store, and the bytes below unwrap
        // only under the one that produced them. The bytes are copied from the first
        // directory, which is what an installation with a different label would have to do to
        // find itself holding another device's file — and the answer must be that it cannot
        // read it, not that it reads a different master key.
        val otherDirectory = Files.createTempDirectory("qualia2fa-other-label")
        Files.copy(
            directory.resolve(MASTER_KEY_FILE),
            otherDirectory.resolve(MASTER_KEY_FILE),
            StandardCopyOption.REPLACE_EXISTING,
        )
        val otherLabel =
            assertNotNull(
                FileVaultStorage.open(
                    dataDirectory = otherDirectory,
                    label = "qualia2fa-other-label",
                ),
            )
        assertNull(
            otherLabel.masterKeyStore?.load(),
            "bytes wrapped under one label must not unwrap under another",
        )
    }

    /**
     * A machine with no platform key store is reported rather than worked around.
     *
     * The store is what the application asks before it offers to create a vault, so the case
     * has to be answerable. This machine has one — the test above proves it — so what is
     * checked here is the shape of the answer: a store that exists names its backend, and the
     * hardware flag follows that name.
     */
    @Test
    fun theStoreReportsItsBackendTruthfully() {
        val storage = temporary().storage
        val store = assertNotNull(storage.masterKeyStore)
        assertTrue(
            store.backend in setOf("tpm", "secure-enclave", "android-keystore", "software"),
            "the backend must be one the library names, got '${store.backend}'",
        )
        assertEquals(
            store.backend == "tpm" || store.backend == "secure-enclave" || store.backend == "android-keystore",
            store.isHardwareBacked,
            "the hardware flag must agree with the backend it reports",
        )
    }

    private companion object {
        /** The name the wrapped form is written under, beside the vault file. */
        const val MASTER_KEY_FILE = "master.key"
    }
}
