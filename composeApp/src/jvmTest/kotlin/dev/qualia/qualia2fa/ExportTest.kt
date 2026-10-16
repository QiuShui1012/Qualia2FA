package dev.qualia.qualia2fa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The export half of the facade, read back by the import half.
 *
 * # Why the two halves are exercised together
 *
 * An export exists to be read somewhere else, so "the file was written" is not a property worth
 * asserting on its own: the four formats write the same vault in four shapes, and what matters is
 * that every kind of entry survives each one with its secret. The bytes are therefore handed
 * straight to [Qualia.readImport], and what is compared is what a vault holds once the resulting
 * import has been applied to an empty one.
 *
 * # What the formats genuinely differ in
 *
 * The two JSON forms carry the groups and the order inside them, and the other two have nowhere to
 * put either. The encrypted form is the only one that hides anything, and the only one that
 * insists on a password. Both facts are asserted here rather than taken from the formats' own
 * descriptions, because the screen repeats them to the user before a file exists.
 */
class ExportTest {
    /** One entry the vault is built from, and the parameters it is written with. */
    private data class Written(
        val name: String,
        val secret: String,
        val kind: EntryKind,
        val algorithm: OtpAlgorithm,
        val digits: Int,
    )

    /**
     * One entry of every kind the library stores, with a secret of its own.
     *
     * Distinct secrets on purpose: two entries that agree in every field are the same entry
     * imported twice, and an importer that dropped one of them would make this test measure the
     * duplicate rule instead of the format.
     */
    private val written =
        listOf(
            Written("Time based", "JBSWY3DPEHPK3PXP", EntryKind.Totp(30), OtpAlgorithm.Sha1, 6),
            Written("Counter based", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", EntryKind.Hotp(7), OtpAlgorithm.Sha1, 6),
            Written("Mobile OTP", "MFRGGZDFMZTWQ2LK", EntryKind.Motp(10, pinSet = true), OtpAlgorithm.Sha1, 6),
            Written("Yandex", "LA2V6KMCGYMWWVEW64RNP3JA3I", EntryKind.Yandex(30, pinSet = true), OtpAlgorithm.Sha256, 8),
            Written("Steam", "KRSXG5CTMVRXEZLU", EntryKind.Steam, OtpAlgorithm.Sha1, 5),
        )

    /** A vault holding one entry of every kind an export has to carry. */
    private fun vaultWithEveryKind(): Vault {
        val vault =
            assertNotNull(
                Qualia.createLocalVault(MemoryStore()),
                "a local vault is the one path that needs no platform identity",
            )
        written.forEach { entry ->
            val pin = PIN.takeIf { entry.kind is EntryKind.Motp || entry.kind is EntryKind.Yandex }
            assertNotNull(
                vault.addEntry(
                    name = entry.name,
                    issuer = ISSUER,
                    account = ACCOUNT,
                    secretBase32 = entry.secret,
                    algorithm = entry.algorithm,
                    digits = entry.digits,
                    kind = entry.kind,
                    note = "",
                    unixTime = NOW,
                    pin = pin,
                ),
                "${entry.name} must be accepted",
            )
        }
        return vault
    }

    /**
     * The kind and the secret an entry computes with, as one comparable value.
     *
     * The PIN is left out: a preview never hands one back, so an imported mOTP or Yandex entry
     * reports no PIN even when the export carried it, and the PIN is not what a format is
     * measured on here.
     */
    private fun shape(kind: EntryKind, secret: String): String {
        val name =
            when (kind) {
                is EntryKind.Totp -> "totp/${kind.periodSeconds}"
                is EntryKind.Hotp -> "hotp/${kind.counter}"
                is EntryKind.Motp -> "motp/${kind.periodSeconds}"
                is EntryKind.Yandex -> "yandex/${kind.periodSeconds}"
                EntryKind.Steam -> "steam"
            }
        return "$name|$secret"
    }

    /** How a written file is read back, which is not one to one: both JSON forms are JSON. */
    private fun importFormatOf(format: ExportFormat): ImportFormat =
        when (format) {
            ExportFormat.Text -> ImportFormat.Text
            ExportFormat.Json, ExportFormat.EncryptedJson -> ImportFormat.Json
            ExportFormat.Html -> ImportFormat.Html
        }

    /** Writes [format] out, reads it back, and answers what a vault holds after applying it. */
    private fun roundTrip(vault: Vault, format: ExportFormat): Vault {
        val password = PASSWORD.takeIf { format.needsPassword }
        val bytes = assertNotNull(vault.export(format, password), "$format must write the vault out")
        val parsed =
            assertNotNull(
                Qualia.readImport(bytes, importFormatOf(format), password, NOW),
                "$format must read back",
            )
        assertTrue(parsed.problems().isEmpty(), "$format reported unusable rows: ${parsed.problems()}")

        val target = assertNotNull(Qualia.createLocalVault(MemoryStore()), "the import needs a vault")
        val applied = assertNotNull(parsed.apply(target, emptyMap()), "$format must apply")
        assertEquals(written.size, applied.added, "$format must contribute every entry")
        return target
    }

    @Test
    fun everyFormatWritesTheVaultOutAndReadsBack() {
        val vault = vaultWithEveryKind()
        assertEquals(written.size, vault.entryCount, "the vault must hold every entry this test writes")

        ExportFormat.entries.forEach { format ->
            val target = roundTrip(vault, format)
            assertEquals(written.size, target.entryCount, "$format")
            assertEquals(
                written.map { shape(it.kind, it.secret) }.sorted(),
                target.entries().map { shape(it.kind, it.secretBase32) }.sorted(),
                "$format must carry each entry's kind and its secret",
            )
        }
    }

    /**
     * The groups travel in the two JSON forms and nowhere else.
     *
     * The names are compared after the import in a second vault, which is where the difference
     * becomes visible: an export that cannot carry a group leaves its entries in the top group,
     * which is what the screen promises with "no groups: the entries return to the top group".
     */
    @Test
    fun onlyTheJsonFormatsCarryTheGroups() {
        val vault = vaultWithEveryKind()
        val first = vault.entries().first()
        val group = assertNotNull(vault.createGroup(GROUP), "the group name must be accepted")
        assertTrue(vault.assignToGroup(first.id, group), "the entry must move into the group")

        ExportFormat.entries.forEach { format ->
            val target = roundTrip(vault, format)
            val named = target.groups().filter { !it.top }
            if (format.carriesGroups) {
                assertEquals(listOf(GROUP), named.map { it.name }, "$format must carry the group")
                assertEquals(1, named.single().entryIds.size, "$format must put the entry in its group")
            } else {
                assertTrue(named.isEmpty(), "$format has nowhere to put a group, got ${named.map { it.name }}")
            }
        }
    }

    /**
     * The encrypted form is the only one that insists on a password, and the only one that hides
     * anything.
     *
     * Both the missing and the empty password are checked: the screen refuses the empty one as
     * well, so a library that accepted it would make the two layers disagree about what is
     * written.
     */
    @Test
    fun theEncryptedJsonInsistsOnAPasswordAndHidesTheSecrets() {
        val vault = vaultWithEveryKind()

        assertNull(vault.export(ExportFormat.EncryptedJson), "an encrypted export needs a password")
        assertNull(vault.export(ExportFormat.EncryptedJson, ""), "an empty password protects nothing")

        val bytes = assertNotNull(vault.export(ExportFormat.EncryptedJson, PASSWORD), "the export must be written")
        val asText = bytes.decodeToString()
        written.forEach { entry ->
            assertFalse(asText.contains(entry.secret), "${entry.name}'s secret must not survive in the file as it stands")
        }

        assertNull(Qualia.readImport(bytes, ImportFormat.Json, null, NOW), "no password must not open the file")
        assertNull(
            Qualia.readImport(bytes, ImportFormat.Json, "correct horsf", NOW),
            "a wrong password must not open the file",
        )
        val opened = assertNotNull(Qualia.readImport(bytes, ImportFormat.Json, PASSWORD, NOW), "the password must open it")
        assertEquals(written.size, opened.entries().size, "the opened export must hold every entry")
    }

    /**
     * The three properties the screen asks a format about, before anything is named or written.
     *
     * They are the library's answers, not this application's: a wrong extension writes a file the
     * save dialog's filter will not show, and a wrong "carries the groups" is what the screen would
     * tell the user.
     */
    @Test
    fun theFormatsDescribeThemselves() {
        val expected =
            mapOf(
                ExportFormat.Text to Triple(false, false, "txt"),
                ExportFormat.Json to Triple(false, true, "json"),
                ExportFormat.EncryptedJson to Triple(true, true, "json"),
                ExportFormat.Html to Triple(false, false, "html"),
            )

        ExportFormat.entries.forEach { format ->
            val (needsPassword, carriesGroups, extension) = expected.getValue(format)
            assertEquals(needsPassword, format.needsPassword, "$format and its password")
            assertEquals(carriesGroups, format.carriesGroups, "$format and the groups it carries")
            assertEquals(extension, format.extension, "$format and its file name extension")
        }
    }

    /**
     * An empty vault is written out rather than refused.
     *
     * The screen offers the same four formats whatever the vault holds, and a user who has just
     * created a vault may export it before adding anything; a refusal there would look like a
     * broken export. What the file holds is then nothing, which the reader reports as no entries.
     */
    @Test
    fun anEmptyVaultIsExported() {
        val vault = assertNotNull(Qualia.createLocalVault(MemoryStore()), "the empty vault must be created")

        ExportFormat.entries.forEach { format ->
            val password = PASSWORD.takeIf { format.needsPassword }
            val bytes = assertNotNull(vault.export(format, password), "$format must write an empty vault out")
            val parsed = assertNotNull(
                Qualia.readImport(bytes, importFormatOf(format), password, NOW),
                "$format must read back as an empty export",
            )
            assertEquals(0, parsed.entries().size, "$format")
        }
    }

    private companion object {
        /** The time every entry is written at. */
        const val NOW = 1_700_000_000L

        /** The password the encrypted form is written and opened with. */
        const val PASSWORD = "correct horse"

        /** The PIN the two schemes that compute with one are given. */
        const val PIN = "1234"

        const val ISSUER = "ACME"
        const val ACCOUNT = "alice"

        /** The group the two JSON forms are expected to carry. */
        const val GROUP = "Work"
    }
}
