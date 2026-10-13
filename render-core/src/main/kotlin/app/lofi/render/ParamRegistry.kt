package app.lofi.render

import org.json.JSONArray
import org.json.JSONObject

enum class ParamType(val size: Int) { FLOAT(1), VEC3(3) }

/** One tweakable look parameter. [path] like `lens.softness` maps to GLSL uniform `uLensSoftness`. */
class ParamSpec(
    val path: String,
    val type: ParamType,
    val default: FloatArray,
    val min: Float,
    val max: Float,
) {
    val uniform: String = uniformName(path)
}

fun uniformName(path: String): String =
    "u" + path.split('.').joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }

/** Parsed from assets/params.json, the single source of truth shared with the shaders and the Python harness. */
class ParamRegistry(val all: List<ParamSpec>) {
    private val byPath: Map<String, ParamSpec> = all.associateBy { it.path }

    operator fun get(path: String): ParamSpec? = byPath[path]

    fun defaults(): Map<String, FloatArray> = all.associate { it.path to it.default.copyOf() }

    companion object {
        fun fromJson(json: String): ParamRegistry {
            val arr = JSONObject(json).getJSONArray("params")
            val list = ArrayList<ParamSpec>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val type = when (val t = o.getString("type")) {
                    "float" -> ParamType.FLOAT
                    "vec3" -> ParamType.VEC3
                    else -> throw IllegalArgumentException("unknown param type '$t'")
                }
                list.add(
                    ParamSpec(
                        path = o.getString("path"),
                        type = type,
                        default = readFloats(o.get("default"), type.size),
                        min = o.getDouble("min").toFloat(),
                        max = o.getDouble("max").toFloat(),
                    ),
                )
            }
            return ParamRegistry(list)
        }
    }
}

internal fun readFloats(value: Any, size: Int): FloatArray = when (value) {
    is JSONArray -> {
        require(value.length() == size) { "expected $size numbers, got ${value.length()}" }
        FloatArray(size) { value.getDouble(it).toFloat() }
    }
    is Number -> {
        require(size == 1) { "expected an array of $size numbers, got a single number" }
        floatArrayOf(value.toFloat())
    }
    else -> throw IllegalArgumentException("expected a number or an array of numbers, got $value")
}

/** Keys of a JSON object as a typed list (works on every org.json variant, unlike keys()). */
internal fun JSONObject.keyList(): List<String> {
    val n = names() ?: return emptyList()
    return List(n.length()) { n.getString(it) }
}
