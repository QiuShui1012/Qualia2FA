package dev.qualia.qualia2fa

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The layout rules of the design, as values rather than as `if` statements buried in
 * composables.
 *
 * The design describes each screen by what it *drops* as the window shrinks, in order. That
 * order is the specification: a title goes before a control, decoration goes before either.
 * Written as a function of the window size, it can be reasoned about and tested without a
 * window, and the composables only have to arrange what they are told to show.
 *
 * The design's rules are of two kinds, and keeping them apart is what makes them predictable.
 * "When the width is not enough" is **not** a threshold: it is the window having stopped being
 * wider than it is tall — the same fact [isLandscape] names — which is why every side-by-side
 * arrangement in the design gives way at the same moment, whatever the window's absolute size.
 * The heights it does leave open, "when the height is not enough", said twice with a different
 * consequence on each screen, are chosen here, once, and named.
 */

/**
 * Below this height a title is dropped: a title next to a control is what stops fitting
 * first, and the control is the part the user came for.
 */
val ShortHeight = 360.dp

/**
 * Below this height decoration is dropped as well — the mark on the login screen, the ring on
 * the loading screen — leaving only what the user has to act on.
 */
val MinimalHeight = 240.dp

/**
 * From this width a form puts two of its short fields on one row.
 *
 * This one is a width and not a shape, because what has to fit is a field and its own label
 * side by side, not one column beside another: under it a half-width field is narrower than
 * the label it carries, and a label that does not fit is ellipsized away.
 */
val FormTwoColumnWidth = 420.dp

/** Whether a form of this width has room for two fields on a row. */
fun formHasTwoColumns(width: Dp): Boolean = width >= FormTwoColumnWidth

/**
 * The narrowest an entry card may be.
 *
 * A card holds a name with its service beside it, a code eight digits wide in the code style,
 * and one row of actions with the countdown ring. Under this width one of those stops fitting
 * — the code is the widest and the least negotiable, since a code that wraps is unreadable.
 * The list lays out as many cards of at least this width as fit, so the number of columns
 * follows the window rather than a fixed count.
 */
val MinEntryCardWidth = 280.dp

/** Whether a window of this size is wider than it is tall. */
fun isLandscape(width: Dp, height: Dp): Boolean = width > height

/** What the loading screen shows, and how it arranges it. */
data class LoadingLayout(
    /**
     * Whether the ring sits beside the mark and the title rather than below them.
     *
     * Beside is the arrangement for a window wider than it is tall; once it is not, the row of
     * a mark, a title and a ring is the first thing to look cramped, so it stacks.
     */
    val ringBesideBrand: Boolean,
    /** Whether the title is shown. */
    val showTitle: Boolean,
    /** Whether the ring is shown. */
    val showRing: Boolean,
)

/**
 * The loading screen at a given window size.
 *
 * The mark itself is **always** shown: it is what the screen is for, and it is small enough
 * to survive any window a user can still read.
 */
fun loadingLayout(width: Dp, height: Dp): LoadingLayout =
    LoadingLayout(
        ringBesideBrand = isLandscape(width, height),
        showTitle = height >= ShortHeight,
        showRing = height >= MinimalHeight,
    )

/** What the login screen shows, and how it arranges it. */
data class LoginLayout(
    /**
     * Whether the subtitle and the buttons sit beside the mark and the title rather than
     * below them.
     *
     * The design asks for this in landscape only: in portrait there is no width to spare,
     * and stacking is what the narrow case does anyway.
     */
    val actionsBesideBrand: Boolean,
    /** Whether the title is shown. */
    val showTitle: Boolean,
    /** Whether the mark is shown. */
    val showLogo: Boolean,
)

/** The login screen at a given window size. */
fun loginLayout(width: Dp, height: Dp): LoginLayout =
    LoginLayout(
        actionsBesideBrand = isLandscape(width, height),
        showTitle = height >= ShortHeight,
        showLogo = height >= MinimalHeight,
    )

/** Where the navigation of the main screens sits. */
enum class NavigationPlacement {
    /** A rail down the left side: the window is wider than it is tall. */
    Side,

    /** A bar across the bottom: the window is taller than it is wide. */
    Bottom,
}

/**
 * Where the navigation sits at a given window size.
 *
 * A square window counts as portrait: the rail needs width to be worth its space, and the
 * bottom bar is the arrangement that always fits.
 */
fun navigationPlacement(width: Dp, height: Dp): NavigationPlacement =
    if (isLandscape(width, height)) NavigationPlacement.Side else NavigationPlacement.Bottom
