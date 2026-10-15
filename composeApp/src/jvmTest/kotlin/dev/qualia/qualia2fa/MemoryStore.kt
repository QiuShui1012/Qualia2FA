package dev.qualia.qualia2fa

/**
 * A master key store kept in memory, so the tests never touch the user's files.
 *
 * [initial] is the master key it starts with; a test that needs two devices of one account
 * copies the key from the first store into a second one.
 *
 * # What it reports
 *
 * Software, and truthfully so: it holds the key in this process's heap, which is no
 * protection at all. The first-run warning follows [isHardwareBacked], and a double that
 * claimed hardware would walk a test through the branch a real user only sees on a machine
 * with a platform key store.
 */
internal class MemoryStore(
    initial: ByteArray? = null,
    override val backend: String = "software",
    override val isHardwareBacked: Boolean = false,
) : MasterKeyStore {
    private var key: ByteArray? = initial

    override fun load(): ByteArray? = key

    override fun store(key: ByteArray) {
        // The contract requires a copy: the caller wipes its buffer right after this.
        this.key = key.copyOf()
    }

    override fun delete() {
        key = null
    }

    override val exists: Boolean get() = key != null
}
