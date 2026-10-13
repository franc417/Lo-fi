package app.lofi.cam.ui

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lofi.cam.CameraViewModel
import app.lofi.render.LofiRenderer
import kotlinx.coroutines.delay

private val Navy = Color(0xFF0A1070)
private val Cream = Color(0xFFEFEADB)
private val Rec = Color(0xFFFF2D3D)
private val Cyan = Color(0xFF19E3FF)
private val Mono = FontFamily.Monospace

@Composable
fun LofiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Rec, secondary = Cyan, background = Color(0xFF05071F), surface = Navy),
        content = content,
    )
}

@Composable
fun CameraScreen(vm: CameraViewModel) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val state by vm.state.collectAsStateWithLifecycle()

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(hasPermission, state.front) { if (hasPermission) vm.bindCamera(activity) }
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(3000)
            vm.clearMessage()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(state.aspect).background(Color.Black)) {
            if (hasPermission) GlPreview(vm.renderer, Modifier.fillMaxSize())
            else Text("Camera permission needed", color = Cream, fontFamily = Mono, modifier = Modifier.align(Alignment.Center))
            state.message?.let {
                Text(
                    it,
                    color = Cream,
                    fontFamily = Mono,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                        .background(Color(0xCC05071F), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(state.looks) { index, look ->
                val selected = index == state.selected
                Text(
                    look.name.uppercase(),
                    color = if (selected) Navy else Cream,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) Cream else Color(0x331A2AD0))
                        .border(BorderStroke(1.dp, if (selected) Cream else Color(0x66EFEADB)), RoundedCornerShape(10.dp))
                        .clickable { vm.selectLook(index) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("WEAR", color = Cream, fontFamily = Mono, fontSize = 12.sp)
            Slider(
                value = state.wear,
                onValueChange = vm::setWear,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                colors = SliderDefaults.colors(thumbColor = Rec, activeTrackColor = Cyan),
            )
        }

        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("PHOTO", color = Cream, fontFamily = Mono, fontWeight = FontWeight.Bold, modifier = Modifier.width(70.dp))
            Box(
                Modifier
                    .size(78.dp)
                    .border(4.dp, if (state.busy) Color.Gray else Cream, CircleShape)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(if (state.busy) Color.Gray else Rec)
                    .clickable(enabled = !state.busy && hasPermission) { vm.capture() },
            )
            Text(
                "FLIP",
                color = Cream,
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .width(70.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Cream, RoundedCornerShape(10.dp))
                    .clickable { vm.flipCamera() }
                    .padding(vertical = 10.dp),
                textAlign = TextAlign.Center,
            )
        }
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
                preserveEGLContextOnPause = true
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                renderer.renderRequester = { requestRender() }
                view = this
            }
        },
    )
}
