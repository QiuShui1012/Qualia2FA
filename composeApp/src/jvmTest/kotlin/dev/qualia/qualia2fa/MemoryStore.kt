package dev.qualia.qualia2fa

/**
 * A master key store kept in memory, so the tests never touch the user's files.
 *
 * [initial] is the master key it starts with; a test that needs two devices of one account
 * copies the key from the first store into a second one.
 */
internal class MemoryStore(initial: ByteArray? = null) : MasterKeyStore {
    private var key: ByteArray? = initial

    override fun load(): ByteArray? = key

    override fun store(key: ByteArray) {
        // The contract requires a copy: the caller wipes its buffer right after this.
        this.key = key.copyOf()
    }

    override fun delete() {
        key = null
    }

    override val isHardwareBacked: Boolean = false
}
