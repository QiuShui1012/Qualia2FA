package dev.qualia.qualia2fa

import dev.qualia.qualia2fa.ffi.FfiAlgorithm
import dev.qualia.qualia2fa.ffi.FfiConflict
import dev.qualia.qualia2fa.ffi.FfiConflictSide
import dev.qualia.qualia2fa.ffi.FfiDecision
import dev.qualia.qualia2fa.ffi.FfiDeviceKeys
import dev.qualia.qualia2fa.ffi.FfiDigits
import dev.qualia.qualia2fa.ffi.FfiEcLevel
import dev.qualia.qualia2fa.ffi.FfiEntryKind
import dev.qualia.qualia2fa.ffi.FfiExportFormat
import dev.qualia.qualia2fa.ffi.FfiFrameCollector
import dev.qualia.qualia2fa.ffi.FfiException
import dev.qualia.qualia2fa.ffi.FfiImport
import dev.qualia.qualia2fa.ffi.FfiGroup
import dev.qualia.qualia2fa.ffi.FfiImportChoice
import dev.qualia.qualia2fa.ffi.FfiImportDecision
import dev.qualia.qualia2fa.ffi.FfiImportFormat
import dev.qualia.qualia2fa.ffi.FfiJoiner
import dev.qualia.qualia2fa.ffi.FfiLanChannel
import dev.qualia.qualia2fa.ffi.FfiLanLobby
import dev.qualia.qualia2fa.ffi.FfiLanPeer
import dev.qualia.qualia2fa.ffi.FfiLogin
import dev.qualia.qualia2fa.ffi.FfiPayloadKind
import dev.qualia.qualia2fa.ffi.FfiPlatformKeys
import dev.qualia.qualia2fa.ffi.FfiReceivedMessage
import dev.qualia.qualia2fa.ffi.FfiRecipient
import dev.qualia.qualia2fa.ffi.FfiRoster
import dev.qualia.qualia2fa.ffi.FfiSecureStore
import dev.qualia.qualia2fa.ffi.FfiSyncOutcome
import dev.qualia.qualia2fa.ffi.FfiSyncSession
import dev.qualia.qualia2fa.ffi.FfiTransport
import dev.qualia.qualia2fa.ffi.FfiTransportCapabilities
import dev.qualia.qualia2fa.ffi.FfiWrappedMasterKeyStore
import dev.qualia.qualia2fa.ffi.detectImportFormat as ffiDetectImportFormat
import dev.qualia.qualia2fa.ffi.parseOtpauthUri as ffiParseOtpAuthUri
import dev.qualia.qualia2fa.ffi.qrDecodeText as ffiQrDecodeText
import dev.qualia.qualia2fa.ffi.qrEncodeFrames as ffiQrEncodeFrames
import dev.qualia.qualia2fa.ffi.qrFrameCapacity as ffiQrFrameCapacity
import dev.qualia.qualia2fa.ffi.qrUnwrapText as ffiQrUnwrapText
import dev.qualia.qualia2fa.ffi.qrEncode as ffiQrEncode
import dev.qualia.qualia2fa.ffi.FfiVault
import dev.qualia.qualia2fa.ffi.FfiVaultEntry
import dev.qualia.qualia2fa.ffi.coreVersion as ffiCoreVersion
import dev.qualia.qualia2fa.ffi.exportFormatCarriesGroups as ffiExportFormatCarriesGroups
import dev.qualia.qualia2fa.ffi.exportFormatExtension as ffiExportFormatExtension
import dev.qualia.qualia2fa.ffi.exportFormatNeedsPassword as ffiExportFormatNeedsPassword
import dev.qualia.qualia2fa.ffi.generateSecretBase32 as ffiGenerateSecretBase32
import dev.qualia.qualia2fa.ffi.importRecoveryKey as ffiImportRecoveryKey
import dev.qualia.qualia2fa.ffi.totpCode as ffiTotpCode
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Where the master key is kept.
 *
 * This is the facade's own interface, not the generated one: what implements it is
 * [WrappedMasterKeyStore], which is the platform key store of `qualia-dik` behind this
 * facade's types, opened through [openWrappedMasterKeyStore].
 *
 * # The material is written and read as bytes, and persists as something else
 *
 * The core takes the master key as its 32 bytes, so [load] and [store] exchange bytes and
 * nothing else. What the implementation *persists* is its own business: the wrapped form
 * keeps a key-encryption key in the platform key store and writes only the bytes that key
 * produced, so a copy of the file is not a copy of the vault's key.
 *
 * # `null` means "nothing to read"
 *
 * [load] answers `null` when this device holds no master key, whether because it was never
 * registered or because what was persisted can no longer be unwrapped. Both are states the
 * user interface reports; neither is worth an exception, and the caller cannot tell them
 * apart from a successful read that happens to be `null`.
 */
interface MasterKeyStore {
    /** The 32-byte master key, or `null` when this device has none to read. */
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

    /**
     * Whether a master key has been persisted here.
     *
     * A read of what is on this device, not of the platform store: opening the key store to
     * unwrap a key that is not there would be a hardware call to answer a question about a
     * file, and the answer would be the same.
     */
    val exists: Boolean

    /**
     * Whether the key that protects the master key cannot leave hardware.
     *
     * **Must be answered truthfully**, because the user interface asks for consent when it
     * is false.
     */
    val isHardwareBacked: Boolean

    /**
     * The platform key store that answered, as the library names it: `"tpm"`,
     * `"android-keystore"`, `"software"` or `"secure-enclave"`.
     *
     * Reported as it is, and only read for the user interface: which backend protects the
     * account is something the user is entitled to know.
     */
    val backend: String
}

/**
 * The HMAC algorithm an entry's codes are computed with.
 *
 * [label] is what the interface shows and what an `otpauth://` link carries. Both are the
 * RFC's own names, so they read the same in every language and are not translated: a user
 * comparing the application against a server's setup page has to see the same three strings
 * on both.
 */
enum class OtpAlgorithm {
    /** HMAC-SHA-1, the default the `otpauth://` scheme assumes when it says nothing. */
    Sha1,

    /** HMAC-SHA-256. */
    Sha256,

    /** HMAC-SHA-512. */
    Sha512,
    ;

    /** The name shown in the interface, which is the name a link carries. */
    val label: String
        get() =
            when (this) {
                Sha1 -> "SHA-1"
                Sha256 -> "SHA-256"
                Sha512 -> "SHA-512"
            }
}

/**
 * The kind of an entry: what decides how its code is computed.
 *
 * The library stores this explicitly, and it is not a detail the interface may derive: an mOTP
 * entry, a Yandex OTP entry and a Steam Guard entry each compute a different shape of code from
 * the same secret, and treating one as an ordinary TOTP entry produces codes that never match.
 *
 * [Motp] and [Yandex] carry a PIN, and the library never hands one back — it is key material with
 * no display purpose, and the only thing that needs to read it computes the code. `pinSet` is what
 * the interface gets instead, and the PIN is passed in when an entry is created or changed.
 */
/**
 * The time step a time-based entry uses when nothing says otherwise, in seconds.
 *
 * The value RFC 6238's own examples use, and the one a scanned link without a `period` means.
 */
const val DEFAULT_PERIOD_SECONDS = 30L

sealed interface EntryKind {
    /** Time-based (RFC 6238). */
    data class Totp(val periodSeconds: Long) : EntryKind

    /** Counter-based (RFC 4226). */
    data class Hotp(val counter: Long) : EntryKind

    /** mOTP: MD5, a ten-second step, six hexadecimal characters, and a PIN. */
    data class Motp(val periodSeconds: Long, val pinSet: Boolean) : EntryKind

    /** Yandex OTP: SHA-256, eight lower-case letters, and a PIN. */
    data class Yandex(val periodSeconds: Long, val pinSet: Boolean) : EntryKind

    /** Steam Guard: SHA-1, five characters, a thirty-second step. */
    data object Steam : EntryKind
}

/**
 * One entry, as the user interface sees it.
 *
 * A plain Kotlin value: no generated type crosses this boundary.
 *
 * [kind] says what the entry is: a time step, a counter, or one of the schemes that compute their
 * own shape of code. The accessors below it — [periodSeconds], [counter], [isCounterBased] — are
 * derived from it, so a screen may still ask the question the old two-kind model asked.
 */
data class Entry(
    val id: String,
    val name: String,
    val issuer: String,
    val account: String,
    val secretBase32: String,
    val algorithm: OtpAlgorithm,
    val digits: Int,
    val kind: EntryKind,
    val note: String,
) {
    /**
     * The time step, for the kinds that have one.
     *
     * A counter-based entry has no step, and a Steam Guard entry's step is fixed by the scheme
     * rather than stored, so this is the step a code would use rather than a field of the entry.
     */
    val periodSeconds: Long
        get() =
            when (val kind = kind) {
                is EntryKind.Totp -> kind.periodSeconds
                is EntryKind.Motp -> kind.periodSeconds
                is EntryKind.Yandex -> kind.periodSeconds
                is EntryKind.Hotp, EntryKind.Steam -> DEFAULT_PERIOD_SECONDS
            }

    /** The counter, for the kind that counts. */
    val counter: Long? get() = (kind as? EntryKind.Hotp)?.counter

    val isCounterBased: Boolean get() = kind is EntryKind.Hotp
}

