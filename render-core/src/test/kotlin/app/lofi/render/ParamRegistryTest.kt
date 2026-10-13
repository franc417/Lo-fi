package app.lofi.render

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParamRegistryTest {
    private val registry = TestFiles.registry()

    @Test fun uniformNamesFollowThePathConvention() {
        assertEquals("uLensSoftness", uniformName("lens.softness"))
        assertEquals("uDigitalPaletteDark", uniformName("digital.paletteDark"))
        assertEquals("uTapeChromaBleed", uniformName("tape.chromaBleed"))
    }

    @Test fun defaultsAreInsideTheirRanges() {
        for (p in registry.all) {
            assertEquals(p.path, p.type.size, p.default.size)
            for (d in p.default) assertTrue("${p.path} default $d", d >= p.min && d <= p.max)
        }
    }

    @Test fun pathsAreUnique() {
        assertEquals(registry.all.size, registry.all.map { it.path }.toSet().size)
    }

    @Test fun everyParamIsDeclaredAsAUniformInSomeShader() {
        val shaders = File(TestFiles.assets, "shaders").listFiles { f -> f.extension == "frag" }!!.map { it.readText() }
        for (p in registry.all) {
            val type = if (p.type == ParamType.FLOAT) "float" else "vec3"
            val decl = Regex("uniform\\s+$type\\s+${p.uniform}\\s*;")
            assertTrue("no shader declares 'uniform $type ${p.uniform};'", shaders.any { decl.containsMatchIn(it) })
        }
    }
}
