package dev.qualia.qualia2fa

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * Keeps the preferences in a properties file under the application data directory.
 *
 * Nothing here is secret: the file holds the language choice and whatever is added later,
 * and it is read with the same trust as a configuration file a user may edit by hand. A
 * missing file, an unreadable one and an unknown language name all mean "no choice yet",
 * because none of them is worth refusing to start the application over — the language is
 * also switchable from the interface.
 *
 * Writes go through a temporary file and a move, so a crash cannot leave a half-written
 * file behind.
 */
class FileSettingsStore(private val path: Path) : SettingsStore {
    override fun language(): AppLanguage {
        val properties = read()
        return AppLanguage.fromName(properties?.getProperty(LANGUAGE_KEY))
    }

    override fun setLanguage(language: AppLanguage) {
        val properties = read() ?: Properties()
        properties.setProperty(LANGUAGE_KEY, language.name)
        write(properties)
    }

    /** The stored properties, or `null` when there is nothing usable to read. */
    private fun read(): Properties? {
        if (!Files.isRegularFile(path)) return null
        return try {
            Files.newInputStream(path).use { stream ->
                Properties().apply { load(stream) }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Writes the properties, leaving the previous file untouched when this fails. */
    private fun write(properties: Properties) {
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.newOutputStream(temporary).use { stream ->
            properties.store(stream, "Qualia2FA settings")
        }
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        /** The key holding the chosen language. */
        const val LANGUAGE_KEY = "language"
    }
}
