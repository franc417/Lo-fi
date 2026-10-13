package app.lofi.render

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Real-time look pipeline. Owns all GL state; every GL call happens on the GLSurfaceView render thread.
 *
 *   camera (OES) -> ingest (virtual res) -> stage1 (analog, + history) -> stage2 (digital) -> present
 *
 * Public methods are thread-safe: they only enqueue work that the GL thread picks up in [onDrawFrame].
 */
class LofiRenderer(private val assets: AssetManager, private val font: PixelFont) : GLSurfaceView.Renderer {

    /** A look plus its fully resolved uniform values (macros applied). */
    class Config(
        val look: Look,
        val uniforms: Map<String, FloatArray>,
        val portrait: Boolean = true,
        /** Photo export multiplier of the virtual resolution (defaults to the look's own). */
        val exportScale: Int = look.exportScale,
        /** Burned-in HUD on/off (only matters for looks that have one). */
        val hud: Boolean = true,
    )

    private class InputRequest(val width: Int, val height: Int, val onSurface: (Surface) -> Unit)
    private class Target(val tex: Int, val fbo: Int, val w: Int, val h: Int)

    // ---- thread-safe inputs ----
    private val pendingConfig = AtomicReference<Config?>(null)
    private val inputRequests = ConcurrentLinkedQueue<InputRequest>()
    private val surfaceReleases = ConcurrentLinkedQueue<Surface>()
    private val captureRequests = ConcurrentLinkedQueue<(Bitmap?) -> Unit>()
    private val frameAvailable = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile var renderRequester: (() -> Unit)? = null
    @Volatile var onError: ((String) -> Unit)? = null
    @Volatile var onContextRecreated: (() -> Unit)? = null
    @Volatile private var rotationDegrees = -1   // -1 = CameraX has not told us yet
    @Volatile private var mirror = false

    // ---- GL-thread state ----
    private var programs: Programs? = null
    private var oesTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var inputW = 0
    private var inputH = 0
    private var config: Config? = null
    private var lastConfig: Config? = null
    private var shapeKey: String? = null
    private var vrW = 0
    private var vrH = 0
    private var ingestT: Target? = null
    private val hist = arrayOfNulls<Target>(2)
    private var histCur = 0
    private var historyValid = false
    private var finalT: Target? = null
    private var exportT: Target? = null
    private var overlayTex = 0
    private var overlayBmp: Bitmap? = null
    private var overlayPix = IntArray(0)
    private var overlayDirty = true
    private var lastOverlaySec = -1L
    private var paramList: List<Pair<String, FloatArray>> = emptyList()
    private var surfaceW = 1
    private var surfaceH = 1
    private var frameIndex = 0
    private val texMatrix = FloatArray(16)
    private var errorCount = 0

    // perf
    private var perfFrames = 0
    private var perfCpuNs = 0L
    private var perfMaxNs = 0L
    private var perfWindowStart = 0L

    // ---------------- public API (any thread) ----------------

    fun setConfig(c: Config) {
        pendingConfig.set(c)
        renderRequester?.invoke()
    }

    fun setRotation(degrees: Int) { rotationDegrees = degrees }

    fun setMirror(m: Boolean) { mirror = m }

    /** Asks for a camera input surface of the given size; [onSurface] is called on the main thread. */
    fun requestInputSurface(width: Int, height: Int, onSurface: (Surface) -> Unit) {
        inputRequests.add(InputRequest(width, height, onSurface))
        renderRequester?.invoke()
    }

    fun releaseInputSurface(surface: Surface) {
        surfaceReleases.add(surface)
        renderRequester?.invoke()
    }

    /** Captures the current processed frame at export size. Callback runs on the GL thread; null = failed. */
    fun requestCapture(callback: (Bitmap?) -> Unit) {
        captureRequests.add(callback)
        renderRequester?.invoke()
    }

    // ---------------- GLSurfaceView.Renderer ----------------

    override fun onSurfaceCreated(gl: GL10?, eglConfig: EGLConfig?) {
        val hadInput = surfaceTexture != null
        dropGlState()
        LofiLog.i("gl", "context: ${GLES20.glGetString(GLES20.GL_RENDERER)} | ${GLES20.glGetString(GLES20.GL_VERSION)}")
        programs = try {
            ShaderLibrary.build(assets)
        } catch (t: Throwable) {
            LofiLog.e("gl", "shader build failed", t)
            reportError("Shader error: ${t.message}")
            null
        }
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        lastConfig?.let { pendingConfig.compareAndSet(null, it) }
        if (hadInput) mainHandler.post { onContextRecreated?.invoke() }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
        LofiLog.i("gl", "surface ${width}x$height")
    }

