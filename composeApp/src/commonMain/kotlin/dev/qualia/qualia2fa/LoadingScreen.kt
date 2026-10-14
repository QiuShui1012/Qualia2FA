package dev.qualia.qualia2fa

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.app_name
import dev.qualia.qualia2fa.resources.cd_loading
import dev.qualia.qualia2fa.resources.qualia2fa_logo
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * What the application shows while it works out where to go.
 *
 * It is the mark, the name and a ring, and which of them is shown depends on the window: the
 * ring moves under the mark when there is no width for a row, and the title goes before the
 * ring does. The rules are in [loadingLayout], so this composable only arranges what it is
 * told to show.
 *
 * The mark itself is never dropped: it is what tells the user that the window belongs to this
 * application and not to a launcher, and it fits at any size worth rendering.
 */
@Composable
fun LoadingScreen() {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val layout = loadingLayout(maxWidth, maxHeight)

        if (layout.ringBesideBrand) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.xl),
            ) {
                Brand(showTitle = layout.showTitle)
                if (layout.showRing) LoadingRing()
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.l),
            ) {
                Brand(showTitle = layout.showTitle)
                if (layout.showRing) LoadingRing()
            }
        }
    }
}

/** The mark, with the name under it. */
@Composable
internal fun Brand(showTitle: Boolean, logoSize: androidx.compose.ui.unit.Dp = Sizes.logoLarge) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Image(
            painter = painterResource(Res.drawable.qualia2fa_logo),
            contentDescription = null,
            modifier = Modifier.size(logoSize),
        )
        if (showTitle) {
            Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineMedium)
        }
    }
}

/** The ring: the only thing on the screen a screen reader has to announce. */
@Composable
private fun LoadingRing() {
    val description = stringResource(Res.string.cd_loading)
    CircularProgressIndicator(
        modifier =
            Modifier
                .size(40.dp)
                .semantics { contentDescription = description },
    )
}
