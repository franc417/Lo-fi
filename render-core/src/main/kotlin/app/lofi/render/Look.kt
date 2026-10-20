package app.lofi.render

data class VirtualResolution(val w: Int, val h: Int)

enum class Upscale { NEAREST, LINEAR }

enum class OverlayStyle { NONE, CAMCORDER, CCTV, NIGHTSHOT, HANDYCAM }

enum class Curve {
    LINEAR, QUAD;

    fun apply(t: Float): Float = if (this == QUAD) t * t else t
}

class MacroTarget(val path: String, val from: FloatArray, val to: FloatArray, val curve: Curve)

/** A 0..1 control (for example "Wear") that drives several parameters at once. */
class Macro(val id: String, val label: String, val default: Float, val targets: List<MacroTarget>)

/** A parsed, validated look. [baseParams] already contains every registry parameter (defaults filled in). */
class Look(
    val id: String,
    val name: String,
    val virtualResolution: VirtualResolution,
    val upscale: Upscale,
    val exportScale: Int,
    val overlay: OverlayStyle,
    val baseParams: Map<String, FloatArray>,
    val macros: List<Macro>,
    /** Optional higher internal resolution used by the HD / VERY CLEAR clarity levels (same aspect ratio). */
    val hdResolution: VirtualResolution? = null,
    /** Params that clarity levels must not touch because they ARE this mode's intended effect. */
    val keep: Set<String> = emptySet(),
) {
    fun resolution(hd: Boolean): VirtualResolution = if (hd) hdResolution ?: virtualResolution else virtualResolution

    fun macro(id: String): Macro? = macros.firstOrNull { it.id == id }

    fun defaultMacroValues(): Map<String, Float> = macros.associate { it.id to it.default }

    /** Final uniform values: base params with every macro applied on top. Missing macro values use their default. */
    fun resolve(macroValues: Map<String, Float> = emptyMap()): Map<String, FloatArray> {
        val out = HashMap<String, FloatArray>(baseParams.size)
        for ((k, v) in baseParams) out[k] = v.copyOf()
        for (m in macros) {
            val t = (macroValues[m.id] ?: m.default).coerceIn(0f, 1f)
            for (tg in m.targets) {
                val k = tg.curve.apply(t)
                out[tg.path] = FloatArray(tg.from.size) { i -> tg.from[i] + (tg.to[i] - tg.from[i]) * k }
            }
        }
        return out
    }
}
