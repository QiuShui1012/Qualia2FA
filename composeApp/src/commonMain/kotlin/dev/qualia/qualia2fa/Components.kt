package dev.qualia.qualia2fa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.action_copy
import org.jetbrains.compose.resources.stringResource

/**
 * The pieces every screen is built from.
 *
 * They exist so that a screen reads as its own layout rather than as a pile of styling: the
 * spacing, the corner radius, and the way a heading sits above its content are decided here
 * once, and the screens below compose them.
 */

/** The heading of a screen. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = modifier)
}

/** A heading with its actions on the right, which is what a screen's header is. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScreenTitle(title)
        Spacer(Modifier.weight(1f))
        actions()
    }
}

/** An icon button with a label for the accessibility tree. */
@Composable
fun IconAction(
    painter: Painter,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            painter = painter,
            contentDescription = description,
            modifier = Modifier.size(Sizes.actionIcon),
            tint = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * A titled card: the shape of every section below a screen's header.
 *
 * [title] is optional because some sections are one control whose own label is enough.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            content()
        }
    }
}

/** A label above a value, the way a settings row reads. */
@Composable
fun LabeledValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * One code that has to be carried to another device: the drawing when it fits, the text
 * always, and a way to copy it.
 *
 * The text stays underneath the code on purpose. A code too dense to scan — which is what a
 * full vault produces — must still leave the user a way to carry it across, and a code too
 * large to draw at all leaves only the text.
 */
@Composable
fun CodeBlock(code: String, onCopied: () -> Unit) {
    val clipboard = LocalClipboardManager.current

    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        remember(code) { Qualia.qrImage(code) }?.let { image -> QrCodeView(image) }
        Text(
            code,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        TextButton(onClick = {
            clipboard.setText(AnnotatedString(code))
            onCopied()
        }) {
            Text(stringResource(Res.string.action_copy))
        }
    }
}

/** A step's short instruction. */
@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall)
}

/** A failure or a result, in the colour that says which it is. */
@Composable
fun MessageLine(message: String?, isError: Boolean = true) {
    message ?: return
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
    )
}

/**
 * The language selector: the current choice, and a menu with every language.
 *
 * The names in the menu are endonyms (see [AppLanguage]), so a user who cannot read the
 * language in effect can still find their own in the list.
 */
@Composable
fun LanguageSelector(language: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(language.label())
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AppLanguage.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label()) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}
