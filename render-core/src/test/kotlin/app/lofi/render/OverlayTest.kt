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
        val needed = "0123456789:./- ABCDEFGHIJKLMNOPQRSTUVWXYZ"
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
        assertTrue(on.all { it == 0 || it == OverlayPainter.WHITE || it == OverlayPainter.RED || it == OverlayPainter.BLACK })
    }

    @Test fun nightshotHasTopRightLabelAndNoRedDot() {
        val px = paint(OverlayStyle.NIGHTSHOT, true)
        assertEquals(0, px.count { it == OverlayPainter.RED })
        val w = 480
        // label sits in the top-right quarter, tally dot (white) top-left
        val topRight = (0 until 60).sumOf { y -> (240 until w).count { x -> px[y * w + x] == OverlayPainter.WHITE } }
        val topLeft = (0 until 60).sumOf { y -> (0 until 40).count { x -> px[y * w + x] == OverlayPainter.WHITE } }
        assertTrue("label pixels: $topRight", topRight > 150)
        assertTrue("dot pixels: $topLeft", topLeft > 50)
    }

    @Test fun textIsDrawnWithADarkShadow() {
        assertTrue(paint(OverlayStyle.CAMCORDER, true).count { it == OverlayPainter.BLACK } > 100)
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
        assertEquals("OCT 12 2026" to "10:57:03 PM", OverlayText.format(OverlayStyle.NIGHTSHOT, ms, ZoneOffset.UTC))
    }
}
