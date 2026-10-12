package dev.qualia.qualia2fa

/**
 * Where the vault file and the master key live.
 *
 * The user interface works through this interface, so the same screens run on any
 * platform once a platform implementation exists.
 */
interface VaultStorage {
    /** The vault file contents, or `null` when the device has no vault yet. */
    fun loadVault(): ByteArray?

    /** Writes the vault file, replacing any previous contents. */
    fun saveVault(data: ByteArray)

    /** The master key store of this device. */
    val masterKeyStore: MasterKeyStore
}
