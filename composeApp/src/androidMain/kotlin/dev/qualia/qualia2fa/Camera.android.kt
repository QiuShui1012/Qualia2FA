package dev.qualia.qualia2fa

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.util.Size
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Whether this device has a camera to read a code with. */
@Composable
actual fun hasCamera(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
}

/**
 * The camera, as CameraX: a preview surface on screen and frames handed to the core's decoder.
 *
 * # What looks at the frames
 *
 * The decoder is the same one an image from the library goes through
 * ([Qualia.qrTextFromImage]), so a code is read by exactly one piece of code whichever way it
 * arrives. It is handed the **luma plane** of each frame, which is already the grayscale
 * image it takes, and it runs on the analysis thread — never on the one drawing, because it
 * is a blocking call into Rust.
 *
 * Only one frame is analysed at a time ([ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST]): a code is
 * read from a still frame, and queueing frames behind a slow decode would only add latency to
 * the frame that matters.
 *
 * # What it does not do
 *
 * It does not ask the user anything about codes, and it does not report what one said beyond
 * handing it over. The permission is the one exception, because only the platform can ask for
 * it, and it is asked for when the scanner opens.
 */
@Composable
actual fun CameraPreview(
    modifier: Modifier,
    onCode: (String) -> Unit,
    onStatus: (CameraStatus) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            granted = allowed
        }

    // Asked for once, the first time the scanner is opened. A refusal is not an error: the
    // scanner says so and keeps the way in that needs no permission.
    LaunchedEffect(Unit) {
        if (!granted) permission.launch(Manifest.permission.CAMERA)
    }

    var binding by remember { mutableStateOf(CameraStatus.Starting) }
    val status =
        when {
            !granted -> CameraStatus.Refused
            activity == null -> CameraStatus.Failed
            else -> binding
        }
    LaunchedEffect(status) { onStatus(status) }

    if (status == CameraStatus.Refused || status == CameraStatus.Failed) return

    val owner = activity as? LifecycleOwner ?: return
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val analyzer = remember { Executors.newSingleThreadExecutor() }
    val deliver = rememberUpdatedState(onCode)

    DisposableEffect(owner) {
        val provider = ProcessCameraProvider.getInstance(context)
        var bound: ProcessCameraProvider? = null
        provider.addListener(
            {
                try {
                    val camera = provider.get()
                    bound = camera
                    val preview =
                        Preview.Builder().build().apply { surfaceProvider = view.surfaceProvider }
                    val analysis =
                        ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setResolutionSelector(analysisResolution())
                            .build()
                            .apply {
                                setAnalyzer(analyzer, CodeAnalyzer { text -> deliver.value(text) })
                            }
                    camera.unbindAll()
                    camera.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    binding = CameraStatus.Running
                } catch (error: Exception) {
                    // A camera that will not start is reported, not worked around: the screen
                    // says so and the way in that needs no camera stays.
                    Log.w(TAG, "the camera would not start", error)
                    binding = CameraStatus.Failed
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            bound?.unbindAll()
            analyzer.shutdown()
        }
    }

    AndroidView(factory = { view }, modifier = modifier)
}

/** The tag this file's diagnostics are written under. */
private const val TAG = "Qualia2FA"

/**
 * The size analysis frames are requested at, in pixels.
 *
 * A code on another screen occupies a small part of the picture, and a dense one — a server's
 * setup page can draw eighty modules across — needs pixels *per module*, not per frame: at
 * 720p such a code arrives with about five pixels across a module, which is at the edge of
 * what the decoder resolves. 1080p doubles that, and decoding two megapixels still costs
 * milliseconds, so the analysis keeps up with [ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST].
 */
private val ANALYSIS_EDGE = Size(1920, 1080)

private fun analysisResolution(): ResolutionSelector =
    ResolutionSelector.Builder()
        .setResolutionStrategy(
            ResolutionStrategy(ANALYSIS_EDGE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
        )
        .build()

/**
 * Hands each frame's luma plane to the core's decoder, and stops at the first code it reads.
 *
 * A frame that was already searched, or one that arrives after a code was found, is dropped
 * rather than searched again: the scanner closes as soon as a code is delivered, and decoding
 * the frames queued behind it would only delay that.
 */
private class CodeAnalyzer(private val onFound: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val found = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        try {
            if (found.get()) return
            val gray = image.luma() ?: return
            val text = Qualia.qrTextFromImage(gray, image.width, image.height) ?: return
            if (found.compareAndSet(false, true)) onFound(text)
        } finally {
            image.close()
        }
    }
}

/**
 * The luma plane of a YUV frame, one byte per pixel, row major.
 *
 * The plane's rows are usually padded to a stride longer than the image is wide and its
 * pixels are usually one byte apart, but neither is guaranteed by the format, so both are
 * read from the plane rather than assumed.
 */
private fun ImageProxy.luma(): ByteArray? {
    val plane = planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val width = width
    val height = height
    val gray = ByteArray(width * height)
    var target = 0
    for (row in 0 until height) {
        var source = row * plane.rowStride
        for (column in 0 until width) {
            gray[target++] = buffer.get(source)
            source += plane.pixelStride
        }
    }
    return gray
}

/** The activity this context belongs to, which is what the camera binds its life to. */
private fun Context.findActivity(): Activity? {
    var context: Context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
