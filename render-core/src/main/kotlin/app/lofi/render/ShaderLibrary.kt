package app.lofi.render

import android.content.res.AssetManager
import android.opengl.GLES20

/**
 * Loads the GLSL from assets/shaders and compiles it as GLSL ES 3.00. The desktop harness
 * (tools/golden/render_golden.py) prepends `#version 330 core` instead; the shader bodies are shared.
 */
internal object ShaderLibrary {
    private const val VERT_PRELUDE = "#version 300 es\n"
    private const val FRAG_PRELUDE =
        "#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\n" +
            "#define INPUT_SAMPLER sampler2D\n"
    private const val INGEST_PRELUDE =
        "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\n" +
            "precision highp float;\nprecision highp int;\nprecision highp sampler2D;\n" +
            "#define INPUT_SAMPLER samplerExternalOES\n"

    fun build(assets: AssetManager): Programs {
        fun read(name: String): String = assets.open("shaders/$name").bufferedReader().use { it.readText() }
        val common = read("common.glsl")
        val vs = GlUtil.compile(GLES20.GL_VERTEX_SHADER, VERT_PRELUDE + read("fullscreen.vert"), "fullscreen.vert")

        fun program(file: String, prelude: String, withCommon: Boolean): Program {
            val src = prelude + (if (withCommon) common else "") + read(file)
            val fs = GlUtil.compile(GLES20.GL_FRAGMENT_SHADER, src, file)
            val id = GlUtil.link(vs, fs, file)
            GLES20.glDeleteShader(fs)
            return Program(id, file)
        }

        val programs = Programs(
            ingest = program("ingest.frag", INGEST_PRELUDE, false),
            stage1 = program("stage1.frag", FRAG_PRELUDE, true),
            stage2 = program("stage2.frag", FRAG_PRELUDE, true),
            present = program("present.frag", FRAG_PRELUDE, false),
        )
        GLES20.glDeleteShader(vs)
        return programs
    }
}
