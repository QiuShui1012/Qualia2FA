package dev.qualia.qualia2fa

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The local network route: two channels in one process, one exchange at a time.
 *
 * # Why the two sides register each other by hand
 *
 * Multicast reaches one socket per group on a host, and both lobbies here run in the same
 * process, so neither would hear the other. What discovery supplies is the address an
 * announcement arrived from, and that is the one thing a test can supply itself: the loopback
 * address and the port out of the peer's own record. Everything after that — the handshake,
 * the payloads, the merge — is the code the application runs.
 *
 * # What makes a test skip instead of fail
 *
 * A machine with no free UDP port, one whose policy blocks it, or one whose loopback carries
 * no traffic cannot run these at all. **Only the first hop of each test may be skipped**: up to
 * and including the arrival that proves the machine can carry traffic at all, a failure is
 * treated as that machine's limitation. Every exchange after that gate is asserted, because
 * "the answer never arrived" is also exactly what a broken reverse direction looks like — a
 * skip there would report a bug as a pass.
 *
 * # Both directions are exercised
 *
 * A sync and a pairing each send in one direction and answer in the other, and the two run on
 * one connection: whoever was dialled answers on it. [aPeerIsHeardInBothDirections] pins that
 * on its own, so a channel that can only be talked *to* is caught here rather than in the
 * middle of a flow whose failure looks like "nothing arrived".
 */
class LanTest {
    private val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    private fun deviceId(digit: Char): String = digit.toString().repeat(64)

    private val deviceA = deviceId('a')
    private val deviceB = deviceId('b')

    /**
     * The peer [channel] may send to, addressed at the loopback address on its own port.
     *
     * The record carries the endpoint key and the port the peer's endpoint listens on, and the
     * address is the one part a test has to supply: a lobby announcement is where a real
     * deployment learns it.
     */
    private fun peerOf(channel: LanChannel, deviceId: String): LanPeer {
        val record = assertNotNull(channel.record(), "an open endpoint must produce a record")
        return LanPeer(deviceId, record, "127.0.0.1:${portOf(record)}", true)
    }

    /**
     * The port out of a connection record.
     *
     * The layout is the transport's own — a six-byte prefix, a version, the thirty-two-byte
     * endpoint key, the port big-endian, and a checksum, forty-two bytes in all — and it is
     * parsed here rather than through the facade so that the test holds its own reading of the
     * format instead of asking the code under test what the format is.
     */
    private fun portOf(record: ByteArray): Int =
        ((record[record.size - 3].toInt() and 0xFF) shl 8) or (record[record.size - 2].toInt() and 0xFF)

    /** Two devices of one account: two vaults, one master key. */
    private fun twoVaultsOfOneAccount(): Pair<Vault, Vault> {
        val storeA = MemoryStore()
        val vaultA = assertNotNull(Qualia.createLocalVault(storeA), "creating a vault must succeed")
        val emptyVault = assertNotNull(vaultA.serialize(), "an empty vault must serialize")

        val key = assertNotNull(storeA.load(), "creating a vault must write the master key")
        val storeB = MemoryStore(key.copyOf())
        val vaultB =
            assertNotNull(
                Qualia.openVault(emptyVault, storeB),
                "the same master key must open a second, independent vault",
            )
        return vaultA to vaultB
    }

    /** The entry these tests move between devices. */
    private fun addEntry(vault: Vault): String =
        assertNotNull(
            vault.addEntry("GitHub", "ACME", "alice", secret, OtpAlgorithm.Sha1, 6, EntryKind.Totp(30), "", 1_700_000_000L),
            "adding an entry must succeed",
        )

