package dev.qualia.qualia2fa

import dev.qualia.qualia2fa.ffi.FfiAlgorithm
import dev.qualia.qualia2fa.ffi.FfiDigits
import dev.qualia.qualia2fa.ffi.FfiSecureStore
import dev.qualia.qualia2fa.ffi.FfiVault
import dev.qualia.qualia2fa.ffi.FfiVaultEntry
import dev.qualia.qualia2fa.ffi.coreVersion as ffiCoreVersion
import dev.qualia.qualia2fa.ffi.generateSecretBase32 as ffiGenerateSecretBase32
import dev.qualia.qualia2fa.ffi.totpCode as ffiTotpCode

/**
 * Where the master key is kept.
 *
 * This is the facade's own interface, not the generated one: the application implements
 * it with a file, and a platform store will replace that later. [isHardwareBacked] must
 * be answered truthfully, because the user interface asks for consent when it is false.
 */
interface MasterKeyStore {
    /** The 32-byte master key, or `null` when this device was never registered. */
    fun load(): ByteArray?

    /**
     * Writes the master key, overwriting any existing value.
     *
     * The store **must copy** the bytes: the caller wipes its own buffer as soon as this
     * returns, so keeping the reference would leave the store holding zeros.
     */
    fun store(key: ByteArray)

    /** Deletes the master key; deleting a missing key is not an error. */
    fun delete()

    /** Whether the store is backed by hardware (TPM, Secure Enclave, Android Keystore). */
    val isHardwareBacked: Boolean
}

/**
 * One entry, as the user interface sees it.
 *
 * A plain Kotlin value: no generated type crosses this boundary.
 */
data class Entry(
    val id: String,
    val name: String,
    val issuer: String,
    val account: String,
    val secretBase32: String,
    val digits: Int,
    val periodSeconds: Long,
    val counter: Long?,
    val note: String,
) {
    val isCounterBased: Boolean get() = counter != null
}

/**
 * An open vault.
 *
 * The generated `FfiVault` is private to this file, so the binding layer can be replaced
 * without touching the user interface.
 */
class Vault internal constructor(private val inner: FfiVault) {
    val entryCount: Int get() = inner.entryCount().toInt()

    val isEmpty: Boolean get() = inner.isEmpty()

    /** Every entry. The order is not defined, so callers sort it themselves. */
    fun entries(): List<Entry> =
        inner.entries().map { it.toEntry() }.sortedWith(compareBy({ it.name.lowercase() }, { it.issuer.lowercase() }))

    /**
     * The current time-based code for [id], or `null` when the entry is counter based or
     * the code cannot be computed.
     */
    fun code(id: String, unixTime: Long): String? =
        try {
            inner.totpFor(id, unixTime.toULong())
        } catch (_: Exception) {
            null
        }

    /**
     * Adds an entry and returns its identifier, or `null` when the secret or the
     * parameters are rejected.
     */
    fun addEntry(
        name: String,
        issuer: String,
        account: String,
        secretBase32: String,
        digits: Int,
        periodSeconds: Long,
        note: String,
        unixTime: Long,
    ): String? =
        try {
            inner.addEntry(
                FfiVaultEntry(
                    id = PLACEHOLDER_ID,
                    name = name,
                    issuer = issuer.ifBlank { null },
                    account = account,
                    secretBase32 = secretBase32,
                    algorithm = FfiAlgorithm.SHA1,
                    digits = if (digits == 8) FfiDigits.EIGHT else FfiDigits.SIX,
                    period = periodSeconds.toULong(),
                    counter = null,
                    note = note,
                    createdAt = 0uL,
                    updatedAt = 0uL,
                ),
                unixTime.toULong(),
            )
        } catch (_: Exception) {
            null
        }

    /** Removes an entry. Returns false when there was nothing to remove. */
    fun removeEntry(id: String, unixTime: Long): Boolean =
        try {
            inner.removeEntry(id, unixTime.toULong())
        } catch (_: Exception) {
            false
        }

