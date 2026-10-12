package dev.qualia.qualia2fa

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where the application keeps its files.
 *
 * [dataDirectory] is `%APPDATA%\Qualia2FA` on Windows and `~/.qualia2fa` elsewhere. The
 * vault and the master key live side by side; the vault is useless without the key, and
 * the key is useless without the vault, so they are kept together.
 */
object AppPaths {
    val dataDirectory: Path =
        System.getenv("APPDATA")
            ?.takeIf { it.isNotBlank() }
            ?.let { Paths.get(it, "Qualia2FA") }
            ?: Paths.get(System.getProperty("user.home"), ".qualia2fa")

    val vaultFile: Path = dataDirectory.resolve("vault.q2fa")

    val masterKeyFile: Path = dataDirectory.resolve("master.key")

    /** The user's preferences; nothing in it is secret and it is not needed to unlock. */
    val settingsFile: Path = dataDirectory.resolve("settings.properties")

    fun ensureDataDirectory() {
        Files.createDirectories(dataDirectory)
    }
}
