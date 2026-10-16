package dev.qualia.qualia2fa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the whole chain — Kotlin, JNA, the Rust cdylib — on the host platform.
 *
 * The expected codes are the RFC 6238 Appendix B vectors, so a passing test also says the
 * library that got loaded is the real core and not a stale one.
 */
class QualiaTest {
    @Test
    fun coreVersionIsReportedByTheLoadedLibrary() {
        val version = Qualia.coreVersion()
        assertTrue(version.isNotBlank(), "core_version returned an empty string")
    }

    @Test
    fun generatedSecretsAreUsable() {
        val secret = Qualia.newSecret()
        assertTrue(secret.length >= 16, "generated secret is too short: ${secret.length}")
        assertTrue(Qualia.totp(secret, 1_700_000_000) != null, "the generated secret must produce a code")
    }

    @Test
    fun totpMatchesTheRfc6238Vectors() {
        // RFC 6238 Appendix B: the ASCII secret "12345678901234567890" in Base32.
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        assertEquals("94287082", Qualia.totp(secret, 59, 30, 8))
        assertEquals("07081804", Qualia.totp(secret, 1_111_111_109, 30, 8))
        assertEquals("14050471", Qualia.totp(secret, 1_111_111_111, 30, 8))
    }

    @Test
    fun invalidSecretsFailInsteadOfThrowing() {
        assertEquals(null, Qualia.totp("not base32!", 59))
    }

    @Test
    fun anOtpauthLinkIsParsedIntoTheFieldsAnEntryNeeds() {
        val link =
            assertNotNull(
                Qualia.parseOtpAuth(
                    "otpauth://totp/ACME%20Co:alice%40example.com" +
                        "?secret=JBSWY3DPEHPK3PXP&issuer=ACME%20Co&digits=8&period=60",
                ),
                "a well-formed otpauth link must parse",
            )

        assertEquals("ACME Co", link.issuer)
        assertEquals("alice@example.com", link.account)
        assertEquals("JBSWY3DPEHPK3PXP", link.secretBase32)
        assertEquals(8, link.digits)
        assertEquals(60L, link.periodSeconds)
    }

    @Test
    fun linksThatAreNotOtpauthAreRefused() {
        assertEquals(null, Qualia.parseOtpAuth("https://example.com/?secret=JBSWY3DPEHPK3PXP"))
        assertEquals(null, Qualia.parseOtpAuth("otpauth://totp/alice?secret=not%20base32"))
        assertEquals(null, Qualia.parseOtpAuth(""))
    }

    /**
     * The link's kind is carried like its algorithm.
     *
     * `otpauth://hotp/` names the counter the codes start from, and an importer that dropped it
     * would store a time-based entry for a server that counts: the codes would look right and
     * never be accepted. The core refuses a counter-based link without a counter and a
     * time-based one that carries a counter, so the field is exactly the kind of the link.
     */
    @Test
    fun aCounterBasedLinkIsImportedWithItsCounter() {
        val counterLink =
            assertNotNull(
                Qualia.parseOtpAuth(
                    "otpauth://hotp/Qualia:alice?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&counter=0",
                ),
                "a counter-based link must parse",
            )
        assertEquals(0L, counterLink.counter, "the counter of the link must be carried")

        val timeLink =
            assertNotNull(
                Qualia.parseOtpAuth("otpauth://totp/Qualia:alice?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"),
                "a time-based link must parse",
            )
        assertNull(timeLink.counter, "a time-based link names no counter, which is what makes it time based")
    }