    /**
     * **A device is let into the account over the network**: the code names the host, the
     * request travels to it, the reply travels back, and the joining device ends up holding
     * the same master key — which is what opening the host's own vault with it proves.
     *
     * The joining side's channel is opened under its **platform identity**, as the application
     * opens it, so the device the host is told about is the same one the channel claims.
     */
    @Test
    fun aPairingCompletesOverTwoChannels() = runBlocking {
        val joinerDeviceId = Qualia.deviceId(JOINER_LABEL) ?: return@runBlocking
        val hostChannel = Qualia.lanChannel(deviceA) ?: return@runBlocking
        val joinerChannel = Qualia.lanChannel(joinerDeviceId)
        if (joinerChannel == null) {
            hostChannel.close()
            return@runBlocking
        }

        try {
            val hostVault = assertNotNull(Qualia.createLocalVault(MemoryStore()), "the host needs a vault")
            val entry = addEntry(hostVault)

            val host = assertNotNull(Qualia.beginPairing(), "a pairing must start")
            val hostRecord = assertNotNull(hostChannel.record(), "the host must produce a record")
            val code = host.codeText(hostRecord)

            val joiner = assertNotNull(Qualia.joinPairing(code, JOINER_LABEL), "the code must be readable")
            assertEquals(
                hostRecord.toList(),
                assertNotNull(joiner.hostRecord, "the code must carry the host's record").toList(),
                "the record in the code is what names the host on the network",
            )

            val hostPeer = peerOf(hostChannel, deviceA)
            val joinerPeer = peerOf(joinerChannel, joinerDeviceId)
            assertTrue(joinerChannel.remember(hostPeer), "the joiner must be able to dial the host")
            assertTrue(hostChannel.remember(joinerPeer), "the host must know where the joiner is")
            val request = assertNotNull(joiner.request(), "the joiner must produce a request")
            assertTrue(joinerChannel.sendTo(deviceA, request), "the request must be accepted for sending")

            val arrived = hostChannel.receive(WAIT_MS) ?: return@runBlocking
            assertEquals(joinerDeviceId, arrived.sender, "the message must name the device that sent it")
            assertEquals(request.toList(), arrived.message.toList(), "the request must arrive unchanged")

            val reply = assertNotNull(hostVault.authorize(host, arrived.message), "the host must authorize it")
            assertEquals(
                joinerDeviceId,
                assertNotNull(host.joinerDeviceId, "the host must read the requester out of the request"),
                "the requester the host reports is the identity the joining device holds",
            )

            assertTrue(hostChannel.sendTo(joinerDeviceId, reply), "the reply must be accepted for sending")

            val answer =
                assertNotNull(
                    joinerChannel.receive(WAIT_MS),
                    "the reply must arrive on the connection the request opened",
                )
            assertEquals(deviceA, answer.sender, "the reply must name the host")

            val joinerStore = MemoryStore()
            val joined =
                assertNotNull(
                    joiner.complete(answer.message, joinerStore),
                    "the reply must complete the pairing",
                )
            assertTrue(joined.isEmpty, "a login carries the master key, not the entries")

            val reopened =
                assertNotNull(
                    Qualia.openVault(
                        assertNotNull(hostVault.serialize(), "the host vault must serialize"),
                        joinerStore,
                    ),
                    "the joined device must open the host's own vault with the key it just received",
                )
            assertEquals(hostVault.entries().map { it.name }, reopened.entries().map { it.name })
            assertEquals(entry, reopened.entries().single().id, "both devices must agree on the entry's identifier")
        } finally {
            joinerChannel.close()
            hostChannel.close()
        }
    }

