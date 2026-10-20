package app.lofi.cam.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lofi.cam.CameraViewModel
import app.lofi.cam.CaptureMode
import app.lofi.cam.exportScaleOptions
import app.lofi.render.LofiLog
import app.lofi.render.LofiRenderer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val Navy = Color(0xFF0A1070)
private val Panel = Color(0xF2080C3A)
private val Cream = Color(0xFFEFEADB)
private val Rec = Color(0xFFFF2D3D)
private val Cyan = Color(0xFF19E3FF)
private val Mono = FontFamily.Monospace

@Composable
fun LofiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Rec, secondary = Cyan, background = Color.Black, surface = Navy),
        content = content,
    )
}

@Composable
fun CameraScreen(vm: CameraViewModel) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val state by vm.state.collectAsStateWithLifecycle()
    val look = state.looks.getOrNull(state.selected)
    val level = vm.clarityLevels.firstOrNull { it.id == state.clarity }

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var hasAudio by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasAudio = it
        if (!it) vm.showMessage("No microphone permission: videos will have no sound")
    }
    var menuOpen by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (!hasPermission) cameraLauncher.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(hasPermission, state.front) { if (hasPermission) vm.bindCamera(activity) }
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(2500)
            vm.clearMessage()
        }
    }
    // Leaving the app must finalise a running recording (the GL thread stops with the activity).
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) vm.onAppPaused() }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    val videoMode = state.mode == CaptureMode.VIDEO

    Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        // ---- preview (centred; the UI lives in the black bars above and below it) ----
        Box(Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(state.aspect)) {
            if (hasPermission) {
                GlPreview(vm.renderer, Modifier.fillMaxSize())
            } else {
                Text("Camera permission needed", color = Cream, fontFamily = Mono, modifier = Modifier.align(Alignment.Center))
            }
            // transparent gesture layer: swipe left/right = next/previous look, up = looks sheet, down = menu
            Box(
                Modifier.matchParentSize().swipes(
                    onLeft = { vm.stepLook(+1) },
                    onRight = { vm.stepLook(-1) },
                    onUp = { sheetOpen = true },
                    onDown = { menuOpen = true },
                ),
            )
        }
        if (state.screenFlash) Box(Modifier.fillMaxSize().background(Color.White))

        // ---- top row: flash, menu handle, photo/video ----
        Pill(
            if (state.flash) "FLASH ON" else "FLASH OFF",
            Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 8.dp).width(100.dp),
            selected = state.flash,
        ) { vm.toggleFlash() }
        Pill(
            if (videoMode) "VIDEO" else "PHOTO",
            Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 8.dp).width(100.dp),
            selected = videoMode,
        ) {
            if (videoMode) {
                vm.setMode(CaptureMode.PHOTO)
            } else {
                vm.setMode(CaptureMode.VIDEO)
                if (!hasAudio) audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        Column(
            Modifier.align(Alignment.TopCenter).swipes(onDown = { menuOpen = true }, onUp = { menuOpen = false }),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Pill("MENU", Modifier.padding(top = 8.dp), selected = menuOpen) { menuOpen = !menuOpen }
            Handle(Modifier.padding(top = 6.dp))
        }
        if (state.recording) RecTimer(state.recordingStartMs, Modifier.align(Alignment.TopCenter).padding(top = 64.dp))

        // ---- zoom bar: sits just above the SWITCH button; touch anywhere on it to zoom, it stays where you let go ----
        ZoomBar(
            zoom = state.zoom,
            ratio = state.zoomRatio,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 108.dp),
            onZoom = vm::setZoom,
        )

        // ---- bottom: look pill, last-capture circle, shutter, switch camera; swipe up for the sheet ----
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().swipes(onUp = { sheetOpen = true }),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Handle()
            Pill(look?.name?.uppercase() ?: "LOOKS", Modifier.padding(top = 8.dp).width(170.dp)) { sheetOpen = true }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LastCapture(state.lastThumb) {
                    state.lastUri?.let { openMedia(context, it, state.lastIsVideo) { vm.showMessage("No gallery app found") } }
                }
                Shutter(videoMode, state.recording, state.busy, hasPermission) {
                    when {
                        !videoMode -> vm.capture()
                        state.recording -> vm.stopRecording()
                        else -> vm.startRecording(hasAudio)
                    }
                }
                Pill("SWITCH", Modifier.width(76.dp)) { vm.flipCamera() }
            }
        }

        // ---- scrim + top drop-down ----
        if (menuOpen) Scrim { menuOpen = false }
        AnimatedVisibility(
            visible = menuOpen,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Panel, RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp))
                    .swipes(onUp = { menuOpen = false })
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Label("CAMERA")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(if (state.front) "FRONT" else "BACK", Modifier.weight(1f)) { vm.flipCamera() }
                    Pill(if (state.hud) "HUD ON" else "HUD OFF", Modifier.weight(1f), selected = state.hud) { vm.toggleHud() }
                }
                Pill(
                    if (state.mirrorFront) "MIRROR FRONT: ON" else "MIRROR FRONT: OFF",
                    Modifier.fillMaxWidth(),
                    selected = state.mirrorFront,
                ) { vm.toggleMirror() }
                Pill("SHARE DEBUG LOG", Modifier.fillMaxWidth()) { shareLog(context) { vm.showMessage("No log written yet") } }
                Handle(Modifier.align(Alignment.CenterHorizontally))
            }
        }

        // ---- scrim + bottom sheet ----
        if (sheetOpen) Scrim { sheetOpen = false }
        AnimatedVisibility(
            visible = sheetOpen,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Panel, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)),
            ) {
                Box(
                    Modifier.fillMaxWidth().height(28.dp).swipes(onDown = { sheetOpen = false }),
                    contentAlignment = Alignment.Center,
                ) { Handle() }
                Column(
                    Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Label("MODES")
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.height(190.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(state.looks) { index, l ->
                            Pill(l.name.uppercase(), Modifier.fillMaxWidth(), selected = index == state.selected) { vm.selectLook(index) }
                        }
                    }
                    Label("WEAR")
                    Slider(
                        value = state.wear,
                        onValueChange = vm::setWear,
                        colors = SliderDefaults.colors(thumbColor = Rec, activeTrackColor = Cyan),
                    )
                    Label("CLARITY")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (lv in vm.clarityLevels) {
                            val weight = when (lv.id) { "hd" -> 0.8f; "veryclear" -> 1.6f; else -> 1f }
                            Pill(lv.label, Modifier.weight(weight), selected = lv.id == state.clarity) { vm.setClarity(lv.id) }
                        }
                    }
                    Label("SIGNAL LINES  ${(state.lines * 100).roundToInt()}%   (50% = mode default)")
                    Slider(
                        value = state.lines,
                        onValueChange = vm::setLines,
                        colors = SliderDefaults.colors(thumbColor = Rec, activeTrackColor = Cyan),
                    )
                    if (look != null) {
                        val vr = look.resolution(level?.hd == true)
                        val short = minOf(vr.w, vr.h)
                        val long = maxOf(vr.w, vr.h)
                        Label("PHOTO SIZE  ${short * state.exportScale} x ${long * state.exportScale}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (scale in exportScaleOptions(look, level?.hd == true)) {
                                Pill("${scale}x", Modifier.weight(1f), selected = scale == state.exportScale) { vm.setExportScale(scale) }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        // ---- toast ----
        state.message?.let {
            Text(
                it,
                color = Cream,
                fontFamily = Mono,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC05071F), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ pieces

@Composable
private fun Pill(text: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Navy else Cream,
        fontFamily = Mono,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Cream else Color(0x331A2AD0))
            .border(1.dp, if (selected) Cream else Color(0x66EFEADB), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    )
}

/** Photo: cream disc. Video: red disc, turning into a red square while recording. */
@Composable
private fun Shutter(videoMode: Boolean, recording: Boolean, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val ring = if (busy) Color.Gray else Cream
    val fill = when {
        busy -> Color.Gray
        videoMode -> Rec
        else -> Cream
    }
    val shape: Shape = if (recording) RoundedCornerShape(6.dp) else CircleShape
    Box(
        Modifier
            .size(76.dp)
            .border(4.dp, ring, CircleShape)
            .clip(CircleShape)
            .clickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(if (recording) 28.dp else 58.dp).clip(shape).background(fill))
    }
}

/** Round preview of the last saved photo or video; tap to open it. */
@Composable
private fun LastCapture(bitmap: Bitmap?, onClick: () -> Unit) {
    val shape = Modifier.size(64.dp).clip(CircleShape).border(2.dp, Cream, CircleShape)
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Last capture",
            modifier = shape.clickable(onClick = onClick),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(shape.background(Color(0x331A2AD0)))
    }
}

@Composable
private fun RecTimer(startMs: Long, modifier: Modifier) {
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startMs) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val sec = ((now - startMs) / 1000).coerceAtLeast(0)
    Text(
        (if (sec % 2L == 0L) "\u25CF " else "  ") + "REC %02d:%02d".format(Locale.US, sec / 60, sec % 60),
        color = Rec,
        fontFamily = Mono,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        modifier = modifier.background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * Vertical zoom bar like the phone volume slider. Pressing anywhere on it jumps there, dragging follows the finger
 * as fast as it moves, and letting go simply leaves the zoom where it is.
 */
@Composable
private fun ZoomBar(zoom: Float, ratio: Float, modifier: Modifier, onZoom: (Float) -> Unit) {
    val update by rememberUpdatedState(onZoom)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("T", color = Cream, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .width(40.dp)
                .height(190.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0x66000000))
                .border(1.dp, Color(0x66EFEADB), RoundedCornerShape(20.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        update(zoomAt(down.position.y, size.height))
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            update(zoomAt(change.position.y, size.height))
                            change.consume()
                        }
                    }
                },
        ) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(zoom.coerceIn(0f, 1f))
                    .background(Color(0xCCEFEADB)),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text("W", color = Cream, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text("%.1fx".format(Locale.US, ratio), color = Cyan, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** Finger position (top = max zoom) to 0..1. */
private fun zoomAt(y: Float, height: Int): Float = if (height <= 0) 0f else (1f - y / height).coerceIn(0f, 1f)

@Composable
private fun Label(text: String) {
    Text(text, color = Color(0x99EFEADB), fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Handle(modifier: Modifier = Modifier) {
    Box(modifier.size(width = 40.dp, height = 4.dp).background(Color(0x88EFEADB), RoundedCornerShape(2.dp)))
}

/** Full-screen tap-to-dismiss layer (no ripple). */
@Composable
private fun Scrim(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    )
}

/** One drag detector for all four directions; fires once when the drag ends past the threshold. */
@Composable
private fun Modifier.swipes(
    onLeft: () -> Unit = {},
    onRight: () -> Unit = {},
    onUp: () -> Unit = {},
    onDown: () -> Unit = {},
): Modifier {
    val left by rememberUpdatedState(onLeft)
    val right by rememberUpdatedState(onRight)
    val up by rememberUpdatedState(onUp)
    val down by rememberUpdatedState(onDown)
    return pointerInput(Unit) {
        var total = Offset.Zero
        val threshold = 80f
        detectDragGestures(
            onDragStart = { total = Offset.Zero },
            onDragEnd = {
                if (abs(total.x) > abs(total.y)) {
                    if (total.x <= -threshold) left() else if (total.x >= threshold) right()
                } else {
                    if (total.y <= -threshold) up() else if (total.y >= threshold) down()
                }
            },
            onDragCancel = { total = Offset.Zero },
            onDrag = { change, amount ->
                change.consume()
                total += amount
            },
        )
    }
}

@Composable
private fun GlPreview(renderer: LofiRenderer, modifier: Modifier) {
    val activity = LocalContext.current as ComponentActivity
    var view by remember { mutableStateOf<GLSurfaceView?>(null) }
    DisposableEffect(activity, view) {
        val v = view
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> v?.onResume()
                Lifecycle.Event.ON_PAUSE -> v?.onPause()
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(3)
                setEGLConfigChooser(app.lofi.render.RecordableConfigChooser())
                preserveEGLContextOnPause = true
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                renderer.renderRequester = { requestRender() }
                view = this
            }
        },
    )
}

/** Shares the rolling debug log through the system share sheet (FileProvider, no storage permission). */
private fun shareLog(context: Context, onMissing: () -> Unit) {
    val file = LofiLog.logFile()
    if (file == null || !file.exists()) {
        onMissing()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share LoFiCam log"))
}

private fun openMedia(context: Context, uri: Uri, isVideo: Boolean, onFail: () -> Unit) {
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, if (isVideo) "video/*" else "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(view)
    } catch (e: ActivityNotFoundException) {
        onFail()
    }
}
