package dev.qualia.qualia2fa

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The on-disk storage: `vault.q2fa` and the master key beside it.
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
 * The master key store is a parameter for the same reason: Android wraps the key with one that
 * lives in the platform keystore, and the desktop keeps it in a file. The vault file itself is
 * identical on both, which is what lets one account be carried between them.
 */
class FileVaultStorage(
    private val dataDirectory: Path,
    override val masterKeyStore: MasterKeyStore = FileMasterKeyStore(dataDirectory.resolve("master.key")),
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
        masterKeyStore.delete()
        Files.deleteIfExists(vaultFile)
        Files.deleteIfExists(vaultFile.resolveSibling("vault.q2fa.tmp"))
    }
}
