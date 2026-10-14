package dev.qualia.qualia2fa

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.action_copy
import dev.qualia.qualia2fa.resources.sync_frames_counter
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

/**
 * A code that needs several images, shown one after another.
 *
 * The counter is not decoration: a user holding a camera at the screen has to know how far
 * along the sequence is and that it starts over, or a code that cycles looks like one that
 * flickers — and the image they missed is the one that matters.
 */
@Composable
fun FramedCode(frames: QrFrames, code: String, onCopied: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var shown by remember(frames) { mutableStateOf(0) }

    LaunchedEffect(frames) {
        while (true) {
            delay(FRAME_MILLIS)
            shown = (shown + 1) % frames.count
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        QrCodeView(frames.image(shown))
        Text(stringResource(Res.string.sync_frames_counter, shown + 1, frames.count))
        Text(code, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        TextButton(onClick = {
            clipboard.setText(AnnotatedString(code))
            onCopied()
        }) {
            Text(stringResource(Res.string.action_copy))
        }
    }
}

/**
 * How long each image of a sequence is shown before the next one.
 *
 * Long enough for a camera to find and read it: the frame caught while the screen is still
 * changing reads as nothing, and the one behind it needs the time that follows. A code replaced
 * before that has happened is a code the reader never sees, and the user is left holding the
 * device at a screen that cycles faster than it can be read.
 */
private const val FRAME_MILLIS = 1200L

/** A step's short instruction. */
@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall)
}

/**
 * A row of mutually exclusive options, drawn as segments of one control.
 *
 * # Why not a row of chips
 *
 * A chip draws its hover over a container that is transparent when it is not selected, so
 * what the pointer highlights is a state layer rather than the control's own surface: the
 * color around the label and the color the rest of the chip takes come from two different
 * places, and they do not match. Here each option is **one** surface with **one** background
 * — the label sits on it, so there is nothing to mismatch.
 *
 * The highlight ramps **linearly** over [CHOICE_FADE_MS]. The shape of that ramp is what makes
 * it look smooth, not its length: an eased curve spends the first frames barely moving and
 * then crosses most of the color in the middle, so at sixty frames a second it arrives in a
 * handful of visible steps. A longer tween only spreads the same steps out, and an eased one
 * is worse the shorter it is; a constant rate gives every frame an equal share of the change,
 * which is the smoothest a fade this small can be.
 */
@Composable
fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.small

    Row(modifier = modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        options.forEach { option ->
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val highlight by animateFloatAsState(
                targetValue = if (hovered) 1f else 0f,
                animationSpec = tween(durationMillis = CHOICE_FADE_MS, easing = LinearEasing),
                label = "choice-highlight",
            )

            val isSelected = option == selected
            val background =
                when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    else ->
                        // One color for the whole segment: the surface, moved towards the
                        // ink by the faded amount.
                        lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primary, highlight * HOVER_TINT)
                }

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .clip(shape)
                        .background(background)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = shape,
                        )
                        .hoverable(interaction)
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            interactionSource = interaction,
                            indication = null,
                            onClick = { onSelect(option) },
                        )
                        .padding(horizontal = Space.m, vertical = Space.s),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** How long an option takes to reach its hover color, at a constant rate. */
private const val CHOICE_FADE_MS = 120

/** How far an unselected option moves towards the ink while the pointer is over it. */
private const val HOVER_TINT = 0.10f

/** A failure or a result, in the color that says which it is. */
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
 * The properties every dialog in this application is opened with.
 *
 * # Why a click outside does not close a dialog
 *
 * A dialog here is a form, and a form is not something to throw away: a click that lands
 * outside the window is not a decision to discard what has been typed, so the ways out are
 * Cancel and Back, which are both there and both deliberate. Compose also dismisses a dialog
 * on a motion event it merely *observes* outside one, whether the user meant it or not.
 * Leaving the property at its default would therefore put the typed input at the mercy of a
 * stray event.
 */
internal val DialogDefaults = DialogProperties(dismissOnClickOutside = false)

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
