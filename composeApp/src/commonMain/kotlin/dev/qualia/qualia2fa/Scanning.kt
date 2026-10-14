package dev.qualia.qualia2fa

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.min
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.action_back
import dev.qualia.qualia2fa.resources.cd_pick_image
import dev.qualia.qualia2fa.resources.dialog_pick_image
import dev.qualia.qualia2fa.resources.error_camera_failed
import dev.qualia.qualia2fa.resources.error_camera_refused
import dev.qualia.qualia2fa.resources.filter_image_files
import dev.qualia.qualia2fa.resources.icon_image
import dev.qualia.qualia2fa.resources.scan_frame_taken
import dev.qualia.qualia2fa.resources.scan_hint
import dev.qualia.qualia2fa.resources.scan_title
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * A code the application is asking the user for, and what to do with what comes back.
 *
 * The two failures are the same everywhere a code is read — the image could not be read at
 * all, or it was read and held no code. They are passed in rather than resolved here, because
 * the screens word them for what they were looking for.
 */
class ScanRequest(
    val unreadable: String,
    val noCode: String,
    val onText: (String) -> Unit,
    val onMessage: (String?) -> Unit,
    /**
     * Takes a code that is one of several, answering with where it sits in the sequence.
     *
     * A payload that needs several codes is collected by reading one after another, and closing
     * the camera between them would make the user reopen it for every image. Answering keeps
     * the scanner where it is, and the counts it answers with are what the scanner tells the
     * user it just read. The default closes the scanner after one code, which is what a screen
     * that wants a whole code needs.
     */
    val onFrame: (String) -> FrameCount? = { null },
)

/**
 * Asks the user for a code.
 *
 * Which way in is used is the window's decision, not the screen's: a device with a camera
 * gets the scanner, a device without one goes straight to picking an image. A screen only
 * says what it is looking for and what to do with the answer.
 */
class CodeScanner internal constructor(private val open: (ScanRequest) -> Unit) {
    /** Asks for a code; [ScanRequest] carries the two failures and the two callbacks. */
    fun read(request: ScanRequest) = open(request)
}

/**
 * The scanner, provided by the window that can show it.
 *
 * A screen that asks for a code cannot be composed outside that window, and says so loudly
 * rather than doing nothing.
 */
val LocalCodeScanner = staticCompositionLocalOf<CodeScanner> {
    error("The code scanner is provided by the window; a screen cannot read a code without it")
}

/** Asks for a code, reporting the two failures every screen has to tell apart. */
fun CodeScanner.readCode(
    unreadable: String,
    noCode: String,
    onText: (String) -> Unit,
    onMessage: (String?) -> Unit,
    onFrame: (String) -> FrameCount? = { null },
) = read(ScanRequest(unreadable, noCode, onText, onMessage, onFrame))

/** What the camera is doing, so that the scanner can say why there is no picture. */
enum class CameraStatus {
    /** Waiting for the camera to start. */
    Starting,

    /** Frames are arriving. */
    Running,

    /** The user, or the platform, has not given the camera to this application. */
    Refused,

    /** The camera is there but would not start. */
    Failed,
}

/**
 * Whether this device can read a code with a camera.
 *
 * It decides which way into the scanner is offered; it says nothing about whether the
 * permission has been granted, which is asked for once the scanner is opened.
 */
@Composable
expect fun hasCamera(): Boolean

/**
 * The camera's view of the world, and whatever code it finds in it.
 *
 * The platform draws the preview and does the looking; the layout around it and everything
 * said about it belong to the screen. [onStatus] is called as the camera starts, and with one
 * of the two failures when it does not: this composable draws nothing in that case, so the
 * screen can put its own words where the picture would have been.
 */
@Composable
expect fun CameraPreview(
    modifier: Modifier,
    onCode: (String) -> Unit,
    onStatus: (CameraStatus) -> Unit,
)

/**
 * The darkening that turns the camera's picture into a viewfinder.
 *
 * Everything but a square in the middle is dimmed, so that the code is read through the
 * brightest part of the frame and the rest of the picture is no longer competing with it. The
 * square is drawn as a frame as well as left clear: "hold the code here" is then something the
 * user can see rather than infer, and it is the one shape on the screen that says what to do.
 *
 * The window is a share of the shorter side so that it stays square whatever shape the view
 * is, and it is never larger than the view: a window wider than the picture would push the
 * dimmed part off the edges and leave nothing to aim at.
 */
