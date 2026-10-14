package dev.qualia.qualia2fa

import androidx.compose.runtime.Composable

/**
 * A hold on multicast reception, taken while a lobby is listening.
 *
 * # Why it exists
 *
 * A WiFi chipset drops multicast datagrams before the system sees them unless something has
 * asked for them. On Android that something is `WifiManager.MulticastLock`, and without it the
 * lobby's socket stays silent and the list of devices on the network is empty however many of
 * them are announcing themselves. The desktop platforms hand multicast to every socket that
 * joined the group, so their hold does nothing.
 *
 * # A platform implementation forwards and decides nothing
 *
 * [acquire] asks the platform to deliver multicast datagrams to this application, [release]
 * gives that back. Whether to hold at all, and for how long, is the caller's decision: the
 * screens hold it from the moment a lobby is opened until that lobby is closed.
 */
interface MulticastHold {
    /** Asks the platform to deliver multicast datagrams to this application. */
    fun acquire()

    /** Gives the hold back. Releasing one that is not held is safe. */
    fun release()
}

/**
 * The multicast hold of the platform this is composed on.
 *
 * Remembered so that a screen can hold one for as long as it is up. Creating the object does
 * not take the hold; calling [MulticastHold.acquire] does.
 */
@Composable
expect fun rememberMulticastHold(): MulticastHold

/**
 * What this platform still needs before the local network may be used.
 *
 * # It is asked for before a channel is opened
 *
 * Opening an endpoint without the permission leaves a channel that sends into nothing, and
 * the failure surfaces later as "no devices found", which is the wrong thing to tell the user.
 * The screens therefore ask first and open the channel only on an affirmative answer.
 *
 * # It answers asynchronously
 *
 * A platform that asks nothing answers immediately from [request]; Android shows a system
 * dialog and answers when the user has dealt with it, which is after the call has returned.
 * The answer always arrives on the main thread.
 */
interface LanPermission {
    /**
     * Asks for whatever is still missing, reporting the outcome through [onAnswer].
     *
     * `true` means the application may use the local network. A refused answer is not an
     * error: every screen keeps the route that needs no network.
     */
    fun request(onAnswer: (Boolean) -> Unit)
}

/** The permission state of the platform this is composed on. */
@Composable
expect fun rememberLanPermission(): LanPermission

/**
 * Registers the device [deviceId] with [channel], when this lobby knows where it is.
 *
 * A message carries no address, and neither does a connection record: the only party that
 * knows where a datagram came from is the one that received it. What does carry an address is
 * an announcement, so answering the device that just sent something means looking it up in the
 * lobby by the identifier the message reported. A device that is not in the lobby cannot be
 * reached, and what the caller does about that is the caller's decision.
 *
 * Returns whether the device was found and registered.
 */
internal fun LanLobby.rememberPeer(channel: LanChannel, deviceId: String): Boolean =
    peers().firstOrNull { it.deviceId == deviceId }?.let(channel::remember) ?: false
