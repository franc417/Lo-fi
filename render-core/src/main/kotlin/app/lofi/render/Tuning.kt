package app.lofi.render

import org.json.JSONObject

/** One "how clear should the footage be" level. [factors] scale noise/blur/smear params; [hd] selects the look's HD resolution. */
class ClarityLevel(
    val id: String,
    val label: String,
    val hd: Boolean,
    val audio: AudioProfile,
    val factors: Map<String, Float>,
)

/**
 * Global, look-independent adjustments applied on top of a look's resolved uniforms:
 *  - clarity (WEAR / CLEAR / HD / VERY CLEAR): scales grain, softness, tape smear etc. but never the params a look
 *    lists in `keep` (its intended effect) nor anything outside the factor list (colour, palette, dither, night vision...).
 *  - signal lines (0..1, 0.5 = the look's own): scales scanlines / interlace / jitter / head-switch / dropouts, and above
 *    0.5 also adds a floor so even clean looks can get lines.
 * Mirrors apply_tuning() in tools/golden/render_golden.py.
 */
class Tuning(
    val clarity: List<ClarityLevel>,
    private val lineFloors: Map<String, Float>,
    private val registry: ParamRegistry,
) {
    fun level(id: String): ClarityLevel = clarity.firstOrNull { it.id == id } ?: clarity.first()

    fun apply(
        uniforms: Map<String, FloatArray>,
        level: ClarityLevel,
        lines: Float,
        keep: Set<String> = emptySet(),
    ): Map<String, FloatArray> {
        val out = HashMap<String, FloatArray>(uniforms.size)
        for ((k, v) in uniforms) out[k] = v.copyOf()
        for ((path, f) in level.factors) {
            if (path in keep) continue
            val v = out[path] ?: continue
            out[path] = FloatArray(v.size) { v[it] * f }
        }
        val l = lines.coerceIn(0f, 1f)
        val mult = if (l <= 0.5f) l * 2f else 1f + (l - 0.5f) * 2f
        val add = maxOf(0f, l - 0.5f) * 2f
        for ((path, floor) in lineFloors) {
            val v = out[path] ?: continue
            val max = registry[path]!!.max
            out[path] = FloatArray(v.size) { minOf(v[it] * mult + floor * add, max) }
        }
        return out
    }

    companion object {
        fun fromJson(json: String, registry: ParamRegistry): Tuning {
            val root = JSONObject(json)
            val levels = root.getJSONArray("clarity")
            val clarity = ArrayList<ClarityLevel>()
            for (i in 0 until levels.length()) {
                val o = levels.getJSONObject(i)
                val fo = o.getJSONObject("factors")
                val factors = LinkedHashMap<String, Float>()
                for (path in fo.keyList()) {
                    require(registry[path] != null) { "clarity '${o.getString("id")}': unknown param '$path'" }
                    factors[path] = fo.getDouble(path).toFloat()
                }
                clarity.add(ClarityLevel(o.getString("id"), o.getString("label"), o.getBoolean("hd"), AudioProfile.fromId(o.getString("audio")), factors))
            }
            require(clarity.isNotEmpty()) { "no clarity levels" }
            val floors = LinkedHashMap<String, Float>()
            val fl = root.getJSONObject("lines").getJSONObject("floor")
            for (path in fl.keyList()) {
                require(registry[path] != null) { "lines: unknown param '$path'" }
                floors[path] = fl.getDouble(path).toFloat()
            }
            return Tuning(clarity, floors, registry)
        }
    }
}
