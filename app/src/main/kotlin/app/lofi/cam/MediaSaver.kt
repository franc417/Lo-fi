package app.lofi.cam

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
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

    /** Small preview of a saved photo for the shutter-row circle; null if it cannot be loaded. */
    fun thumbnail(uri: Uri, px: Int = 256): Bitmap? = try {
        context.contentResolver.loadThumbnail(uri, Size(px, px), null)
    } catch (e: Exception) {
        null
    }

    /** The newest photo this app saved (own files need no storage permission), with its thumbnail. */
    fun latest(): Pair<Uri, Bitmap>? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("Pictures/LoFiCam%"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, c.getLong(0))
                val thumb = thumbnail(uri)
                if (thumb != null) return uri to thumb
            }
        }
        return null
    }
}