/**
 * An open vault.
 *
 * The generated `FfiVault` is private to this file, so the binding layer can be replaced
 * without touching the user interface.
 */
class Vault internal constructor(private val inner: FfiVault) {
    /**
     * The generated handle, for the parts of this file that need to hand the vault to the
     * library - comparing an import against it, and applying one to it. It stays inside this
     * file: nothing outside names a generated type.
     */
    internal fun handle(): FfiVault = inner

    val entryCount: Int get() = inner.entryCount().toInt()

    val isEmpty: Boolean get() = inner.isEmpty()

    /** Every entry. The order is not defined, so callers sort it themselves. */
    fun entries(): List<Entry> =
        inner.entries().map { it.toEntry() }.sortedWith(compareBy({ it.name.lowercase() }, { it.issuer.lowercase() }))

    /**
     * The current time-based code for [id], or `null` when the entry is counter based or
     * the code cannot be computed.
     *
     * A counter-based entry has no code at a moment in time: its code belongs to a counter,
     * and asking the library for a time-based one anyway would produce a plausible code that
     * is always wrong. [counterCode] is what answers for those entries, and [codeFor] is the
     * call that picks between the two from the entry itself.
     */
    fun code(id: String, unixTime: Long): String? =
        try {
            inner.codeFor(id, unixTime.toULong())
        } catch (_: Exception) {
            null
        }

    /**
     * The RFC 4226 code for the counter-based entry [id] at [counter].
     *
     * [counter] is the value the code belongs to, not a step to move by: a server consumes a
     * code by moving to the next counter, and [advanceCounter] is what records that move here.
     * Returns `null` when the entry is time based or the code cannot be computed.
     */
    fun counterCode(id: String, counter: Long): String? =
        try {
            inner.hotpFor(id, counter.toULong())
        } catch (_: Exception) {
            null
        }

    /**
     * The code [entry] shows now: its counter's code when it counts, the current step's when it
     * does not.
     *
     * One call for both kinds, so that a screen cannot show a time-based code for an entry whose
     * codes belong to a counter.
     */
    fun codeFor(entry: Entry, unixTime: Long): String? =
        entry.counter?.let { counterCode(entry.id, it) } ?: code(entry.id, unixTime)

    /**
     * Writes the whole vault out in [format], or `null` when it cannot be written.
     *
     * The bytes come back rather than being written anywhere: where the file goes is the screen's
     * decision, and it is the platform's own save dialog that asks.
     *
     * **Only [ExportFormat.EncryptedJson] protects anything.** The other three hold every secret
     * as it stands, because that is what makes them readable by another application. A screen
     * offering the choice has to say so before the file exists.
     *
     * [password] is looked at only by [ExportFormat.EncryptedJson], and there an **empty** string
     * is refused rather than accepted: a file anybody can open by trying nothing is not protected.
     * A caller offering that format asks for the password first (see [ExportFormat.needsPassword]).
     */
    fun export(format: ExportFormat, password: String? = null): ByteArray? =
        try {
            inner.export(format.toFfi(), password)
        } catch (_: Exception) {
            null
        }

