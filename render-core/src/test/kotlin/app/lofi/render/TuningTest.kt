package app.lofi.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TuningTest {
    private val registry = TestFiles.registry()
    private val tuning = Tuning.fromJson(TestFiles.text("tuning.json"), registry)
    private val parser = LookParser(registry)
    private fun look(id: String) = TestFiles.lookFiles().map { parser.parse(it.readText()) }.first { it.id == id }
    private fun uniforms(id: String): Map<String, FloatArray> = look(id).resolve()
    private fun f(m: Map<String, FloatArray>, path: String) = m[path]!![0]

    @Test fun levelsAreWearClearHdVeryClearInOrder() {
        assertEquals(listOf("wear", "clear", "hd", "veryclear"), tuning.clarity.map { it.id })
        assertEquals(listOf(false, false, true, true), tuning.clarity.map { it.hd })
        assertEquals(AudioProfile.LOFI, tuning.level("wear").audio)
        assertEquals(AudioProfile.CLEAN, tuning.level("veryclear").audio)
        assertEquals("unknown ids fall back to the first level", "wear", tuning.level("nope").id)
    }

    @Test fun wearAtDefaultLinesChangesNothing() {
        for (file in TestFiles.lookFiles()) {
            val look = parser.parse(file.readText())
            val base = look.resolve()
            val out = tuning.apply(base, tuning.level("wear"), 0.5f, look.keep)
            for ((k, v) in base) assertArrayEquals("${look.id} $k", v, out[k], 1e-6f)
        }
    }

    @Test fun clarityScalesNoiseButNotTheModesCharacter() {
        val base = uniforms("camcorder-98")
        val clear = tuning.apply(base, tuning.level("clear"), 0.5f)
        val very = tuning.apply(base, tuning.level("veryclear"), 0.5f)
        assertEquals(f(base, "digital.grain") * 0.4f, f(clear, "digital.grain"), 1e-6f)
        assertEquals(0f, f(very, "digital.grain"), 0f)
        assertEquals(0f, f(very, "tape.chromaBleed"), 0f)
        // colour grade, tint, palette, dither, night vision are never in the factor list
        for (path in listOf("tone.saturation", "tone.contrast", "tone.shadowTint", "digital.levels", "digital.paletteAmount", "night.amount")) {
            assertArrayEquals(path, base[path], very[path], 0f)
        }
    }

    @Test fun keepProtectsIntendedEffects() {
        val trail = look("trail")
        val out = tuning.apply(trail.resolve(), tuning.level("veryclear"), 0.5f, trail.keep)
        assertEquals("trail keeps its ghost", f(trail.resolve(), "tape.ghost"), f(out, "tape.ghost"), 1e-6f)
        assertEquals(0f, f(out, "digital.grain"), 0f)
        val old = look("old-movie")
        val o2 = tuning.apply(old.resolve(), tuning.level("veryclear"), 0.5f, old.keep)
        assertEquals(f(old.resolve(), "film.scratches"), f(o2, "film.scratches"), 1e-6f)
    }

    @Test fun signalLinesScaleAndAddAFloor() {
        val base = uniforms("camcorder-98")
        val s = f(base, "digital.scanlines")
        assertEquals(0f, f(tuning.apply(base, tuning.level("wear"), 0f), "digital.scanlines"), 0f)
        assertEquals(s * 0.5f, f(tuning.apply(base, tuning.level("wear"), 0.25f), "digital.scanlines"), 1e-6f)
        assertEquals(s, f(tuning.apply(base, tuning.level("wear"), 0.5f), "digital.scanlines"), 1e-6f)
        assertEquals(minOf(s * 2f + 0.35f, 1f), f(tuning.apply(base, tuning.level("wear"), 1f), "digital.scanlines"), 1e-6f)
        // a clean look gains lines above the midpoint, and never exceeds the registry maximum
        val clean = uniforms("digicam-03")
        assertEquals(0f, f(clean, "digital.scanlines"), 0f)
        assertTrue(f(tuning.apply(clean, tuning.level("wear"), 1f), "digital.scanlines") > 0.3f)
        for ((k, v) in tuning.apply(clean, tuning.level("wear"), 1f)) for (x in v) assertTrue("$k=$x", x <= registry[k]!!.max + 1e-6f)
    }

    @Test fun veryClearDoesNotRemoveLines() {
        val base = uniforms("camcorder-98")
        val out = tuning.apply(base, tuning.level("veryclear"), 0.5f)
        assertEquals(f(base, "digital.scanlines"), f(out, "digital.scanlines"), 1e-6f)
        assertEquals(f(base, "tape.jitter"), f(out, "tape.jitter"), 1e-6f)
    }

    @Test fun everyHdResolutionKeepsTheAspect() {
        for (file in TestFiles.lookFiles()) {
            val look = parser.parse(file.readText())
            val hd = look.resolution(true)
            val sd = look.resolution(false)
            assertEquals(look.id, sd.w.toFloat() / sd.h, hd.w.toFloat() / hd.h, 0.01f)
        }
    }
}
