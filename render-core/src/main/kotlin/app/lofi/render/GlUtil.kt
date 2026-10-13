package app.lofi.render

import android.opengl.GLES20

internal object GlUtil {
    /** Drains and logs all pending GL errors. Returns true if there was at least one. */
    fun checkError(where: String): Boolean {
        var any = false
        while (true) {
            val e = GLES20.glGetError()
            if (e == GLES20.GL_NO_ERROR) break
            any = true
            LofiLog.e("gl", "$where: glError 0x${Integer.toHexString(e)}")
        }
        return any
    }

    fun compile(type: Int, source: String, label: String): Int {
        val s = GLES20.glCreateShader(type)
        check(s != 0) { "glCreateShader failed for $label" }
        GLES20.glShaderSource(s, source)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw IllegalStateException("shader '$label' failed to compile: $log")
        }
        return s
    }

    fun link(vs: Int, fs: Int, label: String): Int {
        val p = GLES20.glCreateProgram()
        check(p != 0) { "glCreateProgram failed for $label" }
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw IllegalStateException("program '$label' failed to link: $log")
        }
        return p
    }
}

internal class Program(val id: Int, val label: String) {
    private val cache = HashMap<String, Int>()
    fun loc(name: String): Int = cache.getOrPut(name) { GLES20.glGetUniformLocation(id, name) }
}

internal class Programs(val ingest: Program, val stage1: Program, val stage2: Program, val present: Program)
