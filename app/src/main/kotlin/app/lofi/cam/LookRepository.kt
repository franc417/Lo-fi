package app.lofi.cam

import android.content.res.AssetManager
import app.lofi.render.Look
import app.lofi.render.LookParseException
import app.lofi.render.LookParser
import app.lofi.render.LofiLog

/** Loads every JSON file in the bundled `looks` asset folder, sorted by file name. Invalid looks are logged and skipped. */
class LookRepository(private val assets: AssetManager, private val parser: LookParser) {
    fun loadAll(): List<Look> {
        val names = assets.list("looks")?.filter { it.endsWith(".json") }?.sorted().orEmpty()
        return names.mapNotNull { name ->
            try {
                parser.parse(assets.open("looks/$name").bufferedReader().use { it.readText() })
            } catch (e: LookParseException) {
                LofiLog.e("looks", "skipping $name: ${e.message}")
                null
            }
        }
    }
}
