package app.lofi.cam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import app.lofi.render.Look
import app.lofi.render.Upscale
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves a captured bitmap to Pictures/LoFiCam via MediaStore (no storage permission needed on API 29+). */
class MediaSaver(private val context: Context) {
    fun save(bitmap: Bitmap, look: Look): Uri {
        val png = look.upscale == Upscale.NEAREST // lossless keeps hard dither pixels intact
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "LoFi_${stamp}_${look.id}" + if (png) ".png" else ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (png) "image/png" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LoFiCam")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed")
        try {
            val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val stream = resolver.openOutputStream(uri) ?: throw IOException("no output stream for $uri")
            stream.use { if (!bitmap.compress(format, 92, it)) throw IOException("bitmap compress failed") }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }
}
