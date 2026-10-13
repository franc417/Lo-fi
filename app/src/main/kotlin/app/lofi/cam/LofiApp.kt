package app.lofi.cam

import android.app.Application
import android.os.Build
import app.lofi.render.LofiLog
import java.io.File

class LofiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LofiLog.init(File(filesDir, "logs"))
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            LofiLog.e("crash", "uncaught exception on '${thread.name}'", error)
            Thread.sleep(250) // give the async log writer a moment to flush
            previous?.uncaughtException(thread, error)
        }
        LofiLog.i("app", "start: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    }
}
