package dev.qualia.qualia2fa

import dev.qualia.qualia2fa.ffi.FfiAlgorithm
import dev.qualia.qualia2fa.ffi.FfiDigits
import dev.qualia.qualia2fa.ffi.FfiDeviceKeys
import dev.qualia.qualia2fa.ffi.FfiEcLevel
import dev.qualia.qualia2fa.ffi.FfiJoiner
import dev.qualia.qualia2fa.ffi.FfiLogin
import dev.qualia.qualia2fa.ffi.FfiPlatformKeys
import dev.qualia.qualia2fa.ffi.FfiSecureStore
import dev.qualia.qualia2fa.ffi.qrDecode as ffiQrDecode
import dev.qualia.qualia2fa.ffi.qrEncode as ffiQrEncode
import dev.qualia.qualia2fa.ffi.FfiVault
import dev.qualia.qualia2fa.ffi.FfiVaultEntry
import dev.qualia.qualia2fa.ffi.coreVersion as ffiCoreVersion
import dev.qualia.qualia2fa.ffi.generateSecretBase32 as ffiGenerateSecretBase32
import dev.qualia.qualia2fa.ffi.importRecoveryKey as ffiImportRecoveryKey
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
                buildEntry(PLACEHOLDER_ID, name, issuer, account, secretBase32, digits, periodSeconds, note),
                unixTime.toULong(),
            )
        } catch (_: Exception) {
            null
        }

    /**
     * Replaces the content of [id], keeping the identifier and the creation time, which
     * the core preserves on its own.
     *
     * Returns false when the entry does not exist or the new content is rejected.
     */
    fun updateEntry(
        id: String,
        name: String,
        issuer: String,
        account: String,
        secretBase32: String,
        digits: Int,
        periodSeconds: Long,
        note: String,
        unixTime: Long,
    ): Boolean =
        try {
            inner.updateEntry(
                buildEntry(id, name, issuer, account, secretBase32, digits, periodSeconds, note),
                unixTime.toULong(),
            )
            true
        } catch (_: Exception) {
            false
        }

    /** Removes an entry. Returns false when there was nothing to remove. */
    fun removeEntry(id: String, unixTime: Long): Boolean =
        try {
            inner.removeEntry(id, unixTime.toULong())
        } catch (_: Exception) {
            false
        }

    /**
     * Authorises [host]'s pairing request with this vault's key.
     *
     * This is the moment the master key is handed to the other device, so the caller must
     * have asked the user first, and should show [PairingHost.joinerDeviceId] while doing
     * it. Returns the reply to carry back, or `null` when the request was not accepted.
     */
    fun authorize(host: PairingHost, request: ByteArray): ByteArray? = host.authorizeWith(inner, request)

    /** The vault file contents, to be written to disk by the caller. */
    fun serialize(): ByteArray? =
        try {
            inner.serialize()
        } catch (_: Exception) {
            null
        }

    /**
     * The recovery file for this vault, protected by [passphrase].
     *
     * The master key does not cross this boundary as a value: it is wrapped into the file
     * by the core, with the key derivation parameters the core picks.
     */
    fun recoveryFile(passphrase: String): ByteArray? =
        try {
            inner.exportRecoveryKey(passphrase, null, null, null)
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

/**
 * Builds a generated entry from plain values.
 *
 * The timestamps are left at zero: the core stamps a new entry itself and preserves the
 * creation time of an existing one, so sending anything here would be ignored at best.
 */
private fun buildEntry(
    id: String,
    name: String,
    issuer: String,
    account: String,
    secretBase32: String,
    digits: Int,
    periodSeconds: Long,
    note: String,
): FfiVaultEntry =
    FfiVaultEntry(
        id = id,
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
    )

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

    /**
     * Registers this device: generates the master key, creates the device identity in the
     * platform key store, and writes the master key to [store].
     *
     * This is the path that can be paired and synced later, because the device identity
     * is what the login handshake proves. Returns `null` when the platform key store is
     * unavailable, which is a state to report to the user rather than to work around
     * silently.
     */
    fun register(store: MasterKeyStore, label: String): Registration? =
        try {
            val keys = FfiPlatformKeys.open(label)
            val vault = Vault(FfiVault.register(PlatformKeysBridge(keys), StoreBridge(store), label))
            Registration(vault = vault, backend = keys.backend(), hardwareBacked = keys.isHardwareBacked())
        } catch (_: Exception) {
            null
        }

    /**
     * Imports a recovery file and returns the vault it unlocks.
     *
     * The core writes the master key into [store] and hands back its bytes, which are
     * wiped here as soon as the vault holds its own copy. Returns `null` when the
     * passphrase is wrong or the file is not a recovery file.
     */
    fun importRecoveryFile(file: ByteArray, passphrase: String, store: MasterKeyStore): Vault? =
        try {
            val key = ffiImportRecoveryKey(file, passphrase, StoreBridge(store))
            val vault = Vault(FfiVault.fromMasterKey(key))
            wipe(key)
            vault
        } catch (_: Exception) {
            null
        }

    /** Starts the host side of a pairing: the text it returns is what goes into the code. */
    fun beginPairing(): PairingHost? =
        try {
            PairingHost(FfiLogin.begin())
        } catch (_: Exception) {
            null
        }

    /**
     * Starts the joining side from the text that was scanned, using [label] for this
     * device's identity key pair. Returns `null` when the text is not a pairing code or
     * the platform key store is unavailable.
     */
    fun joinPairing(qrText: String, label: String): PairingJoiner? =
        try {
            PairingJoiner(FfiJoiner.fromQr(qrText, label), FfiPlatformKeys.open(label))
        } catch (_: Exception) {
            null
        }

    /** The master key length, in bytes, that the core expects. */
    const val MASTER_KEY_BYTES = 32

    /**
     * Draws [text] as a QR code: [scale] pixels per module, [quietZone] modules of margin.
     *
     * Returns `null` when the text does not fit in the largest version this facade allows.
     * The code is never truncated and the error correction level is never lowered
     * silently: a code that does not fit is a code the user must be told about.
     */
    fun qrImage(text: String, scale: Int = 4, quietZone: Int = 4): QrImage? =
        try {
            val image = ffiQrEncode(text, FfiEcLevel.MEDIUM, QR_MAX_VERSION, scale.toUShort(), quietZone.toUShort())
            QrImage(image.width.toInt(), image.height.toInt(), image.pixels)
        } catch (_: Exception) {
            null
        }

    /**
     * Reads the text out of a **grayscale** image, one byte per pixel.
     *
     * The caller decodes the image file itself (`ImageIO` here, the platform API
     * elsewhere) and converts it to grayscale: image format decoders are where the
     * historical vulnerabilities live, so they stay on the platform side.
     *
     * Returns `null` when there is no readable code in the image, including the case
     * where the decoder's own panic had to be contained.
     */
    fun qrTextFromImage(pixels: ByteArray, width: Int, height: Int): String? =
        try {
            ffiQrDecode(pixels, width.toUInt(), height.toUInt())
        } catch (_: Exception) {
            null
        }

    /**
     * The largest QR version this facade draws, which is 97×97 modules.
     *
     * A pairing payload or an `otpauth://` entry fits well inside it; anything larger is
     * refused rather than drawn at a size no camera will read.
     */
    private const val QR_MAX_VERSION: UByte = 20u
}

