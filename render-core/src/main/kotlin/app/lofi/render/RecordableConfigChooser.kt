package app.lofi.render

import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * RGBA8888 config flagged EGL_RECORDABLE_ANDROID, so the preview's own EGL context can also render into a
 * MediaCodec input surface (see [EncoderEglSurface]). Falls back to progressively less strict configs.
 */
class RecordableConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val tries = listOf(ES3_BIT to true, ES2_BIT to true, ES3_BIT to false, ES2_BIT to false)
        for ((renderable, recordable) in tries) {
            val attribs = ArrayList<Int>()
            attribs.addAll(listOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                EGL_RENDERABLE_TYPE, renderable,
            ))
            if (recordable) attribs.addAll(listOf(EGL_RECORDABLE_ANDROID, 1))
            attribs.add(EGL10.EGL_NONE)
            val spec = attribs.toIntArray()
            val count = IntArray(1)
            if (!egl.eglChooseConfig(display, spec, null, 0, count) || count[0] <= 0) continue
            val configs = arrayOfNulls<EGLConfig>(count[0])
            if (!egl.eglChooseConfig(display, spec, configs, count[0], count)) continue
            val first = configs.firstOrNull { it != null }
            if (first != null) {
                LofiLog.i("gl", "EGL config: renderable=0x${Integer.toHexString(renderable)} recordable=$recordable")
                return first
            }
        }
        throw IllegalStateException("no usable EGL config")
    }

    private companion object {
        const val EGL_RENDERABLE_TYPE = 0x3040
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val ES2_BIT = 0x0004
        const val ES3_BIT = 0x0040
    }
}