    /**
     * Moves a counter-based entry to its next counter.
     *
     * A counter is consumed by use: the server that accepted the code at counter `n` expects
     * counter `n + 1` next, so advancing is what makes the next code the one that works.
     * Returns false when the entry does not exist, is time based, or is already at the largest
     * counter the field can hold.
     */
    fun advanceCounter(id: String, unixTime: Long): Boolean {
        return try {
            val entry = inner.entry(id) ?: return false
            val kind = entry.kind as? FfiEntryKind.Hotp ?: return false
            if (kind.counter >= Long.MAX_VALUE.toULong()) return false
            inner.updateEntry(
                entry.copy(kind = FfiEntryKind.Hotp(counter = kind.counter + 1uL)),
                unixTime.toULong(),
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Adds an entry and returns its identifier, or `null` when the secret or the
     * parameters are rejected.
     *
     * [algorithm] is required rather than defaulted: a default here would be the one place an
     * SHA-256 link could quietly become an SHA-1 entry, and the codes would be wrong rather
     * than missing. [counter] is required for the same reason: `null` asks for a time-based
     * entry, and a caller that forgot it would otherwise get one where it meant a counter.
     */
    fun addEntry(
        name: String,
        issuer: String,
        account: String,
        secretBase32: String,
        algorithm: OtpAlgorithm,
        digits: Int,
        kind: EntryKind,
        note: String,
        unixTime: Long,
        pin: String? = null,
    ): String? {
        val entry =
            buildEntry(
                PLACEHOLDER_ID,
                name,
                issuer,
                account,
                secretBase32,
                algorithm,
                digits,
                kind,
                note,
                pin,
            ) ?: return null
        return try {
            inner.addEntry(entry, unixTime.toULong())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Replaces the content of [id], keeping the identifier and the creation time, which
     * the core preserves on its own.
     *
     * Returns false when the entry does not exist or the new content is rejected. [counter]
     * carries the same meaning as in [addEntry], and `null` turns a counter-based entry into a
     * time-based one.
     */
    fun updateEntry(
        id: String,
        name: String,
        issuer: String,
        account: String,
        secretBase32: String,
        algorithm: OtpAlgorithm,
        digits: Int,
        kind: EntryKind,
        note: String,
        unixTime: Long,
        pin: String? = null,
    ): Boolean {
        val entry =
            buildEntry(id, name, issuer, account, secretBase32, algorithm, digits, kind, note, pin)
                ?: return false
        return try {
            inner.updateEntry(entry, unixTime.toULong())
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * The groups, the top group first and then the named ones in the order they are shown in.
     *
     * Sorted here rather than left to the caller: the library numbers the top group and the first
     * named group both zero, so sorting every group by that number alone would leave the top
     * group's place to a tie-break.
     */
    fun groups(): List<Group> =
        try {
            inner.groups().map {
                Group(
                    id = it.id,
                    name = it.name,
                    order = it.order.toInt(),
                    top = it.top,
                    entryIds = it.entryIds,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

    /** The identifier of the group [entryId] is in, or `null` when the entry does not exist. */
    fun groupOf(entryId: String): String? =
        try {
            inner.groupOf(entryId)
        } catch (_: Exception) {
            null
        }

    /** Creates a named group and returns its identifier, or `null` when the name is refused. */
    fun createGroup(name: String): String? =
        try {
            inner.createGroup(name)
        } catch (_: Exception) {
            null
        }

    /** Renames a group. The top group has no name and cannot be renamed. */
    fun renameGroup(groupId: String, name: String): Boolean =
        try {
            inner.renameGroup(groupId, name)
            true
        } catch (_: Exception) {
            false
        }

    /**
     * Moves a group to [position] among the **named** groups, counted from zero, and shifts the
     * others: the numbers are an order, not values a caller may leave gaps in.
     */
    fun moveGroup(groupId: String, position: Int): Boolean =
        try {
            inner.moveGroup(groupId, position.toULong())
            true
        } catch (_: Exception) {
            false
        }

    /**
     * Removes a group. Its entries are **not** removed: they return to the top group, which is
     * what makes removing a group a tidying action rather than a destructive one.
     */
    fun removeGroup(groupId: String): Boolean =
        try {
            inner.removeGroup(groupId)
            true
        } catch (_: Exception) {
            false
        }

    /** Writes the order of the entries in one group. Every entry of the group must be listed. */
    fun setOrdering(groupId: String, entryIds: List<String>): Boolean =
        try {
            inner.setOrdering(groupId, entryIds)
            true
        } catch (_: Exception) {
            false
        }

    /** Moves one entry into a group. */
    fun assignToGroup(entryId: String, groupId: String): Boolean =
        try {
            inner.assignToGroup(entryId, groupId)
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
     * Authorizes [host]'s pairing request with this vault's key.
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

    /**
     * Opens a synchronization session on this vault.
     *
     * Kept here rather than opened from the facade directly: the vault owns the generated
     * object, and nothing outside this file names a generated type.
     */
    internal fun openSession(): FfiSyncSession = FfiSyncSession.open(inner)

    private companion object {
        /**
         * The identifier of a new entry, as the core expects it: the core assigns the
         * real one and ignores this value.
         */
        const val PLACEHOLDER_ID = "00000000000000000000000000000000"
    }
}

/**
 * Builds a generated entry from plain values, or `null` when a parameter has no
 * representation on the other side of the boundary.
 *
 * The timestamps are left at zero: the core stamps a new entry itself and preserves the
 * creation time of an existing one, so sending anything here would be ignored at best.
 *
 * [counter] is `null` for a time-based entry and the counter it starts from for a
 * counter-based one: the core's entry has no separate kind field, so this is what says which
 * of the two an entry is.
 */
private fun buildEntry(
    id: String,
    name: String,
    issuer: String,
    account: String,
    secretBase32: String,
    algorithm: OtpAlgorithm,
    digits: Int,
    kind: EntryKind,
    note: String,
    pin: String? = null,
): FfiVaultEntry? {
    val ffiDigits = digitsOf(digits) ?: return null
    return FfiVaultEntry(
        id = id,
        name = name,
        issuer = issuer.ifBlank { null },
        account = account,
        secretBase32 = secretBase32,
        algorithm = algorithm.toFfi(),
        digits = ffiDigits,
        kind = kind.toFfi(pin),
        note = note,
        // Read-only for the library: it never hands a PIN back, and a write takes the value
        // through the kind. This is what the entry being written will hold.
        pinSet = !pin.isNullOrEmpty(),
        createdAt = 0uL,
        updatedAt = 0uL,
    )
}

/**
 * Converts one entry for the user interface.
 *
 * The counter crosses the boundary as an unsigned 64-bit value and is carried here as a signed
 * one: a value above `Long.MAX_VALUE` therefore arrives negative, which is the same bit pattern
 * the library takes back, so a counter survives the round trip whatever its value.
 */
private fun FfiVaultEntry.toEntry(): Entry =
    Entry(
        id = id,
        name = name,
        issuer = issuer.orEmpty(),
        account = account,
        secretBase32 = secretBase32,
        algorithm = algorithm.toFacade(),
        digits = digits.toCount(),
        kind = kind.toFacade(pinSet),
        note = note,
    )

/**
 * The digit counts the scheme and the core have.
 *
 * A count outside this set has **no** representation, and `null` says so rather than rounding
 * to the nearest supported one: an entry silently saved with six digits when seven were asked
 * for produces codes that are always wrong, which is worse than an entry that was not saved.
 */
private fun digitsOf(count: Int): FfiDigits? =
    when (count) {
        5 -> FfiDigits.Five
        6 -> FfiDigits.Six
        7 -> FfiDigits.Seven
        8 -> FfiDigits.Eight
        9 -> FfiDigits.Nine
        in 1..4 -> FfiDigits.Other(count.toUByte())
        else -> null
    }

private fun FfiDigits.toCount(): Int =
    when (this) {
        FfiDigits.Five -> 5
        FfiDigits.Six -> 6
        FfiDigits.Seven -> 7
        FfiDigits.Eight -> 8
        FfiDigits.Nine -> 9
        is FfiDigits.Other -> digits.toInt()
    }

/**
 * The library's kind for a facade kind.
 *
 * [pin] is only meaningful for the two kinds that compute with one, and only when the user
 * supplied it: `null` on an update means "keep the stored PIN", which is what lets a form edit a
 * note without asking for a value it was never shown.
 */
private fun EntryKind.toFfi(pin: String?): FfiEntryKind =
    when (this) {
        is EntryKind.Totp -> FfiEntryKind.Totp(period = periodSeconds.toULong())
        is EntryKind.Hotp -> FfiEntryKind.Hotp(counter = counter.toULong())
        is EntryKind.Motp ->
            FfiEntryKind.Motp(period = periodSeconds.toULong(), pin = pin?.takeIf { it.isNotEmpty() })
        is EntryKind.Yandex ->
            FfiEntryKind.Yandex(period = periodSeconds.toULong(), pin = pin?.takeIf { it.isNotEmpty() })
        EntryKind.Steam -> FfiEntryKind.Steam
    }

private fun FfiEntryKind.toFacade(pinStored: Boolean): EntryKind =
    when (this) {
        is FfiEntryKind.Totp -> EntryKind.Totp(period.toLong())
        is FfiEntryKind.Hotp -> EntryKind.Hotp(counter.toLong())
        is FfiEntryKind.Motp -> EntryKind.Motp(period.toLong(), pinStored)
        is FfiEntryKind.Yandex -> EntryKind.Yandex(period.toLong(), pinStored)
        FfiEntryKind.Steam -> EntryKind.Steam
    }

/** The two enums name the same three algorithms; the mapping is the whole contract. */
private fun OtpAlgorithm.toFfi(): FfiAlgorithm =
    when (this) {
        OtpAlgorithm.Sha1 -> FfiAlgorithm.SHA1
        OtpAlgorithm.Sha256 -> FfiAlgorithm.SHA256
        OtpAlgorithm.Sha512 -> FfiAlgorithm.SHA512
    }

private fun FfiAlgorithm.toFacade(): OtpAlgorithm =
    when (this) {
        FfiAlgorithm.SHA1 -> OtpAlgorithm.Sha1
        FfiAlgorithm.SHA256 -> OtpAlgorithm.Sha256
        FfiAlgorithm.SHA512 -> OtpAlgorithm.Sha512
    }

/** How a file to import is read. */
enum class ImportFormat {
    /** A JSON export, which may be encrypted. */
    Json,

    /** An HTML page an authenticator exported. */
    Html,

    /** One `otpauth://` link per line. */
    Text,
    ;

    internal fun toFfi(): FfiImportFormat =
        when (this) {
            Json -> FfiImportFormat.JSON
            Html -> FfiImportFormat.HTML
            Text -> FfiImportFormat.TEXT
        }
}

private fun FfiImportFormat.toFacade(): ImportFormat =
    when (this) {
        FfiImportFormat.JSON -> ImportFormat.Json
        FfiImportFormat.HTML -> ImportFormat.Html
        FfiImportFormat.TEXT -> ImportFormat.Text
    }

/**
 * A format the vault can be written out in.
 *
 * The four are the shapes another authenticator reads, which is why they are the same four the
 * import accepts. They are **not equally safe**: only [EncryptedJson] protects anything, and the
 * other three hold every secret as it stands. The screen that writes one has to say so before the
 * file exists.
 */
enum class ExportFormat {
    /** One `otpauth://` link per line. Carries no name, no note and no group. */
    Text,

    /** The JSON export: every field, the groups and their order included. */
    Json,

    /** The JSON export, locked with a password. */
    EncryptedJson,

    /**
     * The HTML export, with a QR code drawn for every entry.
     *
     * The picture is what makes this format usable one account at a time - a phone can scan a
     * row - and it is also why the file is as sensitive as the vault: each code holds that
     * entry's `otpauth://` URI with its secret.
     */
    Html,
    ;

    /**
     * Whether this format is written with a password.
     *
     * Asked before the file is named, so that the password field appears where it belongs rather
     * than the refusal arriving afterwards.
     */
    val needsPassword: Boolean get() = ffiExportFormatNeedsPassword(toFfi())

    /** The file name extension, without the dot. Both JSON forms share one. */
    val extension: String get() = ffiExportFormatExtension(toFfi())

    /**
     * Whether the groups and the order inside them travel with this format.
     *
     * Only the two JSON forms carry them. The others keep the order of the entries and lose which
     * group each one came from, which the screen offering them says rather than leaving the user
     * to find out after importing the file somewhere else.
     */
    val carriesGroups: Boolean get() = ffiExportFormatCarriesGroups(toFfi())

    internal fun toFfi(): FfiExportFormat =
        when (this) {
            Text -> FfiExportFormat.TEXT
            Json -> FfiExportFormat.JSON
            EncryptedJson -> FfiExportFormat.ENCRYPTED_JSON
            Html -> FfiExportFormat.HTML
        }
}

/**
 * One entry an export contributes, as a preview shows it.
 *
 * The kind says how its code will be computed, and for the two kinds that compute with a PIN the
 * PIN itself is **not** part of this: it stays in the library, which keeps the parsed export and
 * uses it when the import is applied. An entry created by an import therefore reports a PIN as
 * set once it is in the vault, and this preview says nothing about it either way.
 */
data class ImportedEntry(
    /** The row's position in the file, which is what a decision refers to. */
    val position: Int,
    val name: String,
    val issuer: String,
    val account: String,
    val algorithm: OtpAlgorithm,
    val digits: Int,
    val kind: EntryKind,
    val favorite: Boolean,
    /** The export carried an icon, which is not part of a vault. */
    val iconDropped: Boolean,
    /** The export named no step and the schema's own default had to be assumed. */
    val periodAssumed: Boolean,
)

/** A row the reader could not use, and why. The reason never quotes a secret. */
data class ImportProblem(val position: Int, val reason: String)

/**
 * A group of entries.
 *
 * A group is an **ordered** collection, not a set: the order is what the user arranged, it is
 * synchronized between devices, and two devices that arranged the same group differently is a
 * conflict the sync screen has to show. The top group holds the entries that belong to no named
 * group, and its name is empty.
 *
 * [order] is the library's own numbering, in which the top group and the first named group are
 * both zero - the top group stands outside the named sequence. Consumers therefore sort by
 * "top first, then by [order]", which is the order [Vault.groups] already returns.
 */
data class Group(
    val id: String,
    val name: String,
    val order: Int,
    val top: Boolean,
    val entryIds: List<String>,
)

/** A group the export carries. */
data class ImportedGroup(val id: String, val name: String)

/** What the export could not carry across, counted. */
data class ImportDropped(val icons: Int, val periodsAssumed: Int, val duplicates: Int)

/**
 * What a vault already holds of one imported entry.
 *
 * [identical] means every field matches and there is nothing to decide. Otherwise [existing]
 * holds the **identifiers** of the entries that use the same secret - identifiers rather than
 * names, because a decision replaces one of them by identifier - and [differences] names the
 * fields that differ, so the user can be asked a question with the answer in front of them.
 */
data class ImportMatch(
    val position: Int,
    val identical: Boolean,
    val existing: List<String>,
    val differences: List<String>,
)

/** What the user decided about one imported entry. */
sealed interface ImportDecision {
    /** Add it as a new entry. */
    data object Add : ImportDecision

    /** Leave it out. */
    data object Skip : ImportDecision

    /** Replace an entry the vault already holds. */
    data class Replace(val entryId: String) : ImportDecision

    /** Add it beside the entry it collides with, keeping both. */
    data object KeepBoth : ImportDecision
}

/** What an applied import did. */
data class ImportApplied(
    val added: Int,
    val replaced: Int,
    val skipped: Int,
    val groupsCreated: List<String>,
    val groupsExisting: List<String>,
)

/**
 * A read export, ready to be previewed and applied.
 *
 * The parsed file lives in the library: what crosses is what a preview shows, and the secret of
 * each entry never does. An entry the user answered nothing about is **added**, never overwritten
 * - losing a note the user wrote is worse than leaving a duplicate they can delete.
 */
class Import internal constructor(private val inner: FfiImport) {
    /** The entries the export contributes. */
    fun entries(): List<ImportedEntry> =
        inner.entries().map {
            ImportedEntry(
                position = it.position.toInt(),
                name = it.name,
                issuer = it.issuer.orEmpty(),
                account = it.account,
                algorithm = it.algorithm.toFacade(),
                digits = it.digits.toCount(),
                // The PIN of an imported entry is not part of the preview: it is applied from the
                // parsed export when the import is, so nothing here reports it.
                kind = it.kind.toFacade(pinStored = false),
                favorite = it.favorite,
                iconDropped = it.iconDropped,
                periodAssumed = it.periodAssumed,
            )
        }

    /** The rows that could not be used. */
    fun problems(): List<ImportProblem> = inner.problems().map { ImportProblem(it.position.toInt(), it.reason) }

    /** The groups the export carries, in the order it lists them. */
    fun groups(): List<ImportedGroup> = inner.groups().map { ImportedGroup(it.id, it.name) }

    /** What the export could not carry across. */
    fun dropped(): ImportDropped =
        inner.dropped().let {
            ImportDropped(
                icons = it.icons.toInt(),
                periodsAssumed = it.periodsAssumed.toInt(),
                duplicates = it.duplicates.toInt(),
            )
        }

    /** What [vault] already holds of each entry, one row per imported entry. */
    fun compare(vault: Vault): List<ImportMatch> =
        inner.compare(vault.handle()).map {
            ImportMatch(
                position = it.position.toInt(),
                identical = it.identical,
                existing = it.existing,
                differences = it.differences,
            )
        }

    /**
     * Applies the answers to [vault], or returns `null` when the vault refused.
     *
     * [decisions] holds an answer per position. A position left out is **added**: an import that
     * silently dropped rows the user never saw a question about would lose entries, and one that
     * silently overwrote would lose notes.
     */
    fun apply(vault: Vault, decisions: Map<Int, ImportDecision>): ImportApplied? =
        try {
            val choices =
                decisions.map { (position, decision) ->
                    FfiImportChoice(
                        position = position.toULong(),
                        decision =
                            when (decision) {
                                ImportDecision.Add -> FfiImportDecision.Add
                                ImportDecision.Skip -> FfiImportDecision.Skip
                                ImportDecision.KeepBoth -> FfiImportDecision.KeepBoth
                                is ImportDecision.Replace -> FfiImportDecision.Replace(entryId = decision.entryId)
                            },
                    )
                }
            inner.apply(vault.handle(), choices).let {
                ImportApplied(
                    added = it.added.toInt(),
                    replaced = it.replaced.toInt(),
                    skipped = it.skipped.toInt(),
                    groupsCreated = it.groupsCreated,
                    groupsExisting = it.groupsExisting,
                )
            }
        } catch (_: Exception) {
            null
        }
}
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

    /**
     * The RFC 6238 code for [secretBase32] at [unixTime], or `null` on invalid input.
     *
     * A stateless convenience, used by the tests as a second opinion on what a vault computes.
     * [algorithm] defaults to SHA-1 because that is the scheme's own default — what an
     * `otpauth://` link means when it names none — and not because the others are less
     * supported.
     */
    fun totp(
        secretBase32: String,
        unixTime: Long,
        periodSeconds: Long = 30,
        digits: Int = 6,
        algorithm: OtpAlgorithm = OtpAlgorithm.Sha1,
    ): String? {
        val ffiDigits = digitsOf(digits) ?: return null
        return try {
            ffiTotpCode(
                secretBase32 = secretBase32,
                unixTime = unixTime.toULong(),
                period = periodSeconds.toULong(),
                algorithm = algorithm.toFfi(),
                digits = ffiDigits,
            )
        } catch (_: Exception) {
            null
        }
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

    /**
     * Whether this device already holds a master key.
     *
     * Read from what is persisted, not by unwrapping it: the two answers differ only when
     * the key can no longer be read, and that case is decided by opening the vault.
     */
    fun hasMasterKey(store: MasterKeyStore): Boolean = store.exists

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
            val code = parsePairingCode(qrText) ?: return null
            PairingJoiner(
                FfiJoiner.fromQr(code.payload, label),
                FfiPlatformKeys.open(label),
                code.record,
            )
        } catch (_: Exception) {
            null
        }

    /**
     * Reads an `otpauth://` link, which is what a scanned entry code carries.
     *
     * Returns `null` when the text is not such a link or its parameters are rejected.
     * The entry's display name is the caller's to choose: the link carries an issuer and
     * an account, not a name.
     */
        fun parseOtpAuth(uri: String): OtpAuthLink? =
            try {
                val parsed = ffiParseOtpAuthUri(uri)
                OtpAuthLink(
                    issuer = parsed.issuer.orEmpty(),
                    account = parsed.account,
                    secretBase32 = parsed.secretBase32,
                    algorithm = parsed.algorithm.toFacade(),
                    digits = parsed.digits.toCount(),
                    kind = parsed.kind.toFacade(pinStored = false),
                )
            } catch (_: Exception) {
                null
            }

    /**
     * Reads a file another authenticator exported, without touching a vault.
     *
     * [format] says how to read it, and [password] is the export's own password for the formats
     * that can be encrypted. Returns `null` when the file cannot be read at all; a file that was
     * read and holds rows that could not be used comes back with those rows as problems, because
     * "we understood most of it" is what the user needs to hear.
     *
     * Nothing secret crosses: the parsed export stays in the library, and what comes back is what
     * a preview needs.
     */
    fun readImport(
        bytes: ByteArray,
        format: ImportFormat,
        password: String?,
        now: Long,
    ): Import? =
        try {
            Import(
                FfiImport.preview(
                    bytes = bytes,
                    format = format.toFfi(),
                    password = password?.takeIf { it.isNotEmpty() },
                    now = now.toULong(),
                ),
            )
        } catch (_: Exception) {
            null
        }

    /** Guesses how to read [bytes], or `null` when the content is not recognisable. */
    fun detectImportFormat(bytes: ByteArray): ImportFormat? =
        try {
            ffiDetectImportFormat(bytes)?.toFacade()
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
            // The text of whichever code is in the picture, exactly as the code carries it. A
            // screen that wants the content of this application's own code unwraps it with
            // [unwrapQrText]. A screen that collects a split payload hands the text to a
            // [QrFrameCollector], which is the only thing that knows how many codes there are.
            ffiQrDecodeText(pixels, width.toUInt(), height.toUInt())
        } catch (_: Exception) {
            null
        }

    /**
     * Takes the envelope off a code this facade drew, or `null` when the text is something else.
     *
     * A code that is one of several is not unwrapped here: it carries part of the payload, and
     * what to do with it is [QrFrameCollector]'s business.
     */
    fun unwrapQrText(text: String): String? =
        try {
            ffiQrUnwrapText(text)
        } catch (_: Exception) {
            null
        }

    /**
     * Draws a payload as the codes to show one after another.
     *
     * A payload that fits in one code comes back as a sequence of one, so the caller does not
     * have to decide which way to draw it: `count` says what the sequence is.
     */
    fun qrFrames(text: String, scale: Int = 4, quietZone: Int = 4): QrFrames? =
        try {
            val frames =
                ffiQrEncodeFrames(
                    text,
                    FfiEcLevel.MEDIUM,
                    QR_MAX_VERSION,
                    MAX_FRAMES,
                    scale.toUShort(),
                    quietZone.toUShort(),
                )
            QrFrames(frames.map { QrImage(it.image.width.toInt(), it.image.height.toInt(), it.image.pixels) })
        } catch (_: Exception) {
            null
        }

    /**
     * A collector for the codes of a split payload, or `null` when one cannot be made.
     *
     * It is built for the profile [qrFrames] draws with, so the two agree on what one code
     * holds without either side passing the number around.
     */
    fun frameCollector(): QrFrameCollector? =
        try {
            QrFrameCollector(FfiFrameCollector(ffiQrFrameCapacity(FfiEcLevel.MEDIUM, QR_MAX_VERSION)))
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

/**
 * The most codes one payload may be split into.
 *
 * Every code has to be held in front of a camera in turn, so this is a limit on the user's
 * patience rather than on the format, which allows far more.
 */
private const val MAX_FRAMES: UInt = 64u

    /**
     * This device's identity fingerprint: 64 hexadecimal characters, or `null` when the
     * platform key store is unavailable.
     *
     * It is the identifier the sync channel reports for this device and the value a payload
     * carries as its sender, so it must be the same one the pairing screen shows.
     */
    fun deviceId(label: String): String? =
        try {
            FfiPlatformKeys.open(label).deviceId()
        } catch (_: Exception) {
            null
        }

    /**
     * What the platform key store says about this device's identity.
     *
     * Read in one call so that the fingerprint and the protection behind it cannot come from
     * two different answers; `null` means there is no usable store, which is a fact the user
     * interface has to report rather than a failure.
     *
     * Opening the identity **creates it when it is missing**, so this is a call that changes
     * the machine the first time it is made. In practice the identity is created when the
     * vault is registered; a device whose vault is here but whose platform key is gone cannot
     * open that vault in the first place, and never reaches this call.
     */
    fun deviceIdentity(label: String): DeviceIdentity? =
        try {
            val keys = FfiPlatformKeys.open(label)
            DeviceIdentity(
                id = keys.deviceId(),
                backend = keys.backend(),
                hardwareBacked = keys.isHardwareBacked(),
            )
        } catch (_: Exception) {
            null
        }

    /**
     * Opens a synchronization session for [vault], claiming [deviceId] on the channel.
     *
     * Returns `null` when the session cannot be created. The vault is **mutated** by a merge
     * and by every decision, so the caller has to serialize it afterward.
     */
    fun syncSession(vault: Vault, deviceId: String): SyncSession? =
        try {
            SyncSession(vault.openSession(), CodeChannel(deviceId))
        } catch (_: Exception) {
            null
        }

    /**
     * Opens the LAN channel for [deviceId], bound to a port the system chooses.
     *
     * Returns `null` when the endpoint cannot be built, which in practice means no free port or
     * a system policy that blocks UDP. Opening one does not touch the network: nothing is
     * announced and nothing is dialled until the caller registers a peer or opens a lobby.
     */
    fun lanChannel(deviceId: String): LanChannel? =
        try {
            LanChannel(FfiLanChannel.open(deviceId), deviceId)
        } catch (_: Exception) {
            null
        }

    /**
     * Opens a synchronization session that runs over [channel] instead of over text.
     *
     * The vault is **mutated** by a merge and by every decision, so the caller has to serialize
     * it afterward, exactly as with [syncSession].
     */
    fun lanSyncSession(vault: Vault, channel: LanChannel): LanSyncSession? =
        try {
            LanSyncSession(vault.openSession(), channel)
        } catch (_: Exception) {
            null
        }
}

/**
 * One device's side of a synchronization over the code channel.
 *
 * The channel is the text one: this device's state becomes a text the user carries to the
 * other device, and what comes back is the other device's text. Nothing here decides
 * anything about the protocol — the core owns which payload goes first, what a merge does and
 * what a disagreement means.
 *
 * # The vault it works on is mutated
 *
 * A merge writes into the vault and a decision writes into it as well, so the caller must
 * persist it ([Vault.serialize]) after either one, or the result is lost when the process
 * ends.
 */
class SyncSession internal constructor(
    private val inner: FfiSyncSession,
    private val channel: CodeChannel,
) {
    /**
     * This device's state as the text to show, or `null` when it cannot be produced.
     *
     * What it carries is the state **at the moment it was generated**, so the caller should
     * generate it again after a merge before handing it over.
     */
    fun myCodeText(): String? =
        try {
            inner.send(channel, FfiRecipient.All, FfiPayloadKind.VAULT)
            channel.takeOutgoing()?.let { payload -> syncCodeText(channel.deviceId, payload) }
        } catch (_: Exception) {
            null
        }

    /**
     * Merges the other device's code.
     *
     * The text is refused when it is not a sync code, and the merge is refused when the code
     * does not belong to this account or was already applied; both come back as
     * [SyncMerge.Refused] rather than as an exception, because both are normal outcomes of a
     * user typing or scanning something.
     */
    fun mergeText(text: String): SyncMerge {
        val code = parseSyncCode(text) ?: return SyncMerge.Refused(SyncRefusal.NotACode)
        channel.accept(code.sender, code.payload)
        return try {
            SyncMerge.Merged(inner.receive(channel).toOutcome())
        } catch (error: FfiException) {
            SyncMerge.Refused(refusalOf(error))
        } catch (_: Exception) {
            SyncMerge.Refused(SyncRefusal.Other)
        }
    }

    /** The disagreements still waiting for a decision. */
    fun conflicts(): List<SyncConflict> =
        try {
            inner.pendingConflicts().map { it.toConflict() }
        } catch (_: Exception) {
            emptyList()
        }

    /**
     * Applies one decision, which is what finally closes a disagreement.
     *
     * [entryId] is the conflict's identifier rather than a position: a position shifts as soon
     * as one conflict is decided. Returns false when the decision was refused, in which case
     * the vault is unchanged and the conflict stays pending.
     */
    fun decide(entryId: String, takeIncoming: Boolean): Boolean =
        try {
            inner.adjudicate(entryId, if (takeIncoming) FfiDecision.TAKE_INCOMING else FfiDecision.KEEP_LOCAL)
            true
        } catch (_: Exception) {
            false
        }
}

/**
 * One device's identity, as the platform key store describes it.
 *
 * [backend] is what actually answered — `"tpm"` or `"software"` — and [hardwareBacked] says
 * whether that answer is hardware protection. Both are reported as they are: the user is
 * entitled to know which of the two protects the account.
 */
data class DeviceIdentity(
    val id: String,
    val backend: String,
    val hardwareBacked: Boolean,
)

/** What one merge produced. */
sealed interface SyncMerge {
    /** The code was merged; [outcome] says what it changed. */
    data class Merged(val outcome: SyncOutcome) : SyncMerge

    /** The code was refused; [reason] says what to tell the user. */
    data class Refused(val reason: SyncRefusal) : SyncMerge
}

/**
 * Why a code was refused.
 *
 * A closed set rather than the library's error text: the user interface has to say something
 * different for each one, and the text of an error is not part of the interface.
 */
enum class SyncRefusal {
    /** The text is not a sync code at all (a pairing code, or something else). */
    NotACode,

    /** Nothing was waiting on the channel: no code has been read yet. */
    NothingToRead,

    /** This payload was already applied. */
    AlreadyApplied,

    /** The code claims a sender the channel does not confirm. */
    SenderMismatch,

    /** The code did not authenticate with this account's master key. */
    AuthenticationFailed,

    /** Anything else, including a failure of the channel itself. */
    Other,
}

/** What one merge changed. */
data class SyncOutcome(
    val added: Int,
    val tombstonesAdded: Int,
    val deletionsApplied: Int,
    val unchanged: Int,
    val madeNoChange: Boolean,
    val needsAdjudication: Boolean,
    val conflicts: List<SyncConflict>,
)

/**
 * One disagreement a human has to settle.
 *
 * [local] is the entry this device holds and [incoming] the one the peer sent; the side that
 * deleted is `null`, which is what [kind] explains. No seed is here: a decision is applied
 * inside Rust, from the copy the conflict already holds.
 */
data class SyncConflict(
    val entryId: String,
    val kind: SyncConflictKind,
    val local: SyncSide?,
    val incoming: SyncSide?,
    val localIsNewer: Boolean,
    /**
     * The two arrangements in disagreement, when the conflict is about one.
     *
     * When this is set, [entryId] is a **group** identifier rather than an entry one: a decision
     * about an ordering is a decision about a whole group, and the identifier is whatever the
     * decision has to name.
     */
    val ordering: OrderingConflict?,
)

/**
 * Two devices arranged one group differently.
 *
 * Both arrangements say which entries are in the group and in which order. The group's name is
 * carried on each side because a rename is one of the ways an arrangement can disagree, and the
 * top group - the one entries belong to when they belong to no named one - has no name on either
 * side, which is what [top] says.
 */
data class OrderingConflict(
    val localName: String,
    val incomingName: String,
    val top: Boolean,
    val local: List<String>,
    val incoming: List<String>,
)

/** The shape of a disagreement, with a fallback for shapes a newer library may report. */
enum class SyncConflictKind {
    /** Both devices changed the entry. */
    EntryMismatch,

    /** The other device deleted it, and this one edited it and still has it. */
    PeerDeleted,

    /** This device deleted it and the other one still has it. */
    LocalDeleted,

    /** The two devices arranged one group differently. */
    OrderingMismatch,

    /** A shape this version does not know; the two sides are shown without a verdict. */
    Unknown,
}

/** One side of a disagreement, as far as it can be shown. */
data class SyncSide(
    val name: String,
    val issuer: String,
    val account: String,
    val note: String,
)

/** The prefix of the text form of a sync code. */
private const val SYNC_CODE_PREFIX = "Q2FAS1"

/** Length of a device identifier in hexadecimal characters (32 bytes). */
private const val DEVICE_ID_HEX_LENGTH = 64

/**
 * The text that carries one sync payload: `Q2FAS1:<sender>:<base64 payload>`.
 *
 * The sender is outside the sealed payload on purpose: the core takes "who sent this" from the
 * channel and compares it with the sender **inside** the payload, which is authenticated. The
 * code's own label can therefore be tampered with, and doing so only makes the merge fail.
 */
private fun syncCodeText(sender: String, payload: ByteArray): String =
    "$SYNC_CODE_PREFIX:$sender:${encodeToText(payload)}"

/** A sync code the user handed over: the claim and the payload. */
private class SyncCode(val sender: String, val payload: ByteArray)

/** Reads a sync code, or returns `null` when the text is not one. */
private fun parseSyncCode(text: String): SyncCode? {
    val parts = text.trim().split(':', limit = 3)
    if (parts.size != 3 || parts[0] != SYNC_CODE_PREFIX) return null

    val sender = parts[1]
    if (sender.length != DEVICE_ID_HEX_LENGTH || !sender.all { it.isHexDigit() }) return null

    val payload = decodeFromText(parts[2]) ?: return null
    return SyncCode(sender.lowercase(), payload)
}

/** Whether this character is one a hexadecimal identifier may contain. */
private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/** The prefix of the text form of a pairing code that names where the host is. */
private const val PAIRING_CODE_PREFIX = "Q2FAP2"

/**
 * The text that carries a pairing payload: `Q2FAP2:<base64 record>:<base32 payload>`.
 *
 * The record is the host's LAN half and is optional. Without one the text is the payload alone,
 * which is what a device pairs with over the code channel; with one, a device on the same
 * network can find the host by it — an announcement carries the same record for the device that
 * sent it — and ask to join without anything being carried by hand.
 */
internal fun pairingCodeText(
    record: ByteArray?,
    payload: String,
): String = if (record == null) payload else "$PAIRING_CODE_PREFIX:${encodeToText(record)}:$payload"

/** A pairing code the user handed over: where the host is, and what to ask it. */
internal class PairingCode(val record: ByteArray?, val payload: String)

/** Reads a pairing code, in either the current form or the payload-only one. */
internal fun parsePairingCode(text: String): PairingCode? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("$PAIRING_CODE_PREFIX:")) return PairingCode(null, trimmed)

    val parts = trimmed.split(':', limit = 3)
    if (parts.size != 3 || parts[2].isEmpty()) return null
    val record = decodeFromText(parts[1]) ?: return null
    return PairingCode(record, parts[2])
}

/**
 * The code channel: an outbound queue and an inbound queue, with **no decisions** of its own.
 *
 * Every protocol rule lives in the core, so this class only moves bytes: what the session
 * queues goes into the text the user shows, and what the user reads off the other device is
 * queued for the session to take.
 *
 * Its capabilities describe the text channel honestly: text has no per-message limit, because
 * the code underneath is always available when a code is too dense to scan, and the user can
 * carry any amount of text by hand.
 */
internal class CodeChannel(val deviceId: String) : FfiTransport {
    /** Payloads the session has queued for display. */
    private val outgoing = ArrayDeque<ByteArray>()

    /** Messages the user has handed over, with the sender each code claims. */
    private val incoming = ArrayDeque<FfiReceivedMessage>()

    /** The peer this channel has seen, which is what `peers()` reports. */
    private var peer: String? = null

    /** Takes the payload the session just queued, if any. */
    fun takeOutgoing(): ByteArray? = outgoing.removeFirstOrNull()

    /** Queues what the user read off the other device. */
    fun accept(sender: String, payload: ByteArray) {
        peer = sender
        incoming.addLast(FfiReceivedMessage(sender = sender, message = payload))
    }

    override fun capabilities(): FfiTransportCapabilities =
        FfiTransportCapabilities(
            roster = FfiRoster.TWO_PARTY,
            maxMessageLen = null,
            maxQrVersion = 20u,
            singleUse = false,
            canCarryPayload = true,
        )

    override fun localDevice(): String = deviceId

    override fun send(to: FfiRecipient, message: ByteArray) {
        outgoing.addLast(message)
    }

    override fun receive(): FfiReceivedMessage? = incoming.removeFirstOrNull()

    override fun peers(): List<String> = listOfNotNull(peer)
}

/** How long [LanChannel.receive] and [LanSyncSession.receive] wait between attempts. */
private const val RECEIVE_POLL_MS = 50L

/**
 * The LAN channel: one endpoint on this network segment, and the peers it may send to.
 *
 * Like [CodeChannel] this class only moves bytes; the difference is the carrier. Which payload
 * goes first, what a merge does and what a disagreement means are all decided in the core.
 *
 * # Where a peer's address comes from
 *
 * An announcement carries a device identifier, an endpoint public key and a port, and **no
 * address**: an endpoint reports its own sockets as the wildcard address, so the only party
 * that knows where a datagram came from is the one that received it. The address therefore
 * arrives with the announcement, in [LanPeer.socket], and a peer without one can be shown but
 * not dialed.
 *
 * # It is opened on demand
 *
 * An open channel holds a UDP port and a runtime thread, and an open lobby announces this
 * device into the network, so neither runs in the background: the interface opens them while a
 * screen that needs them is up and closes them on the way out.
 */
class LanChannel internal constructor(
    private val inner: FfiLanChannel,
    /** This device's identifier, in the hexadecimal form the boundary uses. */
    val deviceId: String,
) {
    /** The channel as a session takes it. The generated trait does not leave this file. */
    internal val transport: FfiTransport get() = inner.transport()

    /**
     * This endpoint's connection record: what another device needs in order to dial it.
     *
     * The record carries the endpoint public key and the port, and no address. Returns `null`
     * when it cannot be produced.
     */
    fun record(): ByteArray? =
        try {
            inner.record()
        } catch (_: Exception) {
            null
        }

    /** Registers [peer], which is what makes [sendTo] possible. */
    fun remember(peer: LanPeer): Boolean {
        val socket = peer.socket ?: return false
        return try {
            inner.rememberPeer(peer.deviceId, peer.record, socket)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Forgets a peer and closes the connection to it. */
    fun forget(peerDeviceId: String): Boolean =
        try {
            inner.forgetPeer(peerDeviceId)
            true
        } catch (_: Exception) {
            false
        }

    /** Sends one message to [peerDeviceId], which must have been registered first. */
    fun sendTo(
        peerDeviceId: String,
        message: ByteArray,
    ): Boolean =
        try {
            transport.send(FfiRecipient.One(peerDeviceId), message)
            true
        } catch (_: Exception) {
            false
        }

    /** Takes one message if one is waiting, without waiting for one. */
    fun receiveNow(): LanMessage? =
        try {
            transport.receive()?.let { LanMessage(it.sender, it.message) }
        } catch (_: Exception) {
            null
        }

    /**
     * Takes one message, waiting up to [timeoutMillis] for one to arrive.
     *
     * The library's own `receive` never blocks, because the core has no clock; how long to wait
     * is decided here, where there is one. Nothing arriving in time returns `null`.
     */
    suspend fun receive(timeoutMillis: Long): LanMessage? {
        val deadline = TimeSource.Monotonic.markNow() + timeoutMillis.milliseconds
        while (true) {
            receiveNow()?.let { return it }
            if (deadline.hasPassedNow()) return null
            delay(RECEIVE_POLL_MS)
        }
    }

    /** Starts announcing this endpoint on the local network, and hearing the others. */
    fun openLobby(): LanLobby? =
        try {
            LanLobby(inner.openLobby())
        } catch (_: Exception) {
            null
        }

    /** Closes the endpoint: every connection is closed and its runtime stops. */
    fun close() {
        runCatching { inner.shutdown() }
    }
}

/**
 * The lobby: which devices are announcing themselves on this network segment.
 *
 * Discovery only. Nothing it reports is authenticated, and no payload travels through it: what
 * it answers is "who is probably here, and where to reach them". Whether a device really is
 * what it claims is answered by the connection handshake and by the payload's own
 * authentication.
 */
class LanLobby internal constructor(private val inner: FfiLanLobby) {
    /**
     * Every peer the lobby knows, whether or not it is still here.
     *
     * A peer that said goodbye or went quiet is reported too, with [LanPeer.here] false:
     * "switched off" and "lost the network" look different to a user.
     */
    fun peers(): List<LanPeer> =
        try {
            inner.peers().map { LanPeer(it.deviceId, it.record, it.socket, it.here) }
        } catch (_: Exception) {
            emptyList()
        }

    /**
     * The peer whose record is [record].
     *
     * This is how a code says which device to dial: the code carries a record, and an
     * announcement carries the same record for the device that sent it. Comparing the two is a
     * lookup, not a decision.
     */
    fun peerWith(record: ByteArray): LanPeer? = peers().firstOrNull { it.record.contentEquals(record) }

    /** Announces this device now instead of waiting for the interval. */
    fun announceNow(): Boolean =
        try {
            inner.announceNow()
            true
        } catch (_: Exception) {
            false
        }

    /** Closes the lobby: one goodbye, then its thread and its socket. */
    fun close() {
        runCatching { inner.shutdown() }
    }
}

/**
 * One device the lobby has heard.
 *
 * [record] is what [LanChannel.remember] takes, and [socket] is the address the announcement
 * arrived from.
 */
class LanPeer(
    val deviceId: String,
    val record: ByteArray,
    val socket: String?,
    val here: Boolean,
)

/** One message taken off a LAN channel. */
class LanMessage(
    /** The device the message claims to come from, hexadecimal. */
    val sender: String,
    /** The message bytes. */
    val message: ByteArray,
)

/**
 * One device's side of a synchronization that runs over the LAN instead of over text.
 *
 * The protocol is the one the code channel carries and the vault is mutated the same way: a
 * merge writes into it and a decision writes into it, so the caller has to persist it
 * ([Vault.serialize]) afterward.
 *
 * # The two sides act by hand
 *
 * Nothing is synchronized in the background: one side offers its state with [send], the other
 * takes it with [receive], and the same pair of actions runs the other way for the reverse
 * direction. Both sides are therefore at the screen at the same time, which is this
 * application's rule for every channel.
 */
class LanSyncSession internal constructor(
    private val inner: FfiSyncSession,
    private val channel: LanChannel,
) {
    /** Offers this device's whole state to [peerDeviceId]. */
    fun send(peerDeviceId: String): Boolean =
        try {
            inner.send(channel.transport, FfiRecipient.One(peerDeviceId), FfiPayloadKind.VAULT)
            true
        } catch (_: Exception) {
            false
        }

    /**
     * Takes the other device's state, waiting up to [timeoutMillis] for it to arrive.
     *
     * A wait that ends with nothing is [SyncMerge.Refused] with [SyncRefusal.NothingToRead],
     * the same answer the code channel gives when no code has been read yet: from the user's
     * side both mean there is nothing to merge.
     */
    suspend fun receive(timeoutMillis: Long): SyncMerge {
        val deadline = TimeSource.Monotonic.markNow() + timeoutMillis.milliseconds
        while (true) {
            val refusal =
                try {
                    return SyncMerge.Merged(inner.receive(channel.transport).toOutcome())
                } catch (error: FfiException) {
                    refusalOf(error)
                } catch (_: Exception) {
                    SyncRefusal.Other
                }
            if (refusal != SyncRefusal.NothingToRead || deadline.hasPassedNow()) {
                return SyncMerge.Refused(refusal)
            }
            delay(RECEIVE_POLL_MS)
        }
    }

    /** The disagreements still waiting for a decision. */
    fun conflicts(): List<SyncConflict> =
        try {
            inner.pendingConflicts().map { it.toConflict() }
        } catch (_: Exception) {
            emptyList()
        }

    /**
     * Applies one decision, which is what finally closes a disagreement.
     *
     * Returns false when the decision was refused, in which case the vault is unchanged and the
     * conflict stays pending.
     */
    fun decide(entryId: String, takeIncoming: Boolean): Boolean =
        try {
            inner.adjudicate(entryId, if (takeIncoming) FfiDecision.TAKE_INCOMING else FfiDecision.KEEP_LOCAL)
            true
        } catch (_: Exception) {
            false
        }
}

/** Converts one outcome for the user interface. */
private fun FfiSyncOutcome.toOutcome(): SyncOutcome =
    SyncOutcome(
        added = added.toInt(),
        tombstonesAdded = tombstonesAdded.toInt(),
        deletionsApplied = deletionsApplied.toInt(),
        unchanged = unchanged.toInt(),
        madeNoChange = madeNoChange,
        needsAdjudication = needsAdjudication,
        conflicts = conflicts.map { it.toConflict() },
    )

/** Converts one conflict for the user interface. */
private fun FfiConflict.toConflict(): SyncConflict =
    SyncConflict(
        entryId = entryId,
        kind =
            when (kind) {
                "entry_mismatch" -> SyncConflictKind.EntryMismatch
                "peer_deleted" -> SyncConflictKind.PeerDeleted
                "local_deleted" -> SyncConflictKind.LocalDeleted
                "ordering_mismatch" -> SyncConflictKind.OrderingMismatch
                else -> SyncConflictKind.Unknown
            },
        local = local?.toSide(),
        incoming = incoming?.toSide(),
        localIsNewer = localIsNewer,
        ordering =
            ordering?.let {
                OrderingConflict(
                    localName = it.localName,
                    incomingName = it.incomingName,
                    top = it.top,
                    local = it.local,
                    incoming = it.incoming,
                )
            },
    )

/** Converts one side of a conflict. */
private fun FfiConflictSide.toSide(): SyncSide =
    SyncSide(
        name = name,
        issuer = issuer.orEmpty(),
        account = account,
        note = note,
    )

/**
 * Maps a failure to the reason the user interface shows.
 *
 * The kinds are the stable identifiers the library documents, and the fallback is required:
 * the core is `#[non_exhaustive]`, so a newer library reports kinds this version has never
 * seen.
 */
private fun refusalOf(error: FfiException): SyncRefusal =
    when (error) {
        is FfiException.Core ->
            when (error.detail.kind) {
                "sync_already_applied" -> SyncRefusal.AlreadyApplied
                "sync_sender_mismatch" -> SyncRefusal.SenderMismatch
                "sync_authentication_failed" -> SyncRefusal.AuthenticationFailed
                "sync_no_session_found" -> SyncRefusal.NothingToRead
                else -> SyncRefusal.Other
            }

        is FfiException.Qr ->
            when (error.kind) {
                "sync_no_session_found" -> SyncRefusal.NothingToRead
                else -> SyncRefusal.Other
            }

        else -> SyncRefusal.Other
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
     * The text to put into the QR code, with this device's LAN record when it has one.
     *
     * With a record, a device on the same network finds this one by it and sends its request
     * over the network instead of the user carrying it; without one the code still pairs over
     * the code channel, which is what happens whenever the network is not shared.
     */
    fun codeText(record: ByteArray?): String = pairingCodeText(record, payload)

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
    /**
     * The host's LAN record, when the code carried one.
     *
     * It names which device to ask: the lobby reports the same record for the device that sent
     * an announcement, so the two are matched rather than the device being guessed.
     */
    val hostRecord: ByteArray?,
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
     * unlocks, or `null` when the reply did not verify. A wrong session, a tampered message,
     * and a host using a different master key all look the same from here.
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
 * What an `otpauth://` link says, as plain values.
 *
 * There is no name here on purpose: the link carries an issuer and an account, and what
 * to call the entry is the user's choice.
 */
data class OtpAuthLink(
    val issuer: String,
    val account: String,
    val secretBase32: String,
    /**
     * The algorithm the link names, which is SHA-1 when it names none — the scheme's own
     * default. It is carried into the entry: importing an SHA-256 link as an SHA-1 entry would
     * produce codes that look right and are always wrong.
     */
    val algorithm: OtpAlgorithm,
    val digits: Int,
    /**
     * The kind the link describes.
     *
     * `otpauth://hotp/…` is a counter, the other spellings are a step, and the two schemes that
     * compute their own shape of code say so themselves — so the link carries the kind rather
     * than a counter that a caller has to interpret.
     */
    val kind: EntryKind,
) {
    /** The time step, for the kinds that have one. */
    val periodSeconds: Long
        get() = when (val kind = kind) {
            is EntryKind.Totp -> kind.periodSeconds
            is EntryKind.Motp -> kind.periodSeconds
            is EntryKind.Yandex -> kind.periodSeconds
            is EntryKind.Hotp, EntryKind.Steam -> DEFAULT_PERIOD_SECONDS
        }

    /** The counter, for a link that describes a counter-based entry. */
    val counter: Long? get() = (kind as? EntryKind.Hotp)?.counter
}

/**
 * The master key store of the wrapped form: a key-encryption key in the platform key store,
 * and wrapped bytes as the only thing the application persists.
 *
 * # What the application keeps, and what it does not
 *
 * [store] hands the master key to the platform and receives bytes back; those bytes are what
 * belongs next to the vault file, and the platform store never hands out the key-encryption
 * key that produced them. [load] gives the bytes back so the platform can unwrap them, which
 * is how the master key is read again.
 *
 * # The label
 *
 * One label names one entry in the platform key store, and the entry is created on first
 * wrap. Every operation of one device has to use the same label: a different one produces a
 * different key-encryption key, and bytes wrapped under the first do not unwrap under it.
 * The label is taken once by the constructor rather than passed to every call, so one store
 * cannot be used against two entries by accident.
 *
 * # [load] returns material the caller owns
 *
 * What it answers with is the master key in the clear. The caller wipes it as soon as the
 * vault holds its own copy, exactly as it does for the bytes [store] was given.
 */
abstract class WrappedMasterKeyStore internal constructor(
    private val inner: FfiWrappedMasterKeyStore,
    private val label: String,
) : MasterKeyStore {
    /**
     * The wrapped bytes as the platform store last produced them, or `null` when this device
     * holds none.
     *
     * Never the master key: the platform's key is what these bytes still need, which is why
     * they may be persisted at all. How they are kept is the subclass's business, and it is
     * read on every access rather than cached, so that a write made elsewhere is not
     * contradicted by a copy taken once.
     */
    protected abstract val wrappedBytes: ByteArray?

    /**
     * Persists the wrapped bytes the platform just produced.
     *
     * [bytes] is never empty: the platform either wraps the key or fails, and a failure is
     * reported as an exception to the caller of [store].
     */
    protected abstract fun persist(bytes: ByteArray)

    /**
     * Removes what keeps the wrapped bytes usable.
     *
     * Called by [delete] before [persist]'s counterpart removes the bytes themselves, so that
     * an interrupted delete leaves nothing anybody could still unwrap.
     */
    protected open fun deletePersisted() = Unit

    final override fun load(): ByteArray? {
        val wrapped = wrappedBytes ?: return null
        return try {
            inner.load(label, wrapped)
        } catch (_: Exception) {
            // Nothing the platform can unwrap: reading is what fails, and only reading. No
            // exception escapes, because "this device can no longer open its vault" is a
            // state the user interface reports rather than an error to crash on.
            null
        }
    }

    final override fun store(key: ByteArray) {
        require(key.size == MASTER_KEY_BYTES) {
            "the master key is $MASTER_KEY_BYTES bytes, got ${key.size}"
        }
        persist(inner.store(label, key))
    }

    final override fun delete() {
        // The key-encryption key goes first: an interrupted delete then leaves bytes that
        // nothing can unwrap, which is the state a device reports as never registered. The
        // other order would leave a working key behind an application that believes it
        // removed the master key.
        runCatching { inner.delete(label) }
        runCatching { deletePersisted() }
    }

    /**
     * Whether the wrapped bytes are still there.
     *
     * A cheap question, deliberately: what it answers is "this device has been registered",
     * which is what the user interface branches on before it tries to open a vault. Whether
     * they still unwrap is answered by [load], which costs a platform key operation.
     */
    final override val exists: Boolean get() = wrappedBytes != null

    final override val isHardwareBacked: Boolean get() = inner.isHardwareBacked()

    final override val backend: String get() = inner.backend()

    private companion object {
        /** Passthrough of the library's length, so the two sides cannot disagree about it. */
        val MASTER_KEY_BYTES: Int
            get() = Qualia.MASTER_KEY_BYTES
    }
}

/**
 * Opens the platform store of the wrapped form under [label], whatever this machine holds.
 *
 * The result is `null` when the native library cannot open a store at all — a machine with
 * nothing that can hold a key-encryption key — which is a state the user interface reports
 * rather than an error to crash on. Every other failure arrives from the operations, where
 * there is a caller that can say what failed.
 *
 * Opening creates nothing: the key-encryption key of a label is made when the first master
 * key is wrapped under it, and the same label always names the same one. A device therefore
 * uses one label for its master key and passes it here once.
 *
 * The result is the generated object rather than a [MasterKeyStore], and it is handed to a
 * subclass of one: the type does not leave this file, so the binding layer stays behind the
 * facade the way every other generated type does.
 */
internal fun openWrappedMasterKeyStore(label: String): FfiWrappedMasterKeyStore? =
    // Aliased, like every generated name this file uses, so that the call can never resolve
    // to a wrapper around itself.
    try {
        FfiWrappedMasterKeyStore.open()
    } catch (_: Exception) {
        null
    }

/**
 * Adapts the facade's store interface to the one the binding layer expects.
 *
 * It forwards and decides nothing, which is the whole contract of that interface.
 */
private class StoreBridge(private val inner: MasterKeyStore) : FfiSecureStore {
    override fun loadMasterKey(): ByteArray? = inner.load()

    override fun storeMasterKey(key: ByteArray) = inner.store(key)

    override fun deleteMasterKey() = inner.delete()

    override fun isHardwareBacked(): Boolean = inner.isHardwareBacked
}

/**
 * Adapts the platform key store to the callback interface the binding layer expects.
 *
 * The generated object is already bound to one label, so the label the callback carries
 * is the same one and is not used again here. It forwards and decides nothing.
 */
private class PlatformKeysBridge(private val inner: FfiPlatformKeys) : FfiDeviceKeys {
    override fun getOrCreate(label: String): ByteArray = inner.devicePublicKey()

    override fun sign(label: String, message: ByteArray): ByteArray = inner.sign(message)
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

/**
 * A code that may need more than one image.
 *
 * A payload that fits in one code comes back as a sequence of one, so a screen never has to
 * decide which way to draw it: [count] says what the sequence is.
 */
class QrFrames internal constructor(private val images: List<QrImage>) {
    /** How many codes the payload was split into. */
    val count: Int get() = images.size

    /** The image of the code at [index], counting from zero. */
    fun image(index: Int): QrImage = images[index]
}

/** Where one code sits in the sequence it belongs to. */
data class FrameCount(val index: Int, val count: Int)

/** What one read code did to a [QrFrameCollector]. */
sealed interface FrameStep {
    /** The code's number in its sequence, counting from one. */
    val index: Int

    /** How many codes the sequence has. */
    val count: Int

    /** The code was taken, and the set is not complete yet. */
    class Taken(override val index: Int, override val count: Int) : FrameStep

    /** The code completed the set. */
    class Completed(
        override val index: Int,
        override val count: Int,
        val payload: ByteArray,
    ) : FrameStep

    /** The code is not one of this application's frames. */
    data object Rejected : FrameStep {
        override val index: Int = 0
        override val count: Int = 0
    }
}

/**
 * Collects the codes of a payload that was split, in any order and with repeats.
 *
 * The caller reads one code at a time and hands each text to [push]. Codes may arrive twice,
 * out of order, or both, which is what collecting them by camera looks like; [received] and
 * [missing] are what a screen shows while it waits.
 */
class QrFrameCollector internal constructor(private val inner: FfiFrameCollector) {
    /** How many codes have been accepted so far. */
    val received: Int get() = inner.received().toInt()

    /** How many codes the set has, once one of them has said so. */
    val expected: Int? get() = inner.expected()?.toInt()

    /** The numbers of the codes still missing, once the count is known. */
    val missing: List<Int> get() = inner.missing().map { it.toInt() }

    /** Takes one read code, saying what it did to the set. */
    fun push(text: String): FrameStep =
        try {
            val step = inner.push(text)
            val index = step.index.toInt()
            val count = step.count.toInt()
            val payload = step.payload
            if (payload == null) {
                FrameStep.Taken(index, count)
            } else {
                FrameStep.Completed(index, count, payload)
            }
        } catch (_: Exception) {
            FrameStep.Rejected
        }
}
