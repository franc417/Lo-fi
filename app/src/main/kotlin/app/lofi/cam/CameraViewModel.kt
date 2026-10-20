package app.lofi.cam

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import app.lofi.render.ClarityLevel
import app.lofi.render.Look
import app.lofi.render.LofiLog
import app.lofi.render.LofiRenderer
import app.lofi.render.LookParser
import app.lofi.render.ParamRegistry
import app.lofi.render.PixelFont
import app.lofi.render.Tuning
import app.lofi.render.VideoRecorder
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CaptureMode { PHOTO, VIDEO }

data class UiState(
    val looks: List<Look> = emptyList(),
    val selected: Int = 0,
    val wear: Float = 0.5f,
    val front: Boolean = false,
    val aspect: Float = 0.75f,   // preview width / height (portrait)
    val busy: Boolean = false,
    val message: String? = null,
    val exportScale: Int = 2,
    val hud: Boolean = true,
    /** Mirror the front camera left-right (selfie preview). Applies to preview, photos and videos. */
    val mirrorFront: Boolean = true,
    val lastThumb: Bitmap? = null,
    val lastUri: Uri? = null,
    val lastIsVideo: Boolean = false,
    /** WEAR / CLEAR / HD / VERY CLEAR (see tuning.json). */
    val clarity: String = "wear",
    /** Horizontal signal lines: 0..1, 0.5 = the mode's own amount. */
    val lines: Float = 0.5f,
    val mode: CaptureMode = CaptureMode.PHOTO,
    val recording: Boolean = false,
    val recordingStartMs: Long = 0L,
    /** Zoom bar position 0 (wide) .. 1 (max) and the resulting ratio for the read-out. */
    val zoom: Float = 0f,
    val zoomRatio: Float = 1f,
    /** Flash: photo = pulse while shooting, video = torch while recording. Front camera uses a screen flash (photo only). */
    val flash: Boolean = false,
    val screenFlash: Boolean = false,
)

/** Photo size multipliers offered for a look at the current clarity (long side capped so the bitmap stays reasonable). */
fun exportScaleOptions(look: Look, hd: Boolean): List<Int> {
    val vr = look.resolution(hd)
    val longSide = maxOf(vr.w, vr.h)
    return listOf(1, 2, 3, 4, 6, 8).filter { longSide * it <= 2560 }.ifEmpty { listOf(1) }
}

