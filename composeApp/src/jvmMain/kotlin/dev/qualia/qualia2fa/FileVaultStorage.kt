package dev.qualia.qualia2fa

import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The on-disk storage: `%APPDATA%\Qualia2FA\vault.q2fa` and the master key beside it.
 *
 * The vault is written through a temporary file and a move, so an interrupted write
 * cannot destroy the previous contents.
 */
class FileVaultStorage : VaultStorage {
    override fun loadVault(): ByteArray? =
        if (Files.isRegularFile(AppPaths.vaultFile)) Files.readAllBytes(AppPaths.vaultFile) else null

    override fun saveVault(data: ByteArray) {
        AppPaths.ensureDataDirectory()
        val temporary = AppPaths.vaultFile.resolveSibling("vault.q2fa.tmp")
        Files.write(temporary, data)
        Files.move(temporary, AppPaths.vaultFile, StandardCopyOption.REPLACE_EXISTING)
    }

    override val masterKeyStore: MasterKeyStore = FileMasterKeyStore(AppPaths.masterKeyFile)
}
