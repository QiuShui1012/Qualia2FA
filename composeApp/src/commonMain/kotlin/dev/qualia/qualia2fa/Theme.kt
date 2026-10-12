package dev.qualia.qualia2fa

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The application's visual language.
 *
 * # Where the colours come from
 *
 * They are the mark's own two tones: the plate `#EAF2F4` and the line work `#091012`. The
 * plate is the background, white is for the surfaces that sit on it, and the ink carries the
 * text and the primary action. There is deliberately **no third colour** — the countdown
 * ring, the selected navigation item and every button are ink — because the mark is a
 * two-tone drawing and adding a brand colour would be inventing one. The only other colour
 * is the deep red for errors, which is a signal rather than a style.
 *
 * # Shapes
 *
 * Rounded, and generously: the mark's plate is a rounded square whose corner radius is about
 * a sixth of its side, and the surfaces here keep that proportion rather than using the
 * near-square corners of the Material defaults.
 */
@Composable
fun QualiaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = QualiaColors,
        shapes = QualiaShapes,
        content = content,
    )
}

/** The plate of the mark: the background everything sits on. */
private val Plate = Color(0xFFEAF2F4)

/** The line work of the mark: text, icons and the primary action. */
private val Ink = Color(0xFF091012)

/** A recessed plate, for a selected row or a secondary surface. */
private val Recessed = Color(0xFFD8E5E9)

/** The muted ink of secondary text. */
private val MutedInk = Color(0xFF44545A)

/** A deep red for errors: dark enough to read as "stop" without introducing a second hue. */
private val Alert = Color(0xFF8C1D18)

/** The colour scheme, built from the mark. */
private val QualiaColors =
    lightColorScheme(
        primary = Ink,
        onPrimary = Color.White,
        primaryContainer = Recessed,
        onPrimaryContainer = Ink,
        secondary = MutedInk,
        onSecondary = Color.White,
        secondaryContainer = Recessed,
        onSecondaryContainer = Ink,
        tertiary = Ink,
        onTertiary = Color.White,
        background = Plate,
        onBackground = Ink,
        surface = Color.White,
        onSurface = Ink,
        surfaceVariant = Recessed,
        onSurfaceVariant = MutedInk,
        surfaceContainerHighest = Recessed,
        outline = Color(0xFF9FB2B8),
        outlineVariant = Color(0xFFC7D6DA),
        error = Alert,
        onError = Color.White,
        errorContainer = Color(0xFFF7DAD7),
        onErrorContainer = Color(0xFF3B0907),
        scrim = Ink,
    )

/** Rounded like the mark: a sixth of the side rather than a near-square corner. */
private val QualiaShapes =
    Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

/**
 * The spacing scale.
 *
 * One scale for the whole interface, so that "a bit of space" is a decision made once. The
 * steps are close enough together to compose (`M` around `S`, `XL` between sections) and far
 * enough apart to be told apart.
 */
object Space {
    /** 4 dp: between a label and its value. */
    val xs = 4.dp

    /** 8 dp: between an icon and its text, between rows of one control. */
    val s = 8.dp

    /** 12 dp: inside a card, between the fields of a form. */
    val m = 12.dp

    /** 16 dp: the padding of a screen, between cards. */
    val l = 16.dp

    /** 24 dp: between sections. */
    val xl = 24.dp

    /** 32 dp: around a screen's content when there is room. */
    val xxl = 32.dp
}

/** Sizes used by more than one screen. */
object Sizes {
    /** The mark on a full screen (loading, login). */
    val logoLarge = 96.dp

    /** The mark beside a title. */
    val logoInline = 32.dp

    /** An icon in a navigation item. */
    val navIcon = 24.dp

    /** An icon inside a header button. */
    val actionIcon = 22.dp

    /** The countdown ring of one entry. */
    val ring = 40.dp
}
