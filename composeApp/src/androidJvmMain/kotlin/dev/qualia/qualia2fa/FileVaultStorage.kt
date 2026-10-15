package dev.qualia.qualia2fa

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The on-disk storage: `vault.q2fa` and the wrapped master key beside it.
 *
 * The vault is written through a temporary file and a move, so an interrupted write cannot
 * destroy the previous contents.
 *
 * The directory is a **parameter** rather than a platform lookup: where a device keeps its
 * files is the one thing the JVM and Android do not agree on — one reads an environment
 * variable, the other asks the application's context — and everything else about how the
 * vault is written is the same on both. Both platforms therefore use this class, each handing
 * it the directory it resolved.
 *
 * # The master key store belongs to the platform
 *
 * What protects the master key is the platform key store, and only the platform entry point
 * knows which one that is: the desktop opens the one of this machine, Android opens the
 * keystore of the device. Both end up with the same [FileWrappedMasterKeyStore], because what
 * is written beside the vault file is the same on both — which is what lets one account be
 * carried between them.
 *
 * A machine with no store that can hold a key-encryption key reports `null` here rather than
 * failing the whole storage: what it cannot do is keep a master key, and that is a state the
 * login screen can say out loud.
 */
class FileVaultStorage(
    private val dataDirectory: Path,
    /** The store this device's master key is kept in, or `null` on a machine without one. */
    override val masterKeyStore: MasterKeyStore?,
) : VaultStorage {
    private val vaultFile: Path = dataDirectory.resolve("vault.q2fa")

    override fun loadVault(): ByteArray? =
        if (Files.isRegularFile(vaultFile)) Files.readAllBytes(vaultFile) else null

    override fun saveVault(data: ByteArray) {
        Files.createDirectories(dataDirectory)
        val temporary = vaultFile.resolveSibling("vault.q2fa.tmp")
        Files.write(temporary, data)
        Files.move(temporary, vaultFile, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun reset() {
        // The key goes first, so an interrupted reset leaves a vault file that nothing can
        // open: the device reports itself as never registered, which is the truth, and
        // registering again writes over the orphan. The other order leaves a key with no
        // vault, which the device reports as a vault that went missing — also usable, but it
        // tells the user about a loss rather than about the reset they asked for.
        masterKeyStore?.delete()
        Files.deleteIfExists(vaultFile)
        Files.deleteIfExists(vaultFile.resolveSibling("vault.q2fa.tmp"))
    }

    companion object {
        /**
         * The label the master key of this device is kept under in the platform key store.
         *
         * One entry per machine rather than per vault file: the label names the
         * key-encryption key that protects whichever master key this device holds, and a
         * device holds one. It is fixed, so it cannot be tied to a file path that a later
         * version is free to change.
         */
        const val MASTER_KEY_LABEL: String = "qualia2fa-master-key"

        /**
         * The storage of one device: this directory, and the platform's wrapped master key
         * store beside the vault file.
         *
         * Returns `null` when this machine has no store that can hold a key-encryption key,
         * which the caller reports rather than working around.
         *
         * [label] names the entry in the platform key store, and the application passes
         * [MASTER_KEY_LABEL]; the parameter exists so that a test can open a second entry and
         * show that bytes wrapped under one do not unwrap under another.
         */
        fun open(
            dataDirectory: Path,
            label: String = MASTER_KEY_LABEL,
        ): FileVaultStorage? {
            val store =
                try {
                    FileWrappedMasterKeyStore(
                        path = dataDirectory.resolve("master.key"),
                        label = label,
                    )
                } catch (_: Exception) {
                    return null
                }
            return FileVaultStorage(dataDirectory = dataDirectory, masterKeyStore = store)
        }
    }
}
