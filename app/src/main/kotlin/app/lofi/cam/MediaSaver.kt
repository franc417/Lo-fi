package app.lofi.cam

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import app.lofi.render.Look
import app.lofi.render.Upscale
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A video being written; [pfd] is the file the muxer writes to. */
class VideoTarget(val uri: Uri, val pfd: ParcelFileDescriptor)

/** Saves photos to Pictures/LoFiCam and videos to Movies/LoFiCam via MediaStore (no storage permission needed on API 29+). */
class MediaSaver(private val context: Context) {

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun save(bitmap: Bitmap, look: Look): Uri {
        val png = look.upscale == Upscale.NEAREST // lossless keeps hard dither pixels intact
        val name = "LoFi_${stamp()}_${look.id}" + if (png) ".png" else ".jpg"
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

    /** Creates a pending MP4 entry and opens it for writing. Finish with [closeVideo]. */
    fun openVideo(look: Look): VideoTarget {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "LoFi_${stamp()}_${look.id}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/LoFiCam")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed")
        val pfd = try {
            resolver.openFileDescriptor(uri, "rw") ?: throw IOException("no file descriptor for $uri")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return VideoTarget(uri, pfd)
    }

    /** Publishes the video if [success], otherwise deletes the partial file. */
    fun closeVideo(target: VideoTarget, success: Boolean) {
        runCatching { target.pfd.close() }
        val resolver = context.contentResolver
        if (success) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(target.uri, values, null, null)
        } else {
            runCatching { resolver.delete(target.uri, null, null) }
        }
    }

    /** Small preview of a saved photo/video for the shutter-row circle; null if it cannot be loaded. */
    fun thumbnail(uri: Uri, px: Int = 256): Bitmap? = try {
        context.contentResolver.loadThumbnail(uri, Size(px, px), null)
    } catch (e: Exception) {
        null
    }

    private class Row(val uri: Uri, val added: Long, val video: Boolean)

    /** The newest photo or video this app saved (own files need no storage permission), with its thumbnail. */
    fun latest(): Triple<Uri, Bitmap, Boolean>? {
        val rows = ArrayList<Row>()
        val sources = listOf(
            Triple(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/LoFiCam%", false),
            Triple(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies/LoFiCam%", true),
        )
        for ((collection, path, video) in sources) {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf(path),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c ->
                var n = 0
                while (c.moveToNext() && n < 3) {
                    rows.add(Row(ContentUris.withAppendedId(collection, c.getLong(0)), c.getLong(1), video))
                    n++
                }
            }
        }
        for (row in rows.sortedByDescending { it.added }) {
            val thumb = thumbnail(row.uri)
            if (thumb != null) return Triple(row.uri, thumb, row.video)
        }
        return null
    }
}
