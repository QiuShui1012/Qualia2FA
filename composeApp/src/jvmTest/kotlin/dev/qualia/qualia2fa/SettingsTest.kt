package dev.qualia.qualia2fa

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The stored language choice.
 *
 * A preference that does not survive a restart is worse than no preference at all: the
 * user picks a language, and the next start silently contradicts them.
 */
class SettingsTest {
    /** A settings file in a fresh temporary directory. */
    private fun temporarySettings(): Path =
        Files.createTempDirectory("qualia2fa-settings").resolve("settings.properties")

    @Test
    fun theChoiceSurvivesANewStore() {
        val file = temporarySettings()

        assertEquals(
            AppLanguage.System,
            FileSettingsStore(file).language(),
            "nothing chosen yet means follow the system language",
        )

        FileSettingsStore(file).setLanguage(AppLanguage.Chinese)
        assertEquals(
            AppLanguage.Chinese,
            FileSettingsStore(file).language(),
            "the choice reads back from a store that did not write it, which is what a restart does",
        )

        FileSettingsStore(file).setLanguage(AppLanguage.English)
        assertEquals(AppLanguage.English, FileSettingsStore(file).language())
    }

    @Test
    fun everyLanguageRoundTrips() {
        val file = temporarySettings()
        val store = FileSettingsStore(file)
        AppLanguage.entries.forEach { language ->
            store.setLanguage(language)
            assertEquals(language, FileSettingsStore(file).language())
        }
    }

    @Test
    fun anUnknownOrBrokenFileMeansTheSystemLanguage() {
        val file = temporarySettings()
        Files.createDirectories(file.parent)
        Files.writeString(file, "language=Klingon\n")
        assertEquals(AppLanguage.System, FileSettingsStore(file).language())

        Files.writeString(file, "this is not a properties file at all")
        assertEquals(AppLanguage.System, FileSettingsStore(file).language())

        Files.delete(file)
        assertEquals(AppLanguage.System, FileSettingsStore(file).language())
    }

    @Test
    fun writingLeavesNoTemporaryFileBehind() {
        val file = temporarySettings()
        FileSettingsStore(file).setLanguage(AppLanguage.Chinese)

        val leftovers =
            Files.list(file.parent).use { entries ->
                entries.filter { it.fileName.toString().endsWith(".tmp") }.toList()
            }
        assertEquals(emptyList(), leftovers, "the write must finish with a move, not leave the temporary behind")
    }
}
