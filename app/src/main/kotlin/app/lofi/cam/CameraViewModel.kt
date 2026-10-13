package app.lofi.cam

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import app.lofi.render.Look
import app.lofi.render.LofiLog
import app.lofi.render.LofiRenderer
import app.lofi.render.LookParser
import app.lofi.render.ParamRegistry
import app.lofi.render.PixelFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
)

/** Photo size multipliers offered for a look (long side capped so the bitmap stays reasonable). */
fun exportScaleOptions(look: Look): List<Int> {
    val longSide = maxOf(look.virtualResolution.w, look.virtualResolution.h)
    return listOf(1, 2, 3, 4, 6, 8).filter { longSide * it <= 2560 }
}

class CameraViewModel(app: Application) : AndroidViewModel(app) {
    val renderer: LofiRenderer
    private val camera: CameraController
    private val saver = MediaSaver(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val assets = app.assets
        fun asset(path: String) = assets.open(path).bufferedReader().use { it.readText() }
        val registry = ParamRegistry.fromJson(asset("params.json"))
        renderer = LofiRenderer(assets, PixelFont.fromJson(asset("font/pixelfont.json")))
        camera = CameraController(app, renderer)
        val looks = LookRepository(assets, LookParser(registry)).loadAll()
        _state.update { it.copy(looks = looks, message = if (looks.isEmpty()) "No looks found" else null) }
        renderer.onError = { msg -> _state.update { it.copy(message = msg) } }
        renderer.onContextRecreated = { camera.rebind() }
        camera.onError = { msg -> _state.update { it.copy(message = msg) } }
        if (looks.isNotEmpty()) selectLook(0)
    }

    fun bindCamera(owner: LifecycleOwner) = camera.bind(owner, _state.value.front)

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

    fun setExportScale(scale: Int) {
        _state.update { it.copy(exportScale = scale) }
        push()
    }

    fun toggleHud() {
        _state.update { it.copy(hud = !it.hud) }
        push()
    }

    fun setWear(value: Float) {
        _state.update { it.copy(wear = value) }
        push()
    }

    fun flipCamera() = _state.update { it.copy(front = !it.front) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun showMessage(msg: String) = _state.update { it.copy(message = msg) }

    fun capture() {
        val s = _state.value
        val look = s.looks.getOrNull(s.selected) ?: return
        if (s.busy) return
        _state.update { it.copy(busy = true) }
        renderer.requestCapture { bitmap ->
            viewModelScope.launch(Dispatchers.IO) {
                val msg = if (bitmap == null) {
                    "No camera frame yet. Try again."
                } else {
                    try {
                        saver.save(bitmap, look)
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

    private fun push() {
        val s = _state.value
        val look = s.looks.getOrNull(s.selected) ?: return
        val vr = look.virtualResolution
        _state.update { it.copy(aspect = minOf(vr.w, vr.h).toFloat() / maxOf(vr.w, vr.h)) }
        renderer.setConfig(LofiRenderer.Config(look, look.resolve(mapOf("wear" to s.wear)), exportScale = s.exportScale, hud = s.hud))
    }
}
