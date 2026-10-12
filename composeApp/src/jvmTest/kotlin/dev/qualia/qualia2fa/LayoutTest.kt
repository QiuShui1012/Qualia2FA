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
 */
class LayoutTest {
    /** A window with room for everything. */
    private val roomy = 1280.dp to 800.dp

    /** A window too narrow for anything side by side. */
    private val narrow = 400.dp to 800.dp

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
    fun aNarrowWindowStacksInsteadOfDropping() {
        val layout = loadingLayout(narrow.first, narrow.second)
        assertFalse(layout.ringBesideBrand, "a narrow window stacks the ring under the title")
        assertTrue(layout.showTitle, "a narrow window still has room for the title")
        assertTrue(layout.showRing)

        val login = loginLayout(narrow.first, narrow.second)
        assertFalse(login.actionsBesideBrand)
        assertTrue(login.showTitle)
        assertTrue(login.showLogo)
    }

    /** Portrait stacks even when the window is large: there is no width to put a column beside. */
    @Test
    fun aTallWindowStacksTheLoginActions() {
        val login = loginLayout(700.dp, 1200.dp)
        assertFalse(login.actionsBesideBrand, "portrait has no room for a second column")
        assertTrue(login.showTitle)
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

    /** The thresholds themselves, so that a change to them is a deliberate change. */
    @Test
    fun theThresholdsAreWhereTheyAreDocumented() {
        assertTrue(CompactWidth > 0.dp)
        assertTrue(ShortHeight > MinimalHeight, "a title goes before decoration does")
        assertEquals(true, isLandscape(CompactWidth + 1.dp, ShortHeight))
        assertEquals(false, isLandscape(ShortHeight, CompactWidth + 1.dp))
    }
}
