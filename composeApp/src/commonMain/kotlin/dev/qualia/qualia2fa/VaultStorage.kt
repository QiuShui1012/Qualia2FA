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

    /**
     * Removes the vault file and the master key, leaving this device as it was before it was
     * registered.
     *
     * The two are removed together because either one alone is useless and, worse, misleading:
     * a key without a vault opens nothing, and a vault without its key can never be read again
     * on this device. What survives is what was exported or synced elsewhere.
     *
     * Removing something that is absent is not an error: a device that was never registered
     * has nothing to remove, and asking for a reset is still a reasonable thing to have done.
     */
    fun reset()

    /**
     * The master key store of this device, or `null` when this machine has none.
     *
     * `null` is a machine whose platform key store cannot hold a key-encryption key: there is
     * nowhere to keep a master key, so there is nothing to register and nothing to unlock. The
     * user interface says so rather than offering a vault that could never be opened again.
     */
    val masterKeyStore: MasterKeyStore?
}

/**
 * What protects the master key, as the login and settings screens show it.
 *
 * [backend] is the platform key store's own name for itself — `"tpm"`, `"android-keystore"`,
 * `"software"`, `"secure-enclave"` — and [hardwareBacked] says whether that name is hardware
 * protection. Both are reported as they are, because the user is being asked to trust them.
 */
data class MasterKeyProtection(
    val backend: String,
    val hardwareBacked: Boolean,
)
