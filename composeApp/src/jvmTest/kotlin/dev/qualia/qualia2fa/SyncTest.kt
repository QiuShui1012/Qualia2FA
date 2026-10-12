package dev.qualia.qualia2fa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Synchronization through the facade: two vaults of one account, in one process.
 *
 * This is where the three properties the feature stands on are pinned: an ordinary sync
 * moves entries, a disagreement between a deletion and a live entry is reported and can be
 * closed by a decision, and a code that is not this account's does not merge.
 *
 * The devices here are two vaults opened with the same master key. That is the state pairing
 * produces, and it is what a real sync has in common with this one; the identity keys that
 * pairing also creates are not needed to move entries, so no platform key store is involved
 * and the tests run everywhere.
 */
class SyncTest {
    private val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    private val now = 1_700_000_000L

    private fun deviceId(digit: Char): String = digit.toString().repeat(64)

    private val deviceA = deviceId('a')
    private val deviceB = deviceId('b')

    /** Two devices of one account: two vaults, one master key. */
    private fun twoVaultsOfOneAccount(): Pair<Vault, Vault> {
        val storeA = MemoryStore()
        val vaultA = assertNotNull(Qualia.createLocalVault(storeA), "creating a vault must succeed")
        val emptyVault = assertNotNull(vaultA.serialize(), "an empty vault must serialize")

        val key = assertNotNull(storeA.load(), "creating a vault must write the master key")
        val storeB = MemoryStore(key.copyOf())
        val vaultB = assertNotNull(
            Qualia.openVault(emptyVault, storeB),
            "the same master key must open a second, independent vault",
        )
        return vaultA to vaultB
    }

    private fun addEntry(vault: Vault, name: String, updatedAt: Long = 1_700_000_000L): String =
        assertNotNull(
            vault.addEntry(name, "ACME", "alice", secret, 6, 30, "", updatedAt),
            "adding an entry must succeed",
        )

    /** One merge that must have been accepted. */
    private fun merge(session: SyncSession, code: String): SyncOutcome {
        val result = session.mergeText(code)
        assertTrue(result is SyncMerge.Merged, "the code must be accepted, got $result")
        return result.outcome
    }

    /**
     * **The property the feature exists for**: one device's entries reach the other through a
     * code, and the way back changes nothing.
     */
    @Test
    fun twoDevicesCompleteASyncThroughTheFacade() {
        val (vaultA, vaultB) = twoVaultsOfOneAccount()
        val id = addEntry(vaultA, "GitHub")
        val sessionA = assertNotNull(Qualia.syncSession(vaultA, deviceA))
        val sessionB = assertNotNull(Qualia.syncSession(vaultB, deviceB))

        val code = assertNotNull(sessionA.myCodeText(), "the code must be produced")
        assertTrue(code.startsWith("Q2FAS1:"), "the code must name the protocol it speaks: $code")
        assertTrue(code.contains(deviceA), "the code must carry the sender the payload is checked against")

        val outcome = merge(sessionB, code)
        assertEquals(1, outcome.added, "the entry must arrive: $outcome")
        assertFalse(outcome.needsAdjudication, "an ordinary sync asks the user nothing")
        assertFalse(outcome.madeNoChange, "something did change")
        assertEquals(1, vaultB.entryCount)
        assertEquals("GitHub", vaultB.entries().first().name)
        assertEquals(id, vaultB.entries().first().id, "the identifier is what the two devices agree on")

        assertEquals(
            SyncRefusal.AlreadyApplied,
            (sessionB.mergeText(code) as SyncMerge.Refused).reason,
            "the same code applied twice is refused",
        )

        val back = merge(sessionA, assertNotNull(sessionB.myCodeText(), "B must produce a code too"))
        assertTrue(back.madeNoChange, "A already holds everything B has: $back")
        assertFalse(back.needsAdjudication)
    }

    /** A deletion that meets an entry edited here is reported, and deciding it closes it. */
    @Test
    fun aDeletionAgainstAnEditedEntryIsDecided() {
        val (vaultA, vaultB) = twoVaultsOfOneAccount()
        val id = addEntry(vaultA, "GitHub")
        val sessionA = assertNotNull(Qualia.syncSession(vaultA, deviceA))
        val sessionB = assertNotNull(Qualia.syncSession(vaultB, deviceB))
        merge(sessionB, assertNotNull(sessionA.myCodeText()))

        val edited = 1_700_000_120L
        assertTrue(
            vaultB.updateEntry(id, "GitHub (work)", "ACME", "alice", secret, 6, 30, "", edited),
            "the edit must be accepted",
        )
        assertTrue(vaultA.removeEntry(id, 1_700_000_060L), "the deletion must be accepted")

        val outcome = merge(sessionB, assertNotNull(sessionA.myCodeText()))
        assertTrue(outcome.needsAdjudication, "the disagreement must be reported: $outcome")
        assertEquals(0, outcome.deletionsApplied, "nothing may be deleted before the user decides")
        assertEquals(1, vaultB.entryCount, "the entry is still there")

        val conflict = outcome.conflicts.single()
        assertEquals(SyncConflictKind.PeerDeleted, conflict.kind)
        assertEquals(id, conflict.entryId)
        assertEquals("GitHub (work)", assertNotNull(conflict.local, "the local version is shown").name)
        assertNull(conflict.incoming, "the other device sent a deletion, which has nothing to show")
        assertEquals(1, sessionB.conflicts().size, "the conflict stays available until it is decided")

        assertTrue(sessionB.decide(id, takeIncoming = true), "the decision must apply")
        assertEquals(0, vaultB.entryCount, "the deletion is applied")
        assertTrue(sessionB.conflicts().isEmpty(), "a decided conflict stops being pending")

        val settled = merge(sessionB, assertNotNull(sessionA.myCodeText()))
        assertFalse(settled.needsAdjudication, "the decision must close the disagreement: $settled")
    }

