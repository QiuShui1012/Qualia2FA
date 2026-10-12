package dev.qualia.qualia2fa

/**
 * Where the user's preferences are kept.
 *
 * Separate from [VaultStorage] on purpose: losing these costs a language choice, while
 * losing that file costs every entry. Keeping them apart also means a settings file that
 * cannot be written never stands between the user and the vault.
 */
interface SettingsStore {
    /** The language the user chose, or [AppLanguage.System] when nothing was chosen yet. */
    fun language(): AppLanguage

    /** Remembers the language the user chose. */
    fun setLanguage(language: AppLanguage)
}
