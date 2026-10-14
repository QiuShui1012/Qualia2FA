package dev.qualia.qualia2fa

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where the desktop build keeps its files: `%APPDATA%\Qualia2FA` on Windows, `~/.qualia2fa`
 * elsewhere.
 *
 * The vault and the master key live side by side — the vault is useless without the key, and
 * the key is useless without the vault — so one directory is resolved here and handed to the
 * storage. Android resolves its own directory instead: an application's private directory is
 * a thing only the platform can name.
 */
object AppPaths {
    /** The directory the vault, the master key and the preferences all live in. */
    val dataDirectory: Path =
        System.getenv("APPDATA")
            ?.takeIf { it.isNotBlank() }
            ?.let { Paths.get(it, "Qualia2FA") }
            ?: Paths.get(System.getProperty("user.home"), ".qualia2fa")

    /** The user's preferences; nothing in it is secret, and it is not needed to unlock. */
    val settingsFile: Path = dataDirectory.resolve("settings.properties")
}
