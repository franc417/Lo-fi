package app.lofi.render

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LookParserTest {
    private val registry = TestFiles.registry()
    private val parser = LookParser(registry)

    private fun base(): JSONObject = JSONObject(
        """{"schema":1,"id":"t","name":"T","virtualResolution":{"w":480,"h":360},"upscale":"linear","exportScale":2,
            "overlay":{"style":"none"},"params":{"digital.grain":0.2,"night.color":[0.1,0.9,0.3]},
            "macros":[{"id":"wear","label":"Wear","default":0.5,"targets":[
              {"path":"digital.grain","from":0.0,"to":1.0},
              {"path":"tape.dropouts","from":0.0,"to":1.0,"curve":"quad"},
              {"path":"night.color","from":[0,0,0],"to":[1,1,1]}]}]}""",
    )

    private fun expectFailure(json: JSONObject, containing: String) {
        try {
            parser.parse(json.toString())
            fail("expected LookParseException containing '$containing'")
        } catch (e: LookParseException) {
            assertTrue("message was: ${e.message}", e.message!!.contains(containing))
        }
    }

    @Test fun everyBundledLookParses() {
        val files = TestFiles.lookFiles()
        assertTrue("no looks bundled", files.size >= 8)
        val ids = HashSet<String>()
        for (f in files) {
            val look = parser.parse(f.readText())
            assertTrue("duplicate look id ${look.id}", ids.add(look.id))
            assertEquals(registry.all.size, look.baseParams.size)
            assertTrue("${look.id} has no wear macro", look.macro("wear") != null)
            // every macro endpoint must resolve to in-range values
            for (t in listOf(0f, 0.5f, 1f)) {
                for ((path, v) in look.resolve(mapOf("wear" to t))) {
                    val spec = registry[path]!!
                    for (x in v) assertTrue("${look.id} $path=$x @wear=$t", x >= spec.min - 1e-4f && x <= spec.max + 1e-4f)
                }
            }
        }
    }

    @Test fun macroInterpolatesFloatsAndVectors() {
        val look = parser.parse(base().toString())
        val at0 = look.resolve(mapOf("wear" to 0f))
        val at1 = look.resolve(mapOf("wear" to 1f))
        val half = look.resolve(mapOf("wear" to 0.5f))
        assertEquals(0f, at0["digital.grain"]!![0], 1e-6f)
        assertEquals(1f, at1["digital.grain"]!![0], 1e-6f)
        assertEquals(0.5f, half["digital.grain"]!![0], 1e-6f)
        assertEquals(0.25f, half["tape.dropouts"]!![0], 1e-6f)   // quad curve: 0.5^2
        assertEquals(0.5f, half["night.color"]!![2], 1e-6f)
    }

    @Test fun macroValueIsClampedAndDefaultsApply() {
        val look = parser.parse(base().toString())
        assertEquals(1f, look.resolve(mapOf("wear" to 5f))["digital.grain"]!![0], 1e-6f)
        assertEquals(0.5f, look.resolve()["digital.grain"]!![0], 1e-6f)
    }

    @Test fun unsetParamsFallBackToRegistryDefaults() {
        val look = parser.parse(base().toString())
        assertEquals(1f, look.baseParams["tone.exposure"]!![0], 0f)
        assertEquals(1f, look.baseParams["digital.paletteLight"]!![1], 0f)
    }

    @Test fun rejectsUnknownParam() {
        val j = base(); j.getJSONObject("params").put("lens.warp", 1.0)
        expectFailure(j, "unknown param 'lens.warp'")
    }

    @Test fun rejectsOutOfRangeValue() {
        val j = base(); j.getJSONObject("params").put("lens.softness", 9.0)
        expectFailure(j, "outside")
    }

    @Test fun rejectsWrongVectorSize() {
        val j = base(); j.getJSONObject("params").put("night.color", JSONArray("[1,2]"))
        expectFailure(j, "night.color")
    }

    @Test fun rejectsBadSchemaIdAndUpscale() {
        expectFailure(base().put("schema", 2), "schema")
        expectFailure(base().put("id", "Bad Id"), "'id'")
        expectFailure(base().put("upscale", "cubic"), "upscale")
        expectFailure(base().put("exportScale", 9), "exportScale")
    }

    @Test fun rejectsBadResolution() {
        expectFailure(base().put("virtualResolution", JSONObject("""{"w":10,"h":360}""")), "virtualResolution")
    }

    @Test fun rejectsDuplicateMacroIdAndBadCurve() {
        val dup = base()
        dup.getJSONArray("macros").put(JSONObject("""{"id":"wear","targets":[]}"""))
        expectFailure(dup, "duplicate")
        val curve = base()
        curve.getJSONArray("macros").getJSONObject(0).getJSONArray("targets").getJSONObject(0).put("curve", "cubic")
        expectFailure(curve, "curve")
    }

    @Test fun parsesHdKeepAndHandycamOverlay() {
        val j = base()
        j.put("hd", JSONObject("""{"w":640,"h":480}""")).put("keep", JSONArray("""["tape.ghost"]""")).put("overlay", JSONObject("""{"style":"handycam"}"""))
        val look = parser.parse(j.toString())
        assertEquals(VirtualResolution(640, 480), look.resolution(true))
        assertEquals(VirtualResolution(480, 360), look.resolution(false))
        assertEquals(setOf("tape.ghost"), look.keep)
        assertEquals(OverlayStyle.HANDYCAM, look.overlay)
    }

    @Test fun withoutHdTheBaseResolutionIsUsed() {
        assertEquals(VirtualResolution(480, 360), parser.parse(base().toString()).resolution(true))
    }

    @Test fun rejectsHdWithAnotherAspectOrUnknownKeep() {
        expectFailure(base().put("hd", JSONObject("""{"w":640,"h":640}""")), "aspect")
        expectFailure(base().put("keep", JSONArray("""["lens.warp"]""")), "keep")
    }

    @Test fun rejectsInvalidJson() {
        try {
            parser.parse("{nope")
            fail("expected failure")
        } catch (e: LookParseException) {
            assertTrue(e.message!!.contains("invalid JSON"))
        }
    }
}
