package dev.qualia.qualia2fa

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The wrapped master key on disk: the file holds bytes a platform key wrapped, never the
 * master key itself.
 *
 * # What is on this machine
 *
 * Two halves that are useless apart. The wrapped bytes are in [path], and the key that can
 * unwrap them is in the platform key store, where nothing — this application included — can
 * read it out. Copying the file therefore copies nothing that opens a vault, and the platform
 * key is what a thief would still have to obtain.
 *
 * # Where the file is
 *
 * Beside the vault file, in the directory the application was given: the two are one device's
 * state, and a backup that carries one without the other is not a backup. The file is
 * rewritten every time a master key is stored, because the platform produces new wrapped
 * bytes for the same key on every wrap: the pair is what matters, not the bytes.
 *
 * # Writes are atomic
 *
 * Through a temporary file and a move, so an interrupted write cannot leave half of the
 * wrapped bytes behind and turn "the key is there" into "the key is there but unreadable".
 * The platform unwraps what it produced or refuses; a truncated file could only be the
 * latter, with no way for the user to tell why.
 */
internal class FileWrappedMasterKeyStore(
    private val path: Path,
    label: String,
) : WrappedMasterKeyStore(
    requireNotNull(openWrappedMasterKeyStore(label)) {
        "this machine has no platform key store that can hold a key-encryption key"
    },
    label,
) {
    /**
     * The wrapped bytes as they are on disk.
     *
     * Read on every access rather than cached: the file belongs to this device's storage,
     * which a reset and an import both write through, and a copy taken once would answer
     * about a state that no longer exists.
     */
    override val wrappedBytes: ByteArray?
        get() =
            try {
                if (Files.isRegularFile(path)) Files.readAllBytes(path) else null
            } catch (_: Exception) {
                null
            }

    override fun persist(bytes: ByteArray) {
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.write(temporary, bytes)
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun deletePersisted() {
        runCatching { Files.deleteIfExists(path) }
        runCatching { Files.deleteIfExists(path.resolveSibling(path.fileName.toString() + ".tmp")) }
    }
}