    override fun onDrawFrame(gl: GL10?) {
        try {
            drawFrame()
        } catch (t: Throwable) {
            errorCount++
            if (errorCount <= 5) LofiLog.e("render", "frame failed", t)
            if (errorCount == 1) reportError("Render error: ${t.message}")
            drainCaptures(null)
        }
    }

    // ---------------- frame ----------------

    private fun drawFrame() {
        handleInputRequests()
        pendingConfig.getAndSet(null)?.let { applyConfig(it) }
        val cfg = config
        val p = programs
        if (cfg == null || p == null) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drainCaptures(null)
            return
        }
        if (frameAvailable.getAndSet(false) && surfaceTexture != null) {
            val t0 = System.nanoTime()
            updateOverlay(cfg)
            runPipeline(cfg, p)
            recordPerf(System.nanoTime() - t0)
        }
        presentTo(0, surfaceW, surfaceH)
        while (true) {
            val cb = captureRequests.poll() ?: break
            val bmp = try {
                if (historyValid) capture() else null
            } catch (t: Throwable) {
                LofiLog.e("render", "capture failed", t)
                null
            }
            cb(bmp)
        }
    }

    private fun runPipeline(cfg: Config, p: Programs) {
        val st = surfaceTexture ?: return
        val ing = ingestT ?: return
        val fin = finalT ?: return
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)

        // 1) ingest: camera -> virtual resolution (rotate / mirror / centre-crop)
        val ip = IngestTransform.compute(inputW, inputH, vrW, vrH, rotationDegrees, texMatrix)
        if (frameIndex == 0 || frameIndex % 300 == 0) {
            LofiLog.i("ingest", "input=${inputW}x$inputH cameraxRotation=$rotationDegrees matrixRotation=${ip.matrixDegrees} " +
                "remaining=${ip.remainingDegrees} mirror=$mirror crop=${ip.cropX},${ip.cropY}")
        }
        bindTarget(ing.fbo, ing.w, ing.h)
        GLES20.glUseProgram(p.ingest.id)
        bindTexture(0, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(p.ingest.loc("uTex"), 0)
        GLES20.glUniformMatrix4fv(p.ingest.loc("uTexMatrix"), 1, false, texMatrix, 0)
        GLES20.glUniformMatrix2fv(p.ingest.loc("uRot"), 1, false, floatArrayOf(ip.rotCos, ip.rotSin, -ip.rotSin, ip.rotCos), 0)
        GLES20.glUniform2f(p.ingest.loc("uCrop"), ip.cropX, ip.cropY)
        GLES20.glUniform1f(p.ingest.loc("uMirror"), if (mirror) 1f else 0f)
        draw()

        // 2) stage 1 (analog): reads ingest + previous history + overlay, writes the next history slot
        val prevIdx = histCur
        val curIdx = 1 - histCur
        val prevT = hist[prevIdx] ?: return
        val curT = hist[curIdx] ?: return
        bindTarget(curT.fbo, curT.w, curT.h)
        GLES20.glUseProgram(p.stage1.id)
        bindTexture(0, GLES20.GL_TEXTURE_2D, ing.tex)
        bindTexture(1, GLES20.GL_TEXTURE_2D, prevT.tex)
        bindTexture(2, GLES20.GL_TEXTURE_2D, overlayTex)
        GLES20.glUniform1i(p.stage1.loc("uTex"), 0)
        GLES20.glUniform1i(p.stage1.loc("uPrev"), 1)
        GLES20.glUniform1i(p.stage1.loc("uOverlay"), 2)
        GLES20.glUniform2f(p.stage1.loc("uSize"), vrW.toFloat(), vrH.toFloat())
        GLES20.glUniform1i(p.stage1.loc("uFrame"), frameIndex)
        GLES20.glUniform1f(p.stage1.loc("uHistoryValid"), if (historyValid) 1f else 0f)
        GLES20.glUniform1f(p.stage1.loc("uOverlayAmount"), if (cfg.look.overlay == OverlayStyle.NONE || !cfg.hud) 0f else 1f)
        uploadParams(p.stage1)
        draw()

        // 3) stage 2 (digital): reads the fresh history slot, writes the final image
        bindTarget(fin.fbo, fin.w, fin.h)
        GLES20.glUseProgram(p.stage2.id)
        bindTexture(0, GLES20.GL_TEXTURE_2D, curT.tex)
        GLES20.glUniform1i(p.stage2.loc("uTex"), 0)
        GLES20.glUniform2f(p.stage2.loc("uSize"), vrW.toFloat(), vrH.toFloat())
        GLES20.glUniform1i(p.stage2.loc("uFrame"), frameIndex)
        uploadParams(p.stage2)
        draw()

        histCur = curIdx
        historyValid = true
        frameIndex++
        if (frameIndex <= 3 || frameIndex % 150 == 0) GlUtil.checkError("frame $frameIndex")
    }

    private fun presentTo(fbo: Int, w: Int, h: Int) {
        val p = programs ?: return
        val fin = finalT ?: run {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }
        bindTarget(fbo, w, h)
        GLES20.glUseProgram(p.present.id)
        bindTexture(0, GLES20.GL_TEXTURE_2D, fin.tex)
        GLES20.glUniform1i(p.present.loc("uTex"), 0)
        draw()
    }

    private fun capture(): Bitmap? {
        val cfg = config ?: return null
        val w = vrW * cfg.exportScale
        val h = vrH * cfg.exportScale
        val existing = exportT
        val ex: Target = if (existing != null && existing.w == w && existing.h == h) {
            existing
        } else {
            existing?.let { deleteTarget(it) }
            createTarget(w, h, linear = true).also { exportT = it }
        }
        presentTo(ex.fbo, w, h)
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ex.fbo)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GlUtil.checkError("capture")
        buf.rewind()
        val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buf)
        val flip = Matrix().apply { preScale(1f, -1f) }   // GL rows are bottom-up
        val out = Bitmap.createBitmap(raw, 0, 0, w, h, flip, false)
        if (out !== raw) raw.recycle()
        return out
    }

    // ---------------- config / targets ----------------

    private fun applyConfig(cfg: Config) {
        val vr = cfg.look.virtualResolution
        val swap = cfg.portrait && vr.w > vr.h
        val w = if (swap) vr.h else vr.w
        val h = if (swap) vr.w else vr.h
        val key = "${w}x$h|${cfg.look.upscale}|${cfg.look.overlay}"
        if (key != shapeKey) {
            releaseTargets()
            vrW = w
            vrH = h
            val linear = cfg.look.upscale == Upscale.LINEAR
            ingestT = createTarget(w, h, true)
            hist[0] = createTarget(w, h, true)
            hist[1] = createTarget(w, h, true)
            finalT = createTarget(w, h, linear)
            histCur = 0
            historyValid = false
            overlayBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            overlayPix = IntArray(w * h)
            overlayTex = newTexture()
            overlayDirty = true
            lastOverlaySec = -1L
            shapeKey = key
            LofiLog.i("render", "look '${cfg.look.id}' virtual ${w}x$h upscale=${cfg.look.upscale}")
        }
        paramList = cfg.uniforms.map { (path, v) -> uniformName(path) to v }
        config = cfg
        lastConfig = cfg
    }

    private fun updateOverlay(cfg: Config) {
        val bmp = overlayBmp ?: return
        val nowMs = System.currentTimeMillis()
        val sec = nowMs / 1000
        if (!overlayDirty && sec == lastOverlaySec) return
        val first = overlayDirty
        overlayDirty = false
        lastOverlaySec = sec
        val (date, time) = OverlayText.format(cfg.look.overlay, nowMs)
        OverlayPainter.paint(font, cfg.look.overlay, vrW, vrH, date, time, sec % 2L == 0L, overlayPix)
        bmp.setPixels(overlayPix, 0, vrW, 0, 0, vrW, vrH)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
        if (first) GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0) else GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
    }

    private fun newTexture(): Int {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        setParams(GLES20.GL_TEXTURE_2D, true)
        return t[0]
    }

    private fun setParams(target: Int, linear: Boolean) {
        val f = if (linear) GLES20.GL_LINEAR else GLES20.GL_NEAREST
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, f)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, f)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun createTarget(w: Int, h: Int, linear: Boolean): Target {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        setParams(GLES20.GL_TEXTURE_2D, linear)
        val f = IntArray(1)
        GLES20.glGenFramebuffers(1, f, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t[0], 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "framebuffer incomplete 0x${Integer.toHexString(status)} (${w}x$h)" }
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        return Target(t[0], f[0], w, h)
    }

    private fun deleteTarget(t: Target) {
        GLES20.glDeleteFramebuffers(1, intArrayOf(t.fbo), 0)
        GLES20.glDeleteTextures(1, intArrayOf(t.tex), 0)
    }

    private fun releaseTargets() {
        ingestT?.let { deleteTarget(it) }
        hist[0]?.let { deleteTarget(it) }
        hist[1]?.let { deleteTarget(it) }
        finalT?.let { deleteTarget(it) }
        exportT?.let { deleteTarget(it) }
        if (overlayTex != 0) GLES20.glDeleteTextures(1, intArrayOf(overlayTex), 0)
        overlayBmp?.recycle()
        ingestT = null; hist[0] = null; hist[1] = null; finalT = null; exportT = null
        overlayTex = 0; overlayBmp = null
    }

    /** The GL context is gone: forget every GL object without calling GL. */
    private fun dropGlState() {
        runCatching { inputSurface?.release() }
        runCatching { surfaceTexture?.release() }
        inputSurface = null; surfaceTexture = null; oesTex = 0
        programs = null
        ingestT = null; hist[0] = null; hist[1] = null; finalT = null; exportT = null
        overlayTex = 0
        overlayBmp?.recycle(); overlayBmp = null
        shapeKey = null
        historyValid = false
        config = null
    }

    // ---------------- camera input ----------------

    private fun handleInputRequests() {
        while (true) {
            val s = surfaceReleases.poll() ?: break
            if (s === inputSurface) releaseInput() else runCatching { s.release() }
        }
        while (true) {
            val req = inputRequests.poll() ?: break
            releaseInput()
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
            setParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, true)
            val st = SurfaceTexture(t[0])
            st.setDefaultBufferSize(req.width, req.height)
            st.setOnFrameAvailableListener {
                frameAvailable.set(true)
                renderRequester?.invoke()
            }
            val surface = Surface(st)
            oesTex = t[0]
            surfaceTexture = st
            inputSurface = surface
            inputW = req.width
            inputH = req.height
            frameAvailable.set(false)
            LofiLog.i("camera", "input surface ${req.width}x${req.height}")
            val cb = req.onSurface
            mainHandler.post { cb(surface) }
        }
    }

    private fun releaseInput() {
        runCatching { inputSurface?.release() }
        runCatching { surfaceTexture?.release() }
        if (oesTex != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTex), 0)
        inputSurface = null; surfaceTexture = null; oesTex = 0
        frameAvailable.set(false)
    }

    // ---------------- helpers ----------------

    private fun uploadParams(p: Program) {
        for ((name, v) in paramList) {
            val loc = p.loc(name)
            if (loc < 0) continue
            if (v.size == 1) GLES20.glUniform1f(loc, v[0]) else GLES20.glUniform3f(loc, v[0], v[1], v[2])
        }
    }

    private fun bindTarget(fbo: Int, w: Int, h: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, w, h)
    }

    private fun bindTexture(unit: Int, target: Int, id: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(target, id)
    }

    private fun draw() = GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)

    private fun drainCaptures(result: Bitmap?) {
        while (true) {
            val cb = captureRequests.poll() ?: break
            cb(result)
        }
    }

    private fun reportError(msg: String) {
        mainHandler.post { onError?.invoke(msg) }
    }

    private fun recordPerf(ns: Long) {
        val now = System.nanoTime()
        if (perfFrames == 0) perfWindowStart = now
        perfFrames++
        perfCpuNs += ns
        if (ns > perfMaxNs) perfMaxNs = ns
        if (perfFrames >= 150) {
            val fps = perfFrames * 1e9 / (now - perfWindowStart)
            LofiLog.i("perf", "fps=%.1f cpu_avg=%.2fms cpu_max=%.2fms look=%s".format(
                fps, perfCpuNs / perfFrames / 1e6, perfMaxNs / 1e6, config?.look?.id))
            perfFrames = 0; perfCpuNs = 0; perfMaxNs = 0
        }
    }
}
