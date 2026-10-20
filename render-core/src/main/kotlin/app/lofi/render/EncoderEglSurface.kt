package app.lofi.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/**
 * A window EGLSurface around a MediaCodec input surface, created against the GL thread's CURRENT context and
 * using that context's own config, so the textures and programs of the preview pipeline can be used directly.
 */
internal class EncoderEglSurface(
    private val display: EGLDisplay,
    private val context: EGLContext,
    surface: Surface,
) {
    private var eglSurface: EGLSurface

    init {
        val configId = IntArray(1)
        check(EGL14.eglQueryContext(display, context, EGL14.EGL_CONFIG_ID, configId, 0)) { "eglQueryContext failed: ${err()}" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val spec = intArrayOf(EGL14.EGL_CONFIG_ID, configId[0], EGL14.EGL_NONE)
        check(EGL14.eglChooseConfig(display, spec, 0, configs, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig failed: ${err()}" }
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: ${err()}" }
    }

    fun makeCurrent() {
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent(encoder) failed: ${err()}" }
    }

    fun setPresentationTime(nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, nanos)
    }

    fun swap(): Boolean = EGL14.eglSwapBuffers(display, eglSurface)

    fun release() {
        runCatching { EGL14.eglDestroySurface(display, eglSurface) }
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun err() = "0x" + Integer.toHexString(EGL14.eglGetError())
}
