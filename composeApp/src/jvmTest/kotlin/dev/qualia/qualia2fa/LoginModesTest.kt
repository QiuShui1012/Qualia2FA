package dev.qualia.qualia2fa

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which login-screen states offer the reset.
 *
 * The reset is the only destructive action reachable without an open vault, and it is reachable
 * in exactly the two states where this device holds something it can no longer use. The other
 * two must not offer it, and the cost of getting that wrong is not symmetric: a destructive
 * button on the screen a new user sees is a vault deleted by mistake, while withholding it on
 * the two stuck states leaves a device that cannot be used at all — which is the state the
 * settings screen, the only other way to it, cannot be reached from.
 */
class LoginModesTest {
    @Test
    fun theResetIsOfferedExactlyWhereTheDeviceIsStuck() {
        assertEquals(
            listOf(LoginMode.Unreadable, LoginMode.VaultMissing),
            LoginMode.entries.filter { it.offersReset() },
            "a master key that will not open the vault, or a vault whose key is gone, and nothing else",
        )
    }

    /**
     * The two states a device can still be used from, named one by one.
     *
     * `FirstRun` has nothing to remove, and `Locked` is a vault that opens — offering the reset
     * on either would put the destructive action where the user has a way forward that keeps
     * their entries.
     */
    @Test
    fun theStatesWithAWayForwardDoNotOfferIt() {
        assertEquals(false, LoginMode.FirstRun.offersReset(), "a first run has nothing to reset")
        assertEquals(false, LoginMode.Locked.offersReset(), "a locked vault is opened, not destroyed")
    }
}
