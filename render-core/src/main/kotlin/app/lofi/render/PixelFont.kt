package app.lofi.render

import org.json.JSONObject

/** 5x7 bitmap font loaded from assets/font/pixelfont.json (shared with the Python harness). */
class PixelFont(val cellW: Int, val cellH: Int, private val glyphs: Map<Char, List<String>>) {

    fun glyph(ch: Char): List<String>? = glyphs[ch]

    val chars: Set<Char> get() = glyphs.keys

    companion object {
        fun fromJson(json: String): PixelFont {
            val root = JSONObject(json)
            val cellW = root.getInt("cellW")
            val cellH = root.getInt("cellH")
            val go = root.getJSONObject("glyphs")
            val map = HashMap<Char, List<String>>()
            for (k in go.keyList()) {
                require(k.length == 1) { "glyph key '$k' must be a single character" }
                val arr = go.getJSONArray(k)
                require(arr.length() == cellH) { "glyph '$k' needs $cellH rows" }
                val rows = List(arr.length()) { arr.getString(it) }
                require(rows.all { it.length == cellW }) { "glyph '$k' rows must be $cellW wide" }
                map[k[0]] = rows
            }
            return PixelFont(cellW, cellH, map)
        }
    }
}
