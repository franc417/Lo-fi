package app.lofi.render

import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Logging facade: logcat plus a size-capped rolling JSON-lines file (`lofi-log.jsonl`) that can be exported
 * from the app. Safe to call before [init] (it then only logs to logcat).
 */
object LofiLog {
    private const val MAX_BYTES = 512 * 1024L

    @Volatile private var file: File? = null
    private val executor by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "lofi-log").apply { isDaemon = true } }
    }

    fun init(dir: File) {
        dir.mkdirs()
        file = File(dir, "lofi-log.jsonl")
    }

    fun logFile(): File? = file

    fun d(tag: String, msg: String) = log("D", tag, msg, null)
    fun i(tag: String, msg: String) = log("I", tag, msg, null)
    fun w(tag: String, msg: String) = log("W", tag, msg, null)
    fun e(tag: String, msg: String, t: Throwable? = null) = log("E", tag, msg, t)

    private fun log(level: String, tag: String, msg: String, t: Throwable?) {
        when (level) {
            "D" -> Log.d("LoFi/$tag", msg)
            "I" -> Log.i("LoFi/$tag", msg)
            "W" -> Log.w("LoFi/$tag", msg)
            else -> Log.e("LoFi/$tag", msg, t)
        }
        val f = file ?: return
        val line = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("lvl", level)
            .put("tag", tag)
            .put("msg", msg)
            .apply { if (t != null) put("err", Log.getStackTraceString(t)) }
            .toString()
        executor.execute {
            try {
                if (f.exists() && f.length() > MAX_BYTES) f.renameTo(File(f.parentFile, f.name + ".1"))
                f.appendText(line + "\n")
            } catch (_: Exception) {
                // logging must never crash the app
            }
        }
    }
}
