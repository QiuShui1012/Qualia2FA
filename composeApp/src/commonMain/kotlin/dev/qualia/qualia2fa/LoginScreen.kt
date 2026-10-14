package dev.qualia.qualia2fa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.action_create_vault
import dev.qualia.qualia2fa.resources.action_join_existing_account
import dev.qualia.qualia2fa.resources.action_unlock
import dev.qualia.qualia2fa.resources.first_run_intro
import dev.qualia.qualia2fa.resources.first_run_join_explainer
import dev.qualia.qualia2fa.resources.first_run_no_hardware_warning
import dev.qualia.qualia2fa.resources.login_key_unreadable
import dev.qualia.qualia2fa.resources.login_locked_explainer
import dev.qualia.qualia2fa.resources.login_subtitle
import dev.qualia.qualia2fa.resources.login_vault_missing
import dev.qualia.qualia2fa.resources.status_locked
import org.jetbrains.compose.resources.stringResource

/**
 * Which face the login screen shows.
 *
 * The design describes the first-run case. The other three exist because a device can already
 * hold a master key, and then **creating a vault is not a safe action to offer**: it would
 * replace that key and strand every entry the account holds. Which actions are safe is the
 * whole reason these are separate states rather than one screen with conditional buttons.
 */
enum class LoginMode {
    /** Nothing on this device yet: create a vault, or log in to an existing account. */
    FirstRun,

    /** A vault exists and is locked: the only action is unlocking it. */
    Locked,

    /** A master key exists, but the vault cannot be opened with it right now. */
    Unreadable,

    /** A master key exists and the vault file is gone. */
    VaultMissing,
}

/**
 * The first screen with anything to act on: the mark, the name, and what this device can do.
 *
 * The arrangement follows the design: in a wide landscape window the actions sit beside the
 * mark and the name, otherwise they stack under them, and a window too short drops the name
 * and then the mark. [loginLayout] holds those rules.
 *
 * # The language selector is an extension
 *
 * The design does not put one here, but this is the last screen before the vault is open: a
 * user who cannot read the interface has no way to reach the settings that would change it,
 * and reading the interface is exactly what they cannot do yet.
 */
@Composable
fun LoginScreen(
    mode: LoginMode,
    reason: Status?,
    hardwareBacked: Boolean,
    message: String?,
    language: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit,
    onCreateVault: () -> Unit,
    onJoin: () -> Unit,
    onUnlock: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val layout = loginLayout(maxWidth, maxHeight)

        if (layout.actionsBesideBrand) {
            Row(
                modifier = Modifier.fillMaxSize().padding(Space.xxl),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.xxl),
            ) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Brand(showTitle = layout.showTitle)
                }
                Column(Modifier.weight(1f)) {
                    Actions(mode, reason, hardwareBacked, message, language, onSelectLanguage, onCreateVault, onJoin, onUnlock)
                }
            }
        } else {
            // Centered inside a box that scrolls: centered when the window has room, and
            // reachable in full when it does not, which a centered column on its own is not
            // — a scrollable column is only as tall as its content.
            Box(
                modifier = Modifier.fillMaxSize().padding(Space.xl),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space.xl),
                ) {
                    Brand(showTitle = layout.showTitle)
                    Actions(mode, reason, hardwareBacked, message, language, onSelectLanguage, onCreateVault, onJoin, onUnlock)
                }
            }
        }
    }
}

/** The subtitle, the buttons, the explanation and the way to change the language. */
@Composable
private fun Actions(
    mode: LoginMode,
    reason: Status?,
    hardwareBacked: Boolean,
    message: String?,
    language: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit,
    onCreateVault: () -> Unit,
    onJoin: () -> Unit,
    onUnlock: () -> Unit,
) {
    val warningColor = MaterialTheme.colorScheme.error

    Column(
        modifier = Modifier.widthIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        when (mode) {
            LoginMode.FirstRun -> {
                Text(stringResource(Res.string.login_subtitle), style = MaterialTheme.typography.titleMedium)
                Button(onClick = onCreateVault, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.action_create_vault))
                }
                OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.action_join_existing_account))
                }
                Hint(stringResource(Res.string.first_run_intro))
                if (!hardwareBacked) {
                    Text(
                        stringResource(Res.string.first_run_no_hardware_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = warningColor,
                    )
                }
                Hint(stringResource(Res.string.first_run_join_explainer))
            }

            LoginMode.Locked -> {
                Text(
                    reason?.text() ?: stringResource(Res.string.status_locked),
                    style = MaterialTheme.typography.titleMedium,
                )
                Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.action_unlock))
                }
                Hint(stringResource(Res.string.login_locked_explainer))
            }

            LoginMode.Unreadable -> {
                Text(stringResource(Res.string.status_locked), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(Res.string.login_key_unreadable),
                    style = MaterialTheme.typography.bodySmall,
                    color = warningColor,
                )
                Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.action_unlock))
                }
                OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.action_join_existing_account))
                }
            }

            LoginMode.VaultMissing -> {
                Text(stringResource(Res.string.status_locked), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(Res.string.login_vault_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = warningColor,
                )
                // Logging in from another device of the same account brings back both the
                // master key and, through a sync, the entries. Creating a vault would not:
                // it would draw a new key.
                Button(onClick = onJoin, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.action_join_existing_account))
                }
            }
        }

        MessageLine(message)
        LanguageSelector(language = language, onSelect = onSelectLanguage)
    }
}
