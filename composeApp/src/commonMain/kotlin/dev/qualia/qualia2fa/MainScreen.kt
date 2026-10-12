package dev.qualia.qualia2fa

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.icon_2fa
import dev.qualia.qualia2fa.resources.icon_settings
import dev.qualia.qualia2fa.resources.icon_sync
import dev.qualia.qualia2fa.resources.nav_settings
import dev.qualia.qualia2fa.resources.nav_sync
import dev.qualia.qualia2fa.resources.nav_two_factor
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The three screens the main navigation switches between. */
enum class Tab {
    /** The entries and their codes. */
    TwoFactor,

    /** Exchanging codes with another device. */
    Sync,

    /** Preferences and everything about this device. */
    Settings,
}

/** A tab's label and icon. */
private data class TabAppearance(val label: StringResource, val icon: androidx.compose.ui.graphics.painter.Painter, val description: String)

/** The navigation: a rail down the side, or a bar across the bottom. */
@Composable
fun MainScreen(
    tab: Tab,
    onSelectTab: (Tab) -> Unit,
    content: @Composable () -> Unit,
) {
    val items =
        mapOf(
            Tab.TwoFactor to TabAppearance(Res.string.nav_two_factor, painterResource(Res.drawable.icon_2fa), stringResource(Res.string.nav_two_factor)),
            Tab.Sync to TabAppearance(Res.string.nav_sync, painterResource(Res.drawable.icon_sync), stringResource(Res.string.nav_sync)),
            Tab.Settings to TabAppearance(Res.string.nav_settings, painterResource(Res.drawable.icon_settings), stringResource(Res.string.nav_settings)),
        )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        when (navigationPlacement(maxWidth, maxHeight)) {
            NavigationPlacement.Side ->
                Row(modifier = Modifier.fillMaxSize()) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                        Tab.entries.forEach { entry ->
                            val appearance = items.getValue(entry)
                            NavigationRailItem(
                                selected = entry == tab,
                                onClick = { onSelectTab(entry) },
                                icon = {
                                    Icon(
                                        painter = appearance.icon,
                                        contentDescription = appearance.description,
                                        modifier = Modifier.size(Sizes.navIcon),
                                    )
                                },
                                label = { Text(stringResource(appearance.label)) },
                            )
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) { content() }
                }

            NavigationPlacement.Bottom ->
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f)) { content() }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        Tab.entries.forEach { entry ->
                            val appearance = items.getValue(entry)
                            NavigationBarItem(
                                selected = entry == tab,
                                onClick = { onSelectTab(entry) },
                                icon = {
                                    Icon(
                                        painter = appearance.icon,
                                        contentDescription = appearance.description,
                                        modifier = Modifier.size(Sizes.navIcon),
                                    )
                                },
                                label = { Text(stringResource(appearance.label)) },
                            )
                        }
                    }
                }
        }
    }
}

/** The padding a screen's content keeps inside the navigation shell. */
@Composable
fun ScreenPadding(): Modifier = Modifier.padding(Space.l)
