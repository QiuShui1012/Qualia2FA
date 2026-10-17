package dev.qualia.qualia2fa

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The application's visual language.
 *
 * # Where the colors come from
 *
 * They are the mark's own two tones: the plate `#EAF2F4` and the line work `#091012`. The
 * plate is the background, white is for the surfaces that sit on it, and the ink carries the
 * text and the primary action. There is deliberately **no third color** — the countdown
 * ring, the selected navigation item and every button are ink — because the mark is a
 * two-tone drawing and adding a brand color would be inventing one. The only other color
 * is the deep red for errors, which is a signal rather than a style.
 *
 * # The dark scheme is those two tones with their roles swapped
 *
 * It is not a second palette: the ink becomes the background and the plate becomes what is
 * drawn on it, so the two schemes are the same drawing read in both directions. Everything
 * around them is derived the same way it is in the light scheme — a surface lifted off the
 * background (`Raised`, `RaisedMore`), secondary text pulled toward it (`MutedPlate`), and
 * the same red lightened until it reads on a dark surface rather than a second hue.
 *
 * # Which scheme is used
 *
 * The platform's answer, [isSystemInDarkTheme]: the user already has that setting, and a
 * second one inside the application would only be able to disagree with it. The two
 * platforms differ in **when** they read it, and that is the toolkits' doing rather than a
 * choice here: on Android the configuration is delivered to the running application, so a
 * change while it is open is followed immediately; on the desktop the toolkit reads the
 * system theme when the window is created, so a change is picked up at the next start.
 *
 * # Why the theme paints a background
 *
 * The content is drawn on a [Surface] of the scheme's own background rather than on whatever
 * is behind the window, and that is load-bearing in two ways.
 *
 * A `Text` that is not inside a surface takes its color from Material's default content
 * color, which is **black** — invisible in the light scheme only because the plate happens to
 * be nearly white, and unreadable in the dark one. The surface is what makes plain text
 * readable in both.
 *
 * And the window behind the composition is a resource of the **platform's** theme, resolved
 * when the window is created. On Android the activity handles a configuration change itself
 * (recreating it would throw away the vault's in-memory state), so a window that was created
 * in the light theme keeps its light background while the composition switches to the dark
 * one. Painting the background here is what keeps the two from disagreeing.
 *
 * # Shapes
 *
 * Rounded, and generously: the mark's plate is a rounded square whose corner radius is about
 * a sixth of its side, and the surfaces here keep that proportion rather than using the
 * near-square corners of the Material defaults.
 */
@Composable
fun QualiaTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) QualiaDarkColors else QualiaLightColors
    MaterialTheme(
        colorScheme = colors,
        shapes = QualiaShapes,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.background,
            contentColor = colors.onBackground,
            content = content,
        )
    }
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

/** The ink lifted just enough to be told apart from the background it sits on. */
private val Raised = Color(0xFF16232A)

/** The ink lifted further, for a selected row or a secondary surface on a dark background. */
private val RaisedMore = Color(0xFF22333A)

/** The plate pulled toward the ink: secondary text on a dark surface. */
private val MutedPlate = Color(0xFFA6BABF)

/** The same red, lightened until it reads on a dark surface. */
private val AlertOnDark = Color(0xFFFFB4AB)

/** The light scheme, built from the mark. */
internal val QualiaLightColors =
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

/**
 * The dark scheme: the mark's two tones with their roles swapped.
 *
 * `background` is the ink and `onBackground` is the plate, which is the whole idea; the
 * surfaces are that same ink lifted, and the error red is the same hue lightened. It is
 * `internal` rather than private so that the contrast of every pair can be checked by a test
 * instead of by eye on one screen.
 */
internal val QualiaDarkColors =
    darkColorScheme(
        primary = Plate,
        onPrimary = Ink,
        primaryContainer = RaisedMore,
        onPrimaryContainer = Plate,
        secondary = MutedPlate,
        onSecondary = Ink,
        secondaryContainer = RaisedMore,
        onSecondaryContainer = Plate,
        tertiary = Plate,
        onTertiary = Ink,
        background = Ink,
        onBackground = Plate,
        surface = Raised,
        onSurface = Plate,
        surfaceVariant = RaisedMore,
        onSurfaceVariant = MutedPlate,
        surfaceContainerHighest = RaisedMore,
        outline = Color(0xFF46585E),
        outlineVariant = Color(0xFF2A383D),
        error = AlertOnDark,
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
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
