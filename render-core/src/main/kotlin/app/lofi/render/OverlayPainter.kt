package app.lofi.render

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Paints the burned-in HUD (REC dot, camera name, date/time) into an ARGB pixel array at virtual resolution. */
object OverlayPainter {
    const val WHITE: Int = 0xFFEFEFEF.toInt()
    const val RED: Int = 0xFFFF2D3D.toInt()
    const val BLACK: Int = 0xFF000000.toInt()

    private val DOT = listOf(".XXX.", "XXXXX", "XXXXX", "XXXXX", ".XXX.")

    /** Mirrors paint_overlay() in tools/golden/render_golden.py: keep the two in sync. */
    fun paint(
        font: PixelFont,
        style: OverlayStyle,
        w: Int,
        h: Int,
        date: String,
        time: String,
        recOn: Boolean,
        out: IntArray,
    ) {
        require(out.size >= w * h) { "pixel buffer too small" }
        java.util.Arrays.fill(out, 0, w * h, 0)
        if (style == OverlayStyle.NONE) return

        val px = maxOf(2, Math.round(minOf(w, h) / 90f))
        val margin = 3 * px

        fun rect(x: Int, y: Int, rw: Int, rh: Int, color: Int) {
            for (yy in maxOf(y, 0) until minOf(y + rh, h)) {
                for (xx in maxOf(x, 0) until minOf(x + rw, w)) out[yy * w + xx] = color
            }
        }

        fun glyphs(s: String, x: Int, y: Int, color: Int) {
            for ((i, ch) in s.withIndex()) {
                val rows = font.glyph(ch) ?: continue
                val gx = x + i * (font.cellW + 1) * px
                for ((r, row) in rows.withIndex()) {
                    for ((c, v) in row.withIndex()) {
                        if (v == 'X') rect(gx + c * px, y + r * px, px, px, color)
                    }
                }
            }
        }

        // Viewfinder text gets a one-cell dark drop shadow so it stays readable on bright pictures.
        fun text(s: String, x: Int, y: Int, color: Int) {
            glyphs(s, x + px, y + px, BLACK)
            glyphs(s, x, y, color)
        }

        when (style) {
            OverlayStyle.CAMCORDER, OverlayStyle.NIGHTSHOT, OverlayStyle.HANDYCAM -> {
                if (recOn) {
                    // a mono (night-vision) viewfinder shows the tally lamp bright, not red
                    val dotColor = if (style == OverlayStyle.NIGHTSHOT) WHITE else RED
                    for ((r, row) in DOT.withIndex()) {
                        for ((c, v) in row.withIndex()) {
                            if (v == 'X') rect(margin + c * px, margin + (r + 1) * px, px, px, dotColor)
                        }
                    }
                }
                text("REC", margin + 7 * px, margin, WHITE)
                if (style == OverlayStyle.NIGHTSHOT) {
                    val label = "NIGHTSHOT"
                    text(label, w - margin - (label.length * (font.cellW + 1) * px - px), margin, WHITE)
                }
                text(time, margin, h - margin - 7 * px, WHITE)
                text(date, margin, h - margin - 16 * px, WHITE)
            }
            OverlayStyle.CCTV -> {
                text("CAM 01", margin, margin, WHITE)
                text("$date $time", margin, h - margin - 7 * px, WHITE)
            }
            OverlayStyle.NONE -> Unit
        }
    }
}

object OverlayText {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val DATE_DOTS = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val DATE_SLASHES = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val DATE_NIGHT = DateTimeFormatter.ofPattern("MMM d yyyy", Locale.US)
    private val TIME_NIGHT = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US)

    /** Returns (date, time) text for the style. */
    fun format(style: OverlayStyle, epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<String, String> {
        val t = Instant.ofEpochMilli(epochMillis).atZone(zone)
        if (style == OverlayStyle.NIGHTSHOT || style == OverlayStyle.HANDYCAM) {
            return DATE_NIGHT.format(t).uppercase(Locale.US) to TIME_NIGHT.format(t).uppercase(Locale.US)
        }
        val date = if (style == OverlayStyle.CCTV) DATE_SLASHES.format(t) else DATE_DOTS.format(t)
        return date to TIME.format(t)
    }
}
