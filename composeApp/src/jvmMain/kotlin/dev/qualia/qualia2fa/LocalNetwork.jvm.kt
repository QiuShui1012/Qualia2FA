package dev.qualia.qualia2fa

import androidx.compose.runtime.Composable

/**
 * The multicast hold of the desktop JVM: nothing to hold.
 *
 * The desktop network stack hands a multicast datagram to every socket that joined the group,
 * so the lobby receives the announcements without asking for anything. The object exists so
 * that the shared code does not have to know which platform it is running on.
 */
@Composable
actual fun rememberMulticastHold(): MulticastHold = NoMulticastHold

/** The hold that does nothing, for a platform that filters nothing. */
private object NoMulticastHold : MulticastHold {
    override fun acquire() = Unit

    override fun release() = Unit
}

/**
 * The desktop JVM asks for nothing: a socket to the local network is part of what an ordinary
 * desktop application may do, and the user grants it by running the application at all.
 */
@Composable
actual fun rememberLanPermission(): LanPermission = NoLanPermission

/** The permission state of a platform that has no such permission. */
private object NoLanPermission : LanPermission {
    override fun request(onAnswer: (Boolean) -> Unit) = onAnswer(true)
}
