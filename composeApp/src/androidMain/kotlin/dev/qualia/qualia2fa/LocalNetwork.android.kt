package dev.qualia.qualia2fa

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * The multicast hold of Android: a `WifiManager.MulticastLock`.
 *
 * The lock is what stops the chipset from filtering multicast datagrams out of the traffic it
 * hands to the system. It is **not** reference counted: the caller decides when the hold is
 * taken and when it is given back, and a second release — which a `DisposableEffect` can
 * produce when a screen is left in the middle of a flow — must not throw.
 *
 * Asking for the lock when the platform has no WiFi service (a device with no WiFi at all)
 * leaves the hold empty rather than failing: a channel that hears nothing is a fact the screen
 * reports through its own list of devices.
 */
@Composable
actual fun rememberMulticastHold(): MulticastHold {
    val context = LocalContext.current
    return remember(context) { AndroidMulticastHold(context.applicationContext) }
}

private class AndroidMulticastHold(private val context: Context) : MulticastHold {
    private var lock: WifiManager.MulticastLock? = null

    override fun acquire() {
        if (lock != null) return
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        lock =
            wifi.createMulticastLock(LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    override fun release() {
        val held = lock ?: return
        lock = null
        runCatching { held.release() }
    }
}

/** The name the platform shows for this hold in its own battery and network diagnostics. */
private const val LOCK_TAG = "qualia2fa-lan"

/**
 * The permissions this version of Android gates the local network behind.
 *
 * Two of them exist only on the versions that introduced them, and asking for a permission the
 * platform does not know is answered with a refusal rather than being ignored, so which ones
 * are asked for is decided by the API level:
 *
 * - `NEARBY_WIFI_DEVICES` from Android 13 (API 33), which is also where the WiFi APIs started
 *   to require it. It is declared `neverForLocation`: an announcement is not used to work out
 *   where the device is.
 * - `ACCESS_LOCAL_NETWORK` from Android 17 (API 37), the permission that gates every socket
 *   to the local network.
 *
 * An already granted permission is not asked for again: on the versions below the two, this
 * list is empty and there is nothing to ask for at all.
 */
@Composable
actual fun rememberLanPermission(): LanPermission {
    val context = LocalContext.current
    val missing = remember(context) { missingLanPermissions(context) }
    val pending = remember { mutableStateOf<((Boolean) -> Unit)?>(null) }

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answers ->
            val answer = pending.value
            pending.value = null
            answer?.invoke(answers.isNotEmpty() && answers.values.all { it })
        }

    return remember(missing, launcher) {
        object : LanPermission {
            override fun request(onAnswer: (Boolean) -> Unit) {
                if (missing.isEmpty()) {
                    onAnswer(true)
                    return
                }
                pending.value = onAnswer
                launcher.launch(missing.toTypedArray())
            }
        }
    }
}

/** The permissions of [LAN_PERMISSIONS] that this device has not granted yet. */
private fun missingLanPermissions(context: Context): List<String> =
    LAN_PERMISSIONS
        .filter { Build.VERSION.SDK_INT >= it.api }
        .filter { ContextCompat.checkSelfPermission(context, it.name) != PackageManager.PERMISSION_GRANTED }
        .map { it.name }

/** One permission the local network needs, and the API level that introduced it. */
private class LanPermissionOf(val api: Int, val name: String)

/**
 * The name `ACCESS_LOCAL_NETWORK` is declared under.
 *
 * Written out rather than read from `Manifest.permission`, which only carries the constants of
 * the SDK this build compiles against and does not have this one yet.
 */
private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/** The API level that introduced `ACCESS_LOCAL_NETWORK`. */
private const val API_LOCAL_NETWORK = 37

/** The API level that introduced `NEARBY_WIFI_DEVICES`. */
private const val API_NEARBY_WIFI_DEVICES = 33

/** Every permission the local network is behind, oldest first. */
private val LAN_PERMISSIONS =
    listOf(
        LanPermissionOf(API_NEARBY_WIFI_DEVICES, Manifest.permission.NEARBY_WIFI_DEVICES),
        LanPermissionOf(API_LOCAL_NETWORK, ACCESS_LOCAL_NETWORK),
    )
