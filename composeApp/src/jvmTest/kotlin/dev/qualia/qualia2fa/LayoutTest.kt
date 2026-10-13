package dev.qualia.qualia2fa

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The design's responsive rules.
 *
 * The design says what each screen drops as the window shrinks, and in which order — a title
 * goes before a control, decoration goes before either. That order is the part worth pinning:
 * it is what keeps a small window usable instead of merely different, and it is a rule about
 * the design rather than about any one composable.
 *
 * The side-by-side arrangements are the exception to "as the window shrinks": the design gives
 * way on those when the width stops being enough, which is the window's *shape* rather than
 * its size. Both directions of that are pinned below, because size creeping back into the
 * decision is exactly the change that would look reasonable in a diff.
 */
class LayoutTest {
    /** A window with room for everything. */
    private val roomy = 1280.dp to 800.dp

    /** A window taller than it is wide. */
    private val portrait = 400.dp to 800.dp

    /** A landscape window with very little of either. */
    private val shallow = 400.dp to 300.dp

    /** A window too short for a title. */
    private val short = 1280.dp to 300.dp

    /** A window too short for decoration as well. */
    private val tiny = 400.dp to 200.dp

    @Test
    fun aRoomyWindowShowsEverything() {
        val layout = loadingLayout(roomy.first, roomy.second)
        assertTrue(layout.ringBesideBrand, "a wide window puts the ring beside the mark")
        assertTrue(layout.showTitle)
        assertTrue(layout.showRing)

        val login = loginLayout(roomy.first, roomy.second)
        assertTrue(login.actionsBesideBrand, "a wide landscape window puts the actions beside the brand")
        assertTrue(login.showTitle)
        assertTrue(login.showLogo)
    }

    /** The first thing to go is the side-by-side arrangement, not any of the content. */
    @Test
    fun aPortraitWindowStacksInsteadOfDropping() {
        val layout = loadingLayout(portrait.first, portrait.second)
        assertFalse(layout.ringBesideBrand, "a window taller than it is wide stacks the ring under the title")
        assertTrue(layout.showTitle, "a portrait window still has room for the title")
        assertTrue(layout.showRing)

        val login = loginLayout(portrait.first, portrait.second)
        assertFalse(login.actionsBesideBrand)
        assertTrue(login.showTitle)
        assertTrue(login.showLogo)
    }

    /**
     * The shape of the window decides the side-by-side arrangements, not its size: four hundred
     * by three hundred keeps both of them, seven hundred by twelve hundred keeps neither.
     */
    @Test
    fun theShapeDecidesTheSideBySideArrangementAndNotTheSize() {
        val small = loadingLayout(shallow.first, shallow.second)
        assertTrue(small.ringBesideBrand, "a small landscape window keeps the ring beside the mark")
        assertFalse(small.showTitle, "its height still decides what it drops")
        assertTrue(small.showRing)
        assertTrue(
            loginLayout(shallow.first, shallow.second).actionsBesideBrand,
            "and the login screen keeps its actions beside the brand",
        )

        val large = loadingLayout(700.dp, 1200.dp)
        assertFalse(large.ringBesideBrand, "a large portrait window stacks, however much room it has")
        assertTrue(large.showTitle)
        assertFalse(
            loginLayout(700.dp, 1200.dp).actionsBesideBrand,
            "portrait has no room for a second column",
        )
    }

    /** Height drops the title first, and the loading screen keeps its mark until the end. */
    @Test
    fun aShortWindowDropsTheTitleButKeepsTheMark() {
        val layout = loadingLayout(short.first, short.second)
        assertFalse(layout.showTitle, "a short window drops the title")
        assertTrue(layout.showRing, "the ring is what the screen is for, so it stays for now")

        val login = loginLayout(short.first, short.second)
        assertFalse(login.showTitle)
        assertTrue(login.showLogo)
    }

    /**
     * The loading screen never drops the mark; the login screen drops it last, and then only
     * the control the user came for is left.
     */
    @Test
    fun aTinyWindowKeepsOnlyWhatCanBeActedOn() {
        val layout = loadingLayout(tiny.first, tiny.second)
        assertFalse(layout.showTitle)
        assertFalse(layout.showRing, "with no room at all, the mark is what is left")

        val login = loginLayout(tiny.first, tiny.second)
        assertFalse(login.showTitle)
        assertFalse(login.showLogo, "the buttons are the only thing left to show")
    }

    @Test
    fun navigationSitsOnTheSideInLandscapeAndAtTheBottomOtherwise() {
        assertEquals(NavigationPlacement.Side, navigationPlacement(1280.dp, 800.dp))
        assertEquals(NavigationPlacement.Bottom, navigationPlacement(800.dp, 1280.dp))
        assertEquals(
            NavigationPlacement.Bottom,
            navigationPlacement(800.dp, 800.dp),
            "a square window counts as portrait: a rail needs width to be worth its space",
        )
    }

    /** The heights are the only numbers the design leaves open, so they are pinned here. */
    @Test
    fun theHeightsAreWhereTheyAreDocumented() {
        assertTrue(MinimalHeight > 0.dp)
        assertTrue(ShortHeight > MinimalHeight, "a title goes before decoration does")
    }

    /**
     * A form puts two short fields on a row only when the row is wide enough for both labels.
     *
     * Under that width a half-width field is narrower than the label it carries, and a label
     * that does not fit is ellipsized — which is how "the field's name disappeared" happens.
     */
    @Test
    fun aFormHasTwoColumnsOnlyWhenItsFieldsKeepTheirLabels() {
        assertTrue(formHasTwoColumns(FormTwoColumnWidth))
        assertTrue(formHasTwoColumns(FormTwoColumnWidth + 1.dp))
        assertFalse(formHasTwoColumns(FormTwoColumnWidth - 1.dp))
    }
}
