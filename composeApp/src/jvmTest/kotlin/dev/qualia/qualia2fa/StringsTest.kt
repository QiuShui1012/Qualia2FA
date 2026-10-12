package dev.qualia.qualia2fa

import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.entry_count
import dev.qualia.qualia2fa.resources.status_locked
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The translated catalogues and the way a locale selects one.
 *
 * # Why the catalogues are read as files
 *
 * Compose resources fall back to the default catalogue when a translation is missing, so a
 * key added to `values/strings.xml` and forgotten in `values-zh/strings.xml` produces a
 * perfectly working English string in the Chinese interface. Nothing at runtime reports
 * it, and no compiler sees it. Comparing the two files is the only check that can.
 *
 * The files are read through their path in the repository rather than through the
 * generated accessors, because the accessors only describe the key set of the default
 * catalogue: comparing those with themselves would assert nothing.
 */
class StringsTest {
    /** The resource directories, relative to the module directory. */
    private val catalogues = File("src/commonMain/composeResources")

    /** The keys of one catalogue, as `element:name`. */
    private fun keysOf(catalogue: String): Set<String> {
        val file = File(catalogues, "$catalogue/strings.xml")
        assertTrue(
            file.isFile,
            "the catalogue is missing: ${file.absolutePath} (is the test running in the module directory?)",
        )

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("*")
        val keys = mutableSetOf<String>()
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            if (element.tagName == "string" || element.tagName == "plurals") {
                keys += "${element.tagName}:${element.getAttribute("name")}"
            }
        }
        return keys
    }

    @Test
    fun everyDefaultKeyIsTranslated() {
        val english = keysOf("values")
        val chinese = keysOf("values-zh")

        assertTrue(english.size > 50, "the default catalogue looks truncated: ${english.size} keys")

        assertEquals(
            emptySet(),
            english - chinese,
            "these keys fall back to English in the Chinese interface, which no runtime error would report",
        )
        assertEquals(
            emptySet(),
            chinese - english,
            "these keys are translated but absent from the default catalogue, so no other language can fall back to them",
        )
    }

    /**
     * A locale selects its catalogue, and a locale with no catalogue gets the default one.
     *
     * `Locale.getDefault()` is what the resource library reads on this platform, both when
     * a string is composed and when it is asked for directly, so the test can drive it the
     * same way the application does.
     */
    @Test
    fun theLocaleSelectsTheCatalogue() =
        runBlocking {
            withLocale("en-US") {
                assertEquals("Locked", getString(Res.string.status_locked))
            }
            withLocale("zh-CN") {
                assertEquals("已锁定", getString(Res.string.status_locked))
            }
            withLocale("de-DE") {
                assertEquals(
                    "Locked",
                    getString(Res.string.status_locked),
                    "a language without a catalogue must fall back to the default one",
                )
            }
        }

    /** The plural form follows the language, which is why this one is not a plain string. */
    @Test
    fun pluralsFollowTheLanguage() =
        runBlocking {
            withLocale("en-US") {
                assertEquals("1 entry", getPluralString(Res.plurals.entry_count, 1, 1))
                assertEquals("2 entries", getPluralString(Res.plurals.entry_count, 2, 2))
            }
            withLocale("zh-CN") {
                assertEquals("1 条", getPluralString(Res.plurals.entry_count, 1, 1))
                assertEquals("2 条", getPluralString(Res.plurals.entry_count, 2, 2))
            }
        }

    /** Runs [body] with the default locale set to [tag], restoring the previous one after. */
    private suspend fun withLocale(tag: String, body: suspend () -> Unit) {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag(tag))
            body()
        } finally {
            Locale.setDefault(previous)
        }
    }
}