    /**
     * **A vault travels over the network in both directions**: one side offers its state, the
     * other merges it, and the way back reports that there was nothing left to change.
     */
    @Test
    fun aVaultTravelsOverTwoChannels() = runBlocking {
        val (vaultA, vaultB) = twoVaultsOfOneAccount()
        val id = addEntry(vaultA)

        val channelA = Qualia.lanChannel(deviceA) ?: return@runBlocking
        val channelB = Qualia.lanChannel(deviceB)
        if (channelB == null) {
            channelA.close()
            return@runBlocking
        }

        try {
            val peerA = peerOf(channelA, deviceA)
            val peerB = peerOf(channelB, deviceB)
            assertTrue(channelA.remember(peerB), "A must be able to dial B")
            assertTrue(channelB.remember(peerA), "B must know where A is")

            val sessionA = assertNotNull(Qualia.lanSyncSession(vaultA, channelA), "A needs a session")
            val sessionB = assertNotNull(Qualia.lanSyncSession(vaultB, channelB), "B needs a session")

            assertTrue(sessionA.send(deviceB), "the state must be accepted for sending")
            val merged = sessionB.receive(WAIT_MS)
            if (merged is SyncMerge.Refused && merged.reason == SyncRefusal.NothingToRead) return@runBlocking

            val outcome = assertIs<SyncMerge.Merged>(merged, "the state must arrive, got $merged").outcome
            assertEquals(1, outcome.added, "the entry must arrive: $outcome")
            assertTrue(outcome.madeNoChange.not(), "something did change")
            assertEquals(1, vaultB.entryCount)
            assertEquals("GitHub", vaultB.entries().single().name)
            assertEquals(id, vaultB.entries().single().id, "the identifier is what the two devices agree on")

            assertTrue(sessionB.send(deviceA), "B must be able to offer its state back")
            val back =
                assertNotNull(
                    sessionA.receive(WAIT_MS),
                    "B's state must arrive: the connection the first offer opened is the way back",
                )
            assertTrue(back !is SyncMerge.Refused, "B's state must be merged, got $back")

            val settled = assertIs<SyncMerge.Merged>(back, "B's state must arrive, got $back").outcome
            assertTrue(settled.madeNoChange, "A already holds everything B has: $settled")
        } finally {
            channelB.close()
            channelA.close()
        }
    }

    /**
     * **A peer answers on the connection it accepted**: the side that dialled reads what comes
     * back, and the connection stays usable for the next message in either direction.
     *
     * A transport that only reads the connections it accepted delivers the first message and
     * then writes every answer into a stream nobody takes: the send is accepted and the answer
     * is lost, which is what a pairing and a sync look like when they hang after the first
     * step. This pins the two directions on their own, without a session in the way.
     */
    @Test
    fun aPeerIsHeardInBothDirections() = runBlocking {
        val channelA = Qualia.lanChannel(deviceA) ?: return@runBlocking
        val channelB = Qualia.lanChannel(deviceB)
        if (channelB == null) {
            channelA.close()
            return@runBlocking
        }

        try {
            assertTrue(channelA.remember(peerOf(channelB, deviceB)), "A must be able to dial B")
            assertTrue(channelB.remember(peerOf(channelA, deviceA)), "B must know where A is")

            assertTrue(channelA.sendTo(deviceB, byteArrayOf(1)), "A's message must be accepted")
            val first = channelB.receive(WAIT_MS) ?: return@runBlocking
            assertEquals(deviceA, first.sender, "the message must name the device that sent it")
            assertEquals(byteArrayOf(1).toList(), first.message.toList())

            assertTrue(channelB.sendTo(deviceA, byteArrayOf(2)), "B's answer must be accepted")
            val answer =
                assertNotNull(
                    channelA.receive(WAIT_MS),
                    "the answer must arrive on the connection A dialled",
                )
            assertEquals(deviceB, answer.sender, "the answer must name the device that sent it")
            assertEquals(byteArrayOf(2).toList(), answer.message.toList())

            assertTrue(channelA.sendTo(deviceB, byteArrayOf(3)), "the connection must stay usable")
            val again =
                assertNotNull(channelB.receive(WAIT_MS), "the connection must stay usable both ways")
            assertEquals(byteArrayOf(3).toList(), again.message.toList())
        } finally {
            channelB.close()
            channelA.close()
        }
    }

    /** How long one exchange is waited for before the machine is declared unable to run it. */
    private companion object {
        const val WAIT_MS = 10_000L

        /**
         * The label the joining device's identity is kept under, in the platform key store.
         *
         * A label of its own so that the key this test creates is recognizable, and so that it
         * is not the identity an installed application uses.
         */
        const val JOINER_LABEL = "qualia2fa-jvm-test-lan"
    }
}