/**
 * The device that already holds the account, on the side that shows the code.
 *
 * Its payload is a short text; whoever scans it can ask to be let in. Nothing is handed
 * over until [Vault.authorize] is called, and the user is the one who decides that.
 */
class PairingHost internal constructor(private val inner: FfiLogin) {
    /** The text to put into the QR code. */
    val payload: String get() = inner.payload()

    /**
     * The device identifier of the requester, available once its request arrived. Show it
     * to the user: comparing it out of band is what makes the confirmation mean something.
     */
    val joinerDeviceId: String? get() = inner.joinerDeviceId()

    internal fun authorizeWith(vault: FfiVault, request: ByteArray): ByteArray? =
        try {
            inner.authorize(vault, request)
        } catch (_: Exception) {
            null
        }

    /**
     * The reply to carry back to the joiner, in the text form the code channel uses.
     *
     * Returns `null` when the request was not accepted, which is also what happens when
     * the request did not come from this pairing.
     */
    fun replyTo(requestText: String, vault: Vault): String? {
        val request = decodeFromText(requestText) ?: return null
        val reply = vault.authorize(this, request) ?: return null
        return encodeToText(reply)
    }
}

/**
 * The device that is joining, on the side that scanned the code.
 *
 * It carries its own device identity from the platform key store, which is what the host
 * verifies before releasing the master key.
 */
class PairingJoiner internal constructor(
    private val inner: FfiJoiner,
    private val keys: FfiPlatformKeys,
) {
    /** The request to carry back to the host. */
    fun request(): ByteArray? =
        try {
            inner.request(PlatformKeysBridge(keys))
        } catch (_: Exception) {
            null
        }

    /** The request in the text form the code channel uses, or `null` if it failed. */
    fun requestText(): String? = request()?.let(::encodeToText)

    /** This device's public identity, once [request] produced one. */
    fun identity(): ByteArray? = inner.identity()

    /**
     * Completes the pairing from the host's reply.
     *
     * Verifies the reply, writes the master key into [store] and returns the vault it
     * unlocks, or `null` when the reply did not verify — a wrong session, a tampered
     * message, or a host using a different master key all look the same from here.
     */
    fun complete(reply: ByteArray, store: MasterKeyStore): Vault? =
        try {
            val key = inner.complete(reply, PlatformKeysBridge(keys), StoreBridge(store))
            val vault = Vault(FfiVault.fromMasterKey(key))
            wipe(key)
            vault
        } catch (_: Exception) {
            null
        }

    /** [complete] with the reply in the text form the code channel uses. */
    fun completeText(replyText: String, store: MasterKeyStore): Vault? {
        val reply = decodeFromText(replyText) ?: return null
        return complete(reply, store)
    }
}

/**
 * A QR code as grayscale pixels, ready to be drawn.
 *
 * Plain values only: the generated record does not leave this file.
 */
class QrImage(val width: Int, val height: Int, val pixels: ByteArray)

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

/**
 * Adapts the platform key store to the callback interface the binding layer expects.
 *
 * The generated object is already bound to one label, so the label the callback carries
 * is the same one and is not used again here. It forwards and decides nothing.
 */
private class PlatformKeysBridge(private val inner: FfiPlatformKeys) : FfiDeviceKeys {
    override fun `getOrCreate`(`label`: String): ByteArray = inner.devicePublicKey()

    override fun `sign`(`label`: String, `message`: ByteArray): ByteArray = inner.sign(message)
}

/**
 * What a successful registration produced.
 *
 * [backend] and [hardwareBacked] come from the platform key store and are reported to the
 * user: a key that is not hardware backed is a fact the user is entitled to know.
 */
data class Registration(
    val vault: Vault,
    val backend: String,
    val hardwareBacked: Boolean,
)