@Composable
private fun Viewfinder() {
    val scrim = MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA)
    val frame = MaterialTheme.colorScheme.surface

    Canvas(modifier = Modifier.fillMaxSize()) {
        val side = min(size.width, size.height) * WINDOW_SHARE
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val right = left + side
        val bottom = top + side

        drawRect(scrim, size = Size(size.width, top))
        drawRect(scrim, topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
        drawRect(scrim, topLeft = Offset(0f, top), size = Size(left, side))
        drawRect(scrim, topLeft = Offset(right, top), size = Size(size.width - right, side))

        drawRect(
            color = frame,
            topLeft = Offset(left, top),
            size = Size(side, side),
            style = Stroke(width = FRAME_WIDTH.toPx()),
        )
    }
}

/** How much of the shorter side of the view the window takes. */
private const val WINDOW_SHARE = 0.3f

/** How much of the picture behind the window is dimmed away. */
private const val SCRIM_ALPHA = 0.55f

/** The width of the window's frame. */
private val FRAME_WIDTH = 3.dp

/**
 * The scanner: the camera, a line saying what to do with the code, and a way in for a code
 * that is not on a screen.
 *
 * The way in is a button with no text on it, in the corner of the view: it is the second way
 * to do the same thing rather than a second thing to do, and a labeled button beside the
 * camera would read as the point of the screen. What it does is written down in the hint.
 *
 * A code that is read closes the scanner and goes to the screen that asked; a code that is
 * not leaves it open, because the next thing the user does is try again, with the camera or
 * with another image.
 */
@Composable
fun ScanScreen(request: ScanRequest, picker: FilePicker, onDismiss: () -> Unit) {
    var status by remember { mutableStateOf(CameraStatus.Starting) }

    // The image path reports a failure through the same channel the caller uses for messages
    // of its own, and the caller's message lands on the screen **under** this one: invisible
    // for exactly as long as the scanner is open, which is when the user is looking for it.
    // The message is therefore kept here as well and shown beside the hint.
    var notice by remember { mutableStateOf<String?>(null) }
    var takenFrame by remember { mutableStateOf<FrameCount?>(null) }

    val dialogTitle = stringResource(Res.string.dialog_pick_image)
    val imageFilesLabel = stringResource(Res.string.filter_image_files)
    val pickLabel = stringResource(Res.string.cd_pick_image)

    val answer: (String) -> Unit = { text ->
        val frame = request.onFrame(text)
        if (frame == null) {
            onDismiss()
            request.onText(text)
        } else {
            takenFrame = frame
        }
    }

    // The scanner is drawn over the screen that asked for it rather than in place of it, so
    // it has to be opaque, and it has to take the touches: what is underneath is still there,
    // and a tap that fell through it would work a control the user cannot see.
    Surface(
        modifier =
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            ScreenHeader(title = stringResource(Res.string.scan_title)) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_back)) }
            }

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(MaterialTheme.shapes.large)
                        .background(Color.Black),
            ) {
                CameraPreview(modifier = Modifier.fillMaxSize(), onCode = answer, onStatus = { status = it })

                Viewfinder()

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(Space.l),
                ) {
                    IconAction(
                        painter = painterResource(Res.drawable.icon_image),
                        description = pickLabel,
                    ) {
                        picker.readCodeFromImage(
                            dialogTitle = dialogTitle,
                            imageFilesLabel = imageFilesLabel,
                            unreadable = request.unreadable,
                            noCode = request.noCode,
                            onText = answer,
                            onMessage = { text ->
                                notice = text
                                request.onMessage(text)
                            },
                        )
                    }
                }
            }

            when (status) {
                CameraStatus.Refused -> MessageLine(stringResource(Res.string.error_camera_refused))
                CameraStatus.Failed -> MessageLine(stringResource(Res.string.error_camera_failed))
                CameraStatus.Starting, CameraStatus.Running -> {
                    val frame = takenFrame
                    val current = notice
                    when {
                        frame != null ->
                            MessageLine(stringResource(Res.string.scan_frame_taken, frame.index, frame.count))
                        current != null -> MessageLine(current)
                        else -> Hint(stringResource(Res.string.scan_hint))
                    }
                }
            }
        }
    }
}