    /** The vault file contents, to be written to disk by the caller. */
    fun serialize(): ByteArray? =
        try {
            inner.serialize()
        } catch (_: Exception) {
            null
        }

    private companion object {
        /**
         * The identifier of a new entry, as the core expects it: the core assigns the
         * real one and ignores this value.
         */
        const val PLACEHOLDER_ID = "00000000000000000000000000000000"
    }
}

private fun FfiVaultEntry.toEntry(): Entry =
    Entry(
        id = id,
        name = name,
        issuer = issuer.orEmpty(),
        account = account,
        secretBase32 = secretBase32,
        digits = if (digits == FfiDigits.EIGHT) 8 else 6,
        periodSeconds = period.toLong(),
        counter = counter?.toLong(),
        note = note,
    )

/**
 * The narrow facade over the generated UniFFI bindings.
 *
 * This is the only file in the application that names a generated type or function, so
 * the binding layer can be replaced, patched or forked without touching the user
 * interface. Callers pass and receive plain Kotlin values: the generated `Ffi*` types and
 * the `FfiException` error type do not escape this file.
 *
 * The imports are aliased so that a facade member can never shadow the generated function
 * of the same name and turn a call into infinite recursion.
 */
object Qualia {
    /**
     * The version of the Rust core library that is actually loaded, as reported by the
     * library itself rather than by the build configuration.
     */
    fun coreVersion(): String = ffiCoreVersion()

    /** A fresh Base32 secret, drawn from the platform random number generator. */
    fun newSecret(): String = ffiGenerateSecretBase32()

    /** The RFC 6238 code for [secretBase32] at [unixTime], or `null` on invalid input. */
    fun totp(
        secretBase32: String,
        unixTime: Long,
        periodSeconds: Long = 30,
        digits: Int = 6,
    ): String? =
        try {
            ffiTotpCode(
                secretBase32 = secretBase32,
                unixTime = unixTime.toULong(),
                period = periodSeconds.toULong(),
                algorithm = FfiAlgorithm.SHA1,
                digits = if (digits == 8) FfiDigits.EIGHT else FfiDigits.SIX,
            )
        } catch (_: Exception) {
            null
        }

    /**
     * Opens the vault in [data] with the master key from [store], or returns `null` when
     * the file cannot be decrypted, which means the key does not belong to it.
     */
    fun openVault(data: ByteArray, store: MasterKeyStore): Vault? =
        try {
            Vault(FfiVault.open(StoreBridge(store), data))
        } catch (_: Exception) {
            null
        }

    /**
     * Creates an empty vault with a fresh master key and writes the key to [store].
     *
     * This is the local path: it does not create a device identity, so the vault cannot
     * be paired or synced yet, and the key is only as safe as the store is. The
     * registered path, which also creates the device identity in the platform key store,
     * is what the login flow needs.
     */
    fun createLocalVault(store: MasterKeyStore): Vault? =
        try {
            val key = randomBytes(MASTER_KEY_BYTES)
            val vault = Vault(FfiVault.fromMasterKey(key))
            store.store(key)
            // The store keeps its own copy; this one does not outlive the call.
            wipe(key)
            vault
        } catch (_: Exception) {
            null
        }

    /** Whether this device already holds a master key. */
    fun hasMasterKey(store: MasterKeyStore): Boolean = store.load() != null

    /** The master key length, in bytes, that the core expects. */
    const val MASTER_KEY_BYTES = 32
}

/**
 * Adapts the facade's store interface to the one the binding layer expects.
 *
 * It forwards and decides nothing, which is the whole contract of that interface.
 */
private class StoreBridge(private val inner: MasterKeyStore) : FfiSecureStore {
    override fun `loadMasterKey`(): ByteArray? = inner.load()

    override fun `storeMasterKey`(`key`: ByteArray) = inner.store(key)

    override fun `deleteMasterKey`() = inner.delete()

    override fun `isHardwareBacked`(): Boolean = inner.isHardwareBacked
}
