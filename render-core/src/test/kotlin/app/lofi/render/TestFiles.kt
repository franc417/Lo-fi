package app.lofi.render

import java.io.File

/** Gradle runs unit tests with the module directory as the working directory. */
object TestFiles {
    val assets: File = File("src/main/assets").also { check(it.isDirectory) { "assets not found at ${it.absolutePath}" } }
    fun text(path: String): String = File(assets, path).readText()
    fun registry(): ParamRegistry = ParamRegistry.fromJson(text("params.json"))
    fun font(): PixelFont = PixelFont.fromJson(text("font/pixelfont.json"))
    fun lookFiles(): List<File> = File(assets, "looks").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
}
