package dev.qualia.qualia2fa

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.annotation.RequiresApi
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * The master key on Android: wrapped by a key that lives in the platform keystore.
 *
 * # Why the platform key wraps it instead of holding it
 *
 * A key in the Android keystore cannot be read out — that is the whole point of it — and the
 * core needs the master key's bytes, not a handle to them. So the keystore holds an AES key
 * that never leaves it, and that key encrypts the master key; what is on disk is the wrapped
 * form. Reading the file is not enough to open a vault, and the wrapping key is protected by
 * whatever the device's keystore uses.
 *
 * # What is reported
 *
 * [isHardwareBacked] is answered from the keystore's own description of the key, not from an
 * assumption about the device: a key that turns out to be software-backed is reported as one,
 * because the user is being asked to trust it.
 *
 * # Which keystore
 *
 * A key that can go in the StrongBox is asked to, when the device advertises one: it is the
 * more isolated of the two, and it is slower, which does not matter for an operation that
 * happens once per unlock. A device that has the feature but cannot honor the request is
 * answered with the ordinary keystore rather than refused. That is a difference in how well
 * the key is protected, not in whether the vault can be opened.
 */
class AndroidKeystoreMasterKeyStore(
    private val path: Path,
    context: Context,
) : MasterKeyStore {
    private val applicationContext: Context = context.applicationContext

    private val keyStore: KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    override fun load(): ByteArray? {
        val wrapped = if (Files.isRegularFile(path)) Files.readAllBytes(path) else return null
        if (wrapped.size <= HEADER_BYTES) return null
        if (wrapped[0] != FORMAT_VERSION) return null

        val key = existingKey() ?: return null
        return try {
            val iv = GCMParameterSpec(TAG_BITS, wrapped, 1, IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, iv) }
            cipher.doFinal(wrapped, HEADER_BYTES, wrapped.size - HEADER_BYTES)
        } catch (_: Exception) {
            null
        }
    }

    override fun store(key: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        val sealed = cipher.doFinal(key)
        val wrapped = ByteArray(HEADER_BYTES + sealed.size)
        wrapped[0] = FORMAT_VERSION
        cipher.iv.copyInto(wrapped, 1, 0, IV_BYTES)
        sealed.copyInto(wrapped, HEADER_BYTES)

        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.write(temporary, wrapped)
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun delete() {
        Files.deleteIfExists(path)
        Files.deleteIfExists(path.resolveSibling(path.fileName.toString() + ".tmp"))
        try {
            keyStore.deleteEntry(ALIAS)
        } catch (_: Exception) {
            // A keystore that refuses the deletion leaves a key that wraps nothing: every
            // vault it could have opened is gone from this device either way.
        }
    }

    override val isHardwareBacked: Boolean
        get() = protection

    /**
     * What the keystore says about the wrapping key, asked once.
     *
     * The key is generated on the way if it is not there yet: a device whose master key has
     * not been created still has to be able to answer honestly what would protect it, and an
     * answer of "nothing yet" is the one that makes the user refuse to trust the thing they
     * are about to create. Generating it early costs one keystore entry.
     */
    private val protection: Boolean by lazy {
        try {
            hardwareBacked(wrappingKey())
        } catch (_: Exception) {
            false
        }
    }

    /** The wrapping key if it is already in the keystore, or `null` when it is not. */
    private fun existingKey(): SecretKey? =
        try {
            keyStore.getKey(ALIAS, null) as? SecretKey
        } catch (_: Exception) {
            null
        }

    /**
     * The wrapping key, generating it when this device does not have one yet.
     *
     * Generating is what happens on the first run and after anything that removed the
     * keystore's entries; either way the wrapped master key that may still be on disk can no
     * longer be read, and [load] reports that by answering with nothing.
     */
    private fun wrappingKey(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hasStrongBox()) {
            generateStrongBoxOrTee(generator)
        } else {
            generator.init(specification(strongBox = false).build())
            generator.generateKey()
        }
    }

    /** Whether this device advertises a StrongBox, which is where the key would rather live. */
    private fun hasStrongBox(): Boolean =
        try {
            applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        } catch (_: Exception) {
            false
        }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun generateStrongBoxOrTee(generator: KeyGenerator): SecretKey {
        generator.init(specification(strongBox = true).build())
        return try {
            generator.generateKey()
        } catch (_: StrongBoxUnavailableException) {
            generator.init(specification(strongBox = false).build())
            generator.generateKey()
        }
    }

    private fun specification(strongBox: Boolean): KeyGenParameterSpec.Builder =
        KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setRandomizedEncryptionRequired(true)
            .apply {
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }

    /** Whether the keystore says a key is protected by hardware, taken from the key itself. */
    private fun hardwareBacked(key: SecretKey?): Boolean {
        key ?: return false
        return try {
            val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEY_STORE)
            (factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo).isInsideSecureHardware
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        /** The provider that keeps keys out of this process's reach. */
        const val ANDROID_KEY_STORE = "AndroidKeyStore"

        /** The name this application's wrapping key is kept under. */
        const val ALIAS = "qualia2fa-master-key"

        /** AES-GCM, which authenticates the wrapped key as well as hiding it. */
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** Key length of the wrapping key, in bits. */
        const val KEY_BITS = 256

        /** Length of the authentication tag, in bits, which the tag itself is known by. */
        const val TAG_BITS = 128

        /** Length of the initialisation vector, in bytes. */
        const val IV_BYTES = 12

        /** Length of an authentication tag, in bytes. */
        const val TAG_BYTES = 16

        /** The byte a wrapped key starts with: `1`, then the initialisation vector. */
        const val FORMAT_VERSION: Byte = 1

        /** Version byte plus the initialisation vector, before the sealed key itself. */
        const val HEADER_BYTES = 1 + IV_BYTES
    }
}
