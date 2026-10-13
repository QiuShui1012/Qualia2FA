package dev.qualia.qualia2fa

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

/**
 * The desktop build has no camera support, so it says so.
 *
 * The scanner is only opened where a camera exists, which makes this the answer that keeps a
 * device without one out of a screen that could do nothing: the code is read out of an image
 * instead, through the picker that is already there.
 */
@Composable
actual fun hasCamera(): Boolean = false

/** Never draws a picture: there is no camera on this platform. */
@Composable
actual fun CameraPreview(
    modifier: Modifier,
    onCode: (String) -> Unit,
    onStatus: (CameraStatus) -> Unit,
) {
    LaunchedEffect(Unit) { onStatus(CameraStatus.Failed) }
    Box(modifier)
}
