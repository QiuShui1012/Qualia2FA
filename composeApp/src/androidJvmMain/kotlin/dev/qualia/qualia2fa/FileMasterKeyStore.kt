package dev.qualia.qualia2fa

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Stores the master key in a file under the application data directory.
 *
 * This is a **software** store: [isHardwareBacked] is false, so the user interface asks
 * for consent before it is used, because the file is protected only by the operating
 * system user account. The hardware path for the master key (the TPM work that
 * `qualia-dik` already does for the device identity key) is not implemented yet, and
 * reporting anything other than false here would tell the user that a plain file is
 * hardware protected.
 *
 * Writes go through a temporary file and a move, so a crash cannot leave a half-written
 * master key behind.
 */
class FileMasterKeyStore(private val path: Path) : MasterKeyStore {
    override fun load(): ByteArray? =
        if (Files.isRegularFile(path)) Files.readAllBytes(path) else null

    override fun store(key: ByteArray) {
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.write(temporary, key)
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun delete() {
        Files.deleteIfExists(path)
    }

    override val isHardwareBacked: Boolean = false
}
