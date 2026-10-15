package dev.qualia.qualia2fa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The codes that need more than one symbol, on the pairing payload in particular.
 *
 * The pairing code, the request and the answer are drawn by the same component the sync code
 * uses, and that component takes the multi-frame path for every payload: one symbol when it
 * fits, a sequence of them when it does not. What is pinned here is that the pairing payload
 * survives that path in both directions — drawn, read back out of the pixels, and reassembled —
 * and that a payload too large for one symbol is split rather than dropped.
 *
 * Nothing here needs a platform key store or any other hardware: starting a pairing draws an
 * ephemeral key and a nonce and touches nothing, and the rest is the transport's own encoder and
 * decoder.
 */
class FrameTest {
    /**
     * The text a host draws for a pairing.
     *
     * The payload is the ephemeral key and nonce the core generates, in Base32: it needs no
     * platform key store, which is what keeps this test runnable on a machine without one.
     */
    private fun hostPayload(): String =
        assertNotNull(Qualia.beginPairing(), "starting a pairing must not need a key store").payload

    /**
     * A host record of the size the transport produces.
     *
     * Forty-two bytes: a six-byte prefix, a version, the thirty-two-byte endpoint key, the port
     * big-endian and a checksum — the layout `LanTest` reads a real record with. The bytes are
     * not a valid record and do not need to be: what the code path here sees is the text the
     * record is encoded into, and its length is what decides how many symbols that text takes.
     */
    private fun record(): ByteArray = ByteArray(42) { (it + 1).toByte() }

    /** The text of one drawn code, read back out of its pixels. */
    private fun textOf(image: QrImage): String =
        assertNotNull(
            Qualia.qrTextFromImage(image.pixels, image.width, image.height),
            "a code this facade drew must be readable",
        )

    /**
     * Reads every drawing back out of its pixels and reassembles the payload.
     *
     * This is what a camera in front of the sequence does, and the reassembly is the library's
     * own: the codes are collected in the order they were shown, and the frames are the ones the
     * display path produced.
     */
    private fun reassembled(frames: QrFrames): String {
        val collector = assertNotNull(Qualia.frameCollector(), "a collector must be creatable")
        var payload: ByteArray? = null

        for (index in 0 until frames.count) {
            val text = textOf(frames.image(index))
            when (val step = collector.push(text)) {
                is FrameStep.Completed -> payload = step.payload
                is FrameStep.Taken -> Unit
                FrameStep.Rejected -> fail("code ${index + 1} of ${frames.count} was refused: $text")
            }
        }

        return assertNotNull(payload, "the codes must complete the payload").decodeToString()
    }

    /**
     * **A pairing code survives the path the screen draws it with.**
     *
     * The code is drawn, read back out of the pixels and reassembled, and what comes back has to
     * be the text that went in — a payload lost on the way would leave a pairing code the other
     * device cannot use, which is a failure invisible on the screen showing it.
     *
     * The same text is also put through the single-code reader, because a payload that fits is
     * drawn as one symbol and has to keep working that way.
     */
    @Test
    fun aPairingCodeSurvivesTheCodePathTheScreenDraws() {
        // Asked for once: every call to [hostPayload] starts a new pairing, and a new pairing is
        // a new ephemeral key.
        val payload = hostPayload()
        val code = pairingCodeText(record(), payload)

        val frames = assertNotNull(Qualia.qrFrames(code), "the pairing code must be drawable")
        assertEquals(1, frames.count, "a pairing code fits in one symbol; the split path is the other test")
        assertEquals(code, reassembled(frames), "the code must come back out of the pixels unchanged")
        assertEquals(
            code,
            Qualia.unwrapQrText(textOf(frames.image(0))),
            "and the single-code reader the pairing screens use must still unwrap it",
        )

        // What was reassembled is still a pairing code: the record and the payload both come back.
        val parsed = assertNotNull(parsePairingCode(reassembled(frames)), "the code must parse as a pairing code")
        assertEquals(payload, parsed.payload, "the payload the other device asks with must be unchanged")
        assertEquals(record().toList(), parsed.record?.toList(), "the host's record must be unchanged")
    }

    /**
     * **A pairing code too large for one symbol is split, not dropped.**
     *
     * The single-symbol path answers `null` for a payload above the encoding's capacity, and a
     * screen that had only that path would show the text and no code at all — which is what the
     * pairing screen used to do with a long enough payload. The assertion on `qrImage` is what
     * makes "too large" a fact this test establishes rather than a number it guessed: the text
     * below is eight payloads' worth, and if the symbol could hold it the assertion would say so.
     */
    @Test
    fun aPairingCodeTooLargeForOneSymbolIsSplitAcrossCodes() {
        val code = pairingCodeText(record(), hostPayload().repeat(8))

        assertNull(Qualia.qrImage(code), "this text must be too large for one symbol, or the test proves nothing")

        val frames = assertNotNull(Qualia.qrFrames(code), "a code too large for one symbol must still be drawn")
        assertTrue(frames.count > 1, "the payload must be split, got ${frames.count} code(s)")
        assertEquals(code, reassembled(frames), "the split codes must reassemble to the text that was drawn")
    }

    /**
     * A payload that fits is drawn as the same symbol the single-code path would have drawn.
     *
     * Everything is drawn through the multi-frame path now, so this is what says the change is
     * invisible for the ordinary case rather than merely equivalent in meaning.
     */
    @Test
    fun oneCodeIsDrawnTheSameWayByBothPaths() {
        val text = "otpauth://totp/Example:alice?secret=MZXW6YTBOI"
        val single = assertNotNull(Qualia.qrImage(text), "the text must fit in one code")
        val frames = assertNotNull(Qualia.qrFrames(text), "the text must fit in one code")

        assertEquals(1, frames.count)
        val drawn = frames.image(0)
        assertEquals(single.width, drawn.width)
        assertEquals(single.height, drawn.height)
        assertEquals(
            single.pixels.toList(),
            drawn.pixels.toList(),
            "one code must be the same pixels whichever path produced it",
        )
    }
}
