package app.lofi.render

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayTest {
    private val font = TestFiles.font()

    private fun paint(style: OverlayStyle, rec: Boolean, w: Int = 480, h: Int = 360): IntArray {
        val out = IntArray(w * h) { 0x7F7F7F7F }  // dirty buffer: painter must clear it
        OverlayPainter.paint(font, style, w, h, "12.10.2026", "22:57:03", rec, out)
        return out
    }

    @Test fun glyphsAreFiveBySevenAndCoverAllOverlayCharacters() {
        for (c in font.chars) {
            val g = font.glyph(c)!!
            assertEquals("glyph '$c'", 7, g.size)
            assertTrue("glyph '$c'", g.all { it.length == 5 })
        }
        val needed = "0123456789:./- REC PLAYSM"
        for (c in needed) assertTrue("missing glyph '$c'", c in font.chars)
    }

    @Test fun noneStyleIsFullyTransparent() {
        assertTrue(paint(OverlayStyle.NONE, true).all { it == 0 })
    }

    @Test fun camcorderDrawsTextAndTheRecDotOnlyWhenOn() {
        val on = paint(OverlayStyle.CAMCORDER, true)
        val off = paint(OverlayStyle.CAMCORDER, false)
        assertTrue(on.count { it == OverlayPainter.RED } > 50)
        assertEquals(0, off.count { it == OverlayPainter.RED })
        assertTrue(off.count { it == OverlayPainter.WHITE } > 200)
        assertTrue(on.all { it == 0 || it == OverlayPainter.WHITE || it == OverlayPainter.RED })
    }

    @Test fun cctvDrawsWhiteTextOnly() {
        val px = paint(OverlayStyle.CCTV, true)
        assertTrue(px.count { it == OverlayPainter.WHITE } > 200)
        assertEquals(0, px.count { it == OverlayPainter.RED })
    }

    @Test fun overlayStaysInsideSmallAndPortraitBuffers() {
        paint(OverlayStyle.CAMCORDER, true, 360, 480)
        paint(OverlayStyle.CCTV, true, 264, 352)
        paint(OverlayStyle.CAMCORDER, true, 64, 64)  // must not throw even when text overflows
    }

    @Test fun textFormatsMatchTheHarness() {
        val ms = Instant.parse("2026-10-12T22:57:03Z").toEpochMilli()
        assertEquals("12.10.2026" to "22:57:03", OverlayText.format(OverlayStyle.CAMCORDER, ms, ZoneOffset.UTC))
        assertEquals("12/10/2026" to "22:57:03", OverlayText.format(OverlayStyle.CCTV, ms, ZoneOffset.UTC))
    }
}
