package app.lofi.render

import org.json.JSONException
import org.json.JSONObject

class LookParseException(message: String) : Exception(message)

/** Validates look JSON against the [ParamRegistry]. Looks are data only: they can never carry shader code. */
class LookParser(private val registry: ParamRegistry) {

    fun parse(json: String): Look {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw LookParseException("invalid JSON: ${e.message}")
        }
        try {
            return parseObject(root)
        } catch (e: JSONException) {
            throw LookParseException("look '${root.optString("id", "?")}': malformed value (${e.message})")
        }
    }

    private fun parseObject(root: JSONObject): Look {
        val id = root.optString("id", "")
        fun fail(msg: String): Nothing = throw LookParseException("look '${id.ifEmpty { "?" }}': $msg")

        if (root.optInt("schema", -1) != 1) fail("unsupported or missing 'schema' (expected 1)")
        if (!ID_REGEX.matches(id)) fail("'id' must be lowercase letters, digits or '-'")
        val name = root.optString("name", "")
        if (name.isBlank()) fail("missing 'name'")

        val vr = root.optJSONObject("virtualResolution") ?: fail("missing 'virtualResolution'")
        val w = vr.optInt("w", 0)
        val h = vr.optInt("h", 0)
        if (w !in 64..1920 || h !in 64..1920) fail("virtualResolution must be 64..1920 px, got ${w}x$h")

        val upscale = when (root.optString("upscale", "")) {
            "nearest" -> Upscale.NEAREST
            "linear" -> Upscale.LINEAR
            else -> fail("'upscale' must be 'nearest' or 'linear'")
        }
        val exportScale = root.optInt("exportScale", 1)
        if (exportScale !in 1..4) fail("'exportScale' must be 1..4, got $exportScale")

        val overlay = when (val s = root.optJSONObject("overlay")?.optString("style", "none") ?: "none") {
            "none" -> OverlayStyle.NONE
            "camcorder" -> OverlayStyle.CAMCORDER
            "cctv" -> OverlayStyle.CCTV
            else -> fail("unknown overlay style '$s'")
        }

        val params = registry.defaults().toMutableMap()
        root.optJSONObject("params")?.let { po ->
            for (key in po.keyList()) {
                val spec = registry[key] ?: fail("unknown param '$key'")
                val v = try {
                    readFloats(po.get(key), spec.type.size)
                } catch (e: IllegalArgumentException) {
                    fail("param '$key': ${e.message}")
                }
                checkRange(spec, v, "param '$key'", ::fail)
                params[key] = v
            }
        }

        val macros = ArrayList<Macro>()
        val seen = HashSet<String>()
        val ma = root.optJSONArray("macros")
        if (ma != null) {
            for (i in 0 until ma.length()) {
                val mo = ma.optJSONObject(i) ?: fail("macro #$i is not an object")
                val mid = mo.optString("id", "")
                if (mid.isEmpty() || !seen.add(mid)) fail("macro #$i has a missing or duplicate 'id'")
                val def = mo.optDouble("default", 0.5).toFloat()
                if (def !in 0f..1f) fail("macro '$mid': 'default' must be 0..1")
                val ta = mo.optJSONArray("targets") ?: fail("macro '$mid' needs 'targets'")
                val targets = ArrayList<MacroTarget>()
                for (j in 0 until ta.length()) {
                    val to = ta.optJSONObject(j) ?: fail("macro '$mid' target #$j is not an object")
                    val path = to.optString("path", "")
                    val spec = registry[path] ?: fail("macro '$mid': unknown param '$path'")
                    fun read(key: String): FloatArray = try {
                        readFloats(to.get(key), spec.type.size)
                    } catch (e: Exception) {
                        fail("macro '$mid' target '$path' '$key': ${e.message}")
                    }
                    val from = read("from")
                    val dest = read("to")
                    checkRange(spec, from, "macro '$mid' target '$path' 'from'", ::fail)
                    checkRange(spec, dest, "macro '$mid' target '$path' 'to'", ::fail)
                    val curve = when (val c = to.optString("curve", "linear")) {
                        "linear" -> Curve.LINEAR
                        "quad" -> Curve.QUAD
                        else -> fail("macro '$mid' target '$path': unknown curve '$c'")
                    }
                    targets.add(MacroTarget(path, from, dest, curve))
                }
                macros.add(Macro(mid, mo.optString("label", mid), def, targets))
            }
        }

        return Look(id, name, VirtualResolution(w, h), upscale, exportScale, overlay, params, macros)
    }

    private fun checkRange(spec: ParamSpec, v: FloatArray, what: String, fail: (String) -> Nothing) {
        for (x in v) {
            if (x < spec.min || x > spec.max) fail("$what: $x is outside ${spec.min}..${spec.max}")
        }
    }

    private companion object {
        val ID_REGEX = Regex("[a-z0-9-]+")
    }
}