    /** The other direction: an entry deleted here comes back when the decision says so. */
    @Test
    fun anEntryDeletedHereIsRestored() {
        val (vaultA, vaultB) = twoVaultsOfOneAccount()
        val id = addEntry(vaultA, "GitHub")
        val sessionA = assertNotNull(Qualia.syncSession(vaultA, deviceA))
        val sessionB = assertNotNull(Qualia.syncSession(vaultB, deviceB))
        merge(sessionB, assertNotNull(sessionA.myCodeText()))

        assertTrue(vaultB.removeEntry(id, 1_700_000_060L), "the deletion must be accepted")

        val outcome = merge(sessionB, assertNotNull(sessionA.myCodeText()))
        assertTrue(outcome.needsAdjudication, "$outcome")
        val conflict = outcome.conflicts.single()
        assertEquals(SyncConflictKind.LocalDeleted, conflict.kind)
        assertNull(conflict.local, "this device holds only a deletion")
        assertEquals("GitHub", assertNotNull(conflict.incoming, "the other device's entry is shown").name)

        assertTrue(sessionB.decide(id, takeIncoming = true), "the decision must apply")
        assertEquals(1, vaultB.entryCount, "the entry is back")
        assertEquals("GitHub", vaultB.entries().first().name)

        val back = merge(sessionA, assertNotNull(sessionB.myCodeText()))
        assertFalse(back.needsAdjudication, "the disagreement is closed on both sides: $back")
        assertTrue(back.madeNoChange, "both devices hold the same entry again")
    }

    /** Keeping this device's version is the other half of a decision. */
    @Test
    fun keepingTheLocalVersionLeavesTheEntryAlone() {
        val (vaultA, vaultB) = twoVaultsOfOneAccount()
        val id = addEntry(vaultA, "GitHub")
        val sessionA = assertNotNull(Qualia.syncSession(vaultA, deviceA))
        val sessionB = assertNotNull(Qualia.syncSession(vaultB, deviceB))
        merge(sessionB, assertNotNull(sessionA.myCodeText()))

        assertTrue(vaultB.updateEntry(id, "GitHub (work)", "ACME", "alice", secret, 6, 30, "", 1_700_000_120L))
        assertTrue(vaultA.removeEntry(id, 1_700_000_060L))
        merge(sessionB, assertNotNull(sessionA.myCodeText()))

        assertTrue(sessionB.decide(id, takeIncoming = false), "the decision must apply")
        assertEquals(1, vaultB.entryCount, "the entry stays")
        assertEquals("GitHub (work)", vaultB.entries().first().name)
        assertTrue(sessionB.conflicts().isEmpty())
    }

    /** A code from another account is refused rather than merged. */
    @Test
    fun aCodeFromAnotherAccountIsRefused() {
        val (vaultA, _) = twoVaultsOfOneAccount()
        val (strangerVault, _) = twoVaultsOfOneAccount()
        val code = assertNotNull(Qualia.syncSession(strangerVault, deviceA)?.myCodeText())

        val (_, vaultB) = twoVaultsOfOneAccount()
        val sessionB = assertNotNull(Qualia.syncSession(vaultB, deviceB))
        val result = sessionB.mergeText(code)

        assertTrue(result is SyncMerge.Refused, "a code of another account must be refused")
        assertEquals(SyncRefusal.AuthenticationFailed, result.reason)
        assertEquals(0, vaultB.entryCount, "a refused code must not touch the vault")
        assertTrue(vaultA.isEmpty, "precondition: the first account is not involved")
    }

    /** Text that is not a sync code is refused before any payload is touched. */
    @Test
    fun textThatIsNotASyncCodeIsRefused() {
        val (_, vault) = twoVaultsOfOneAccount()
        val session = assertNotNull(Qualia.syncSession(vault, deviceB))

        for (text in listOf("", "hello", "Q2FAS1:not-a-device-id:AAAA", "Q2FAS1:${deviceA}:not base64!")) {
            val result = session.mergeText(text)
            assertTrue(result is SyncMerge.Refused, "\"$text\" must be refused")
            assertEquals(SyncRefusal.NotACode, result.reason, "for \"$text\"")
        }
    }
}