class CameraViewModel(app: Application) : AndroidViewModel(app) {
    val renderer: LofiRenderer
    private val camera: CameraController
    private val tuning: Tuning
    private val saver = MediaSaver(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var videoTarget: VideoTarget? = null

    val clarityLevels: List<ClarityLevel> get() = tuning.clarity

    init {
        val assets = app.assets
        fun asset(path: String) = assets.open(path).bufferedReader().use { it.readText() }
        val registry = ParamRegistry.fromJson(asset("params.json"))
        tuning = Tuning.fromJson(asset("tuning.json"), registry)
        renderer = LofiRenderer(assets, PixelFont.fromJson(asset("font/pixelfont.json")))
        camera = CameraController(app, renderer)
        val looks = LookRepository(assets, LookParser(registry)).loadAll()
        _state.update { it.copy(looks = looks, message = if (looks.isEmpty()) "No looks found" else null) }
        renderer.onError = { msg -> _state.update { it.copy(message = msg) } }
        renderer.onContextRecreated = { camera.rebind() }
        camera.onError = { msg -> _state.update { it.copy(message = msg) } }
        camera.onZoomRatio = { ratio -> _state.update { it.copy(zoomRatio = ratio) } }
        if (looks.isNotEmpty()) selectLook(0)
        viewModelScope.launch(Dispatchers.IO) {
            saver.latest()?.let { (uri, thumb, video) ->
                _state.update { it.copy(lastThumb = thumb, lastUri = uri, lastIsVideo = video) }
            }
        }
    }

    // ---------------------------------------------------------------- camera

    fun bindCamera(owner: LifecycleOwner) {
        val s = _state.value
        camera.bind(owner, s.front, s.front && s.mirrorFront, s.zoom)
    }

    fun flipCamera() {
        if (_state.value.recording) return
        _state.update { it.copy(front = !it.front, zoom = 0f, zoomRatio = 1f) }
    }

    fun toggleMirror() {
        _state.update { it.copy(mirrorFront = !it.mirrorFront) }
        val s = _state.value
        camera.setMirror(s.front && s.mirrorFront)
    }

    /** Zoom bar: applied instantly and kept where the finger lets go. */
    fun setZoom(value: Float) {
        _state.update { it.copy(zoom = value) }
        camera.setZoom(value)
    }

    fun toggleFlash() {
        val s = _state.value
        val on = !s.flash
        _state.update { it.copy(flash = on) }
        if (on) {
            val note = when {
                camera.hasFlash -> if (s.mode == CaptureMode.VIDEO) "Flash: torch while recording" else "Flash: fires when you take a photo"
                s.front -> "Front camera: screen flash for photos"
                else -> "This camera has no flash"
            }
            showMessage(note)
        }
    }

    // ---------------------------------------------------------------- looks

    fun selectLook(index: Int) {
        val look = _state.value.looks.getOrNull(index) ?: return
        val wear = look.macro("wear")?.default ?: 0.5f
        _state.update { it.copy(selected = index, wear = wear, exportScale = look.exportScale) }
        push()
    }

    /** Swipe navigation: +1 = next look, -1 = previous (wraps). Shows the look name briefly. */
    fun stepLook(delta: Int) {
        val s = _state.value
        if (s.looks.isEmpty()) return
        val index = Math.floorMod(s.selected + delta, s.looks.size)
        selectLook(index)
        showMessage(s.looks[index].name)
    }

    fun setWear(value: Float) {
        _state.update { it.copy(wear = value) }
        push()
    }

    fun setClarity(id: String) {
        _state.update { it.copy(clarity = id) }
        push()
    }

    fun setLines(value: Float) {
        _state.update { it.copy(lines = value) }
        push()
    }

    fun setExportScale(scale: Int) {
        _state.update { it.copy(exportScale = scale) }
        push()
    }

    fun toggleHud() {
        _state.update { it.copy(hud = !it.hud) }
        push()
    }

    fun setMode(mode: CaptureMode) {
        if (_state.value.recording) return
        _state.update { it.copy(mode = mode) }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun showMessage(msg: String) = _state.update { it.copy(message = msg) }

    // ---------------------------------------------------------------- photo

    fun capture() {
        val s = _state.value
        val look = s.looks.getOrNull(s.selected) ?: return
        if (s.busy || s.recording) return
        _state.update { it.copy(busy = true) }
        val torch = s.flash && camera.hasFlash
        val screen = s.flash && !camera.hasFlash && s.front
        viewModelScope.launch {
            if (torch) {
                camera.setTorch(true)
                delay(350)   // let auto-exposure settle on the lit scene
            }
            if (screen) {
                _state.update { it.copy(screenFlash = true) }
                delay(350)
            }
            renderer.requestCapture { bitmap ->
                viewModelScope.launch(Dispatchers.IO) {
                    if (torch) camera.setTorch(false)
                    _state.update { it.copy(screenFlash = false) }
                    val msg = if (bitmap == null) {
                        "No camera frame yet. Try again."
                    } else {
                        try {
                            val uri = saver.save(bitmap, look)
                            val thumb = saver.thumbnail(uri)
                            _state.update { it.copy(lastThumb = thumb ?: it.lastThumb, lastUri = uri, lastIsVideo = false) }
                            "Saved to Pictures/LoFiCam"
                        } catch (e: Exception) {
                            LofiLog.e("save", "failed", e)
                            "Save failed: ${e.message}"
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    _state.update { it.copy(busy = false, message = msg) }
                }
            }
        }
    }

    // ---------------------------------------------------------------- video

    fun startRecording(withAudio: Boolean) {
        val s = _state.value
        val look = s.looks.getOrNull(s.selected) ?: return
        if (s.recording || s.busy) return
        val level = tuning.level(s.clarity)
        val aspect = s.aspect

        val target = try {
            saver.openVideo(look)
        } catch (e: Exception) {
            LofiLog.e("record", "openVideo failed", e)
            showMessage("Cannot create video file: ${e.message}")
            return
        }
        // Try the preferred size first, then fall back (some devices cannot encode 1080 wide).
        var recorder: VideoRecorder? = null
        var lastError: Throwable? = null
        for ((w, h) in videoSizes(aspect, level.hd)) {
            val candidate = VideoRecorder(
                fd = target.pfd.fileDescriptor,
                width = w,
                height = h,
                bitrate = if (w >= 1080) 14_000_000 else 8_000_000,
                fps = 30,
                audioProfile = if (withAudio) level.audio else null,
            )
            try {
                candidate.prepare()
                recorder = candidate
                break
            } catch (t: Throwable) {
                lastError = t
                LofiLog.e("record", "prepare ${w}x$h failed", t)
            }
        }
        if (recorder == null) {
            saver.closeVideo(target, false)
            showMessage("This phone cannot record that size: ${lastError?.message}")
            return
        }

        videoTarget = target
        if (s.flash && camera.hasFlash) camera.setTorch(true)
        _state.update { it.copy(recording = true, recordingStartMs = SystemClock.elapsedRealtime()) }
        renderer.startRecording(recorder, object : LofiRenderer.RecordListener {
            override fun onFinished(success: Boolean) = finishVideo(success, null)
            override fun onError(message: String) = finishVideo(false, message)
        })
    }

    fun stopRecording() {
        if (_state.value.recording) renderer.stopRecording()
    }

    /** Called when the app goes to the background: finalise the file instead of leaving a stalled recording. */
    fun onAppPaused() {
        stopRecording()
        camera.setTorch(false)
    }

    private fun finishVideo(success: Boolean, error: String?) {
        val target = videoTarget
        videoTarget = null
        viewModelScope.launch(Dispatchers.IO) {
            camera.setTorch(false)
            if (target != null) saver.closeVideo(target, success)
            val thumb = if (success && target != null) saver.thumbnail(target.uri) else null
            _state.update {
                it.copy(
                    recording = false,
                    lastThumb = thumb ?: it.lastThumb,
                    lastUri = if (success && target != null) target.uri else it.lastUri,
                    lastIsVideo = if (success && target != null) true else it.lastIsVideo,
                    message = if (success) "Video saved to Movies/LoFiCam" else (error ?: "Recording failed"),
                )
            }
        }
    }

    private fun videoSizes(aspect: Float, hd: Boolean): List<Pair<Int, Int>> {
        fun size(w: Int) = w to (Math.round(w / aspect / 2f) * 2)
        return if (hd) listOf(size(1080), size(720)) else listOf(size(720))
    }

    // ---------------------------------------------------------------- pipeline

    private fun push() {
        val s = _state.value
        val look = s.looks.getOrNull(s.selected) ?: return
        val level = tuning.level(s.clarity)
        val vr = look.resolution(level.hd)
        val options = exportScaleOptions(look, level.hd)
        val scale = if (s.exportScale in options) s.exportScale else options.minByOrNull { abs(it - s.exportScale) } ?: 1
        val uniforms = tuning.apply(look.resolve(mapOf("wear" to s.wear)), level, s.lines, look.keep)
        _state.update { it.copy(aspect = minOf(vr.w, vr.h).toFloat() / maxOf(vr.w, vr.h), exportScale = scale) }
        renderer.setConfig(LofiRenderer.Config(look, uniforms, exportScale = scale, hud = s.hud, useHd = level.hd))
    }
}