    /**
     * The path the "+" menu's link option takes: parse a link, add what it describes, and
     * compute a code from it.
     *
     * The expected code is RFC 6238's vector, so this also says the fields were carried
     * across intact — a digit count or a period lost on the way would produce a different
     * code rather than an error.
     */
    @Test
    fun anEntryImportedFromALinkProducesTheRfcCode() {
        val link =
            assertNotNull(
                Qualia.parseOtpAuth(
                    "otpauth://totp/Qualia:test?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&digits=8&period=30",
                ),
            )

        val store = MemoryStore()
        val vault = assertNotNull(Qualia.createLocalVault(store), "a local vault must be creatable")
        val id =
            assertNotNull(
                vault.addEntry(
                    name = "Qualia",
                    issuer = link.issuer,
                    account = link.account,
                    secretBase32 = link.secretBase32,
                    algorithm = link.algorithm,
                    digits = link.digits,
                    kind = link.kind,
                    note = "",
                    unixTime = 59,
                ),
                "the entry the link describes must be accepted",
            )

        assertEquals("94287082", vault.code(id, 59))
    }
    /**
     * An export is read, previewed and applied, and applying it twice adds nothing twice.
     *
     * The text an export holds is what another application wrote, so the reader is fed a link per
     * line plus one line that is not a link at all: a file with a row that cannot be used is the
     * normal case, and it must come back as a reported row rather than as a refusal of the whole
     * file or as a silently missing entry.
     */
    @Test
    fun anExportIsReadPreviewedAndApplied() {
        val vault = assertNotNull(Qualia.createLocalVault(MemoryStore()))
        val bytes =
            (
                "otpauth://totp/Qualia:alice?secret=JBSWY3DPEHPK3PXP&issuer=Qualia&digits=6&period=30\n" +
                    "otpauth://hotp/Qualia:bob?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Qualia&counter=1\n" +
                    "this line is not a link at all\n"
            ).toByteArray()

        assertEquals(ImportFormat.Text, Qualia.detectImportFormat(bytes), "the content must be recognised")

        val parsed =
            assertNotNull(
                Qualia.readImport(bytes, ImportFormat.Text, password = null, now = 1_700_000_000),
                "the export must be read",
            )
        val entries = parsed.entries()
        assertEquals(2, entries.size, "the two links must be read")
        assertEquals("Qualia", entries[0].issuer)
        assertEquals(EntryKind.Totp(30), entries[0].kind, "the first link is time based")
        assertTrue(entries[1].kind is EntryKind.Hotp, "the second link counts: ${entries[1].kind}")
        assertEquals(1, parsed.problems().size, "the line that is not a link must be reported")

        assertTrue(parsed.compare(vault).all { it.existing.isEmpty() }, "the vault holds none of them")
        val applied = assertNotNull(parsed.apply(vault, emptyMap()), "the import must apply")
        assertEquals(2, applied.added, "both entries must be added")
        assertEquals(2, vault.entryCount, "the vault must hold both")

        val again =
            assertNotNull(
                Qualia.readImport(bytes, ImportFormat.Text, password = null, now = 1_700_000_000),
                "the same export must be readable again",
            )
        assertEquals(
            2,
            again.compare(vault).count { it.identical },
            "both entries are already in the vault, unchanged",
        )
        assertEquals(
            0,
            assertNotNull(again.apply(vault, emptyMap()), "re-applying must be accepted").added,
            "nothing may be added twice",
        )
        assertEquals(2, vault.entryCount, "the vault still holds exactly what it held")
    }
    /**
     * A group keeps the order it is given, and removing one keeps its entries.
     *
     * The second half is the part worth a test: "remove group" reads like "remove what is in it",
     * and the boundary decides the opposite. An entry whose group was removed returns to the top
     * group, where the user can still see it and copy its codes.
     */
    @Test
    fun groupsKeepTheirOrderAndOutliveTheirRemoval() {
        val vault = assertNotNull(Qualia.createLocalVault(MemoryStore()))
        val secret = "JBSWY3DPEHPK3PXP"
        fun add(name: String): String =
            assertNotNull(
                vault.addEntry(
                    name = name,
                    issuer = "Qualia",
                    account = "test",
                    secretBase32 = secret,
                    algorithm = OtpAlgorithm.Sha1,
                    digits = 6,
                    kind = EntryKind.Totp(30),
                    note = "",
                    unixTime = 1_700_000_000,
                ),
                "$name must be accepted",
            )

        val first = add("First")
        val second = add("Second")

        val groupId = assertNotNull(vault.createGroup("Work"), "a group must be created")
        val groups = vault.groups()
        assertEquals(2, groups.size, "the top group and the new one")
        assertTrue(groups[0].top, "the top group comes first")
        assertEquals("Work", groups[1].name)
        assertTrue(
            groups[0].entryIds.containsAll(listOf(first, second)),
            "a new entry starts in the top group: ${groups[0].entryIds}",
        )

        assertTrue(vault.assignToGroup(first, groupId), "the first entry must move")
        assertTrue(vault.assignToGroup(second, groupId), "the second entry must move")
        assertEquals(groupId, vault.groupOf(first), "the entry must report its group")

        assertEquals(
            listOf(first, second),
            vault.groups().single { it.id == groupId }.entryIds,
            "a group keeps the order entries were put in",
        )
        assertTrue(vault.setOrdering(groupId, listOf(second, first)), "the order must be writable")
        assertEquals(
            listOf(second, first),
            vault.groups().single { it.id == groupId }.entryIds,
            "the order that was written is the order that is read back",
        )

        assertTrue(vault.removeGroup(groupId), "the group must be removable")
        assertEquals(1, vault.groups().size, "only the top group is left")
        assertEquals(2, vault.entryCount, "removing a group must not remove its entries")
        assertEquals(
            vault.groups().first().id,
            vault.groupOf(first),
            "the entry returns to the top group",
        )
    }
}
