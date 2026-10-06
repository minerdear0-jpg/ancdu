package dev.ancdu

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** Каждый цвет текста на своём фоне — не ниже 4.5:1 (WCAG 2.x, относительная яркость sRGB). */
class TokenContrastTest {
    private fun lum(c: Int): Double {
        fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(c shr 16 and 0xFF) + 0.7152 * ch(c shr 8 and 0xFF) + 0.0722 * ch(c and 0xFF)
    }

    private fun ratio(a: Int, b: Int): Double {
        val x = lum(a); val y = lum(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    @Test fun ratioMatchesKnownValues() {
        assertTrue(ratio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()) in 20.99..21.01)
        assertTrue(ratio(C.TEXT, C.TEXT) in 0.99..1.01)
    }

    @Test fun textTokensOnBackgrounds() {
        val texts = mapOf("TEXT" to C.TEXT, "MUTED" to C.MUTED, "AMBER" to C.AMBER, "BLUE" to C.BLUE,
            "BLUE_HI" to C.BLUE_HI, "DANGER_TEXT" to C.DANGER_TEXT)
        val grounds = mapOf("BG" to C.BG, "PANEL" to C.PANEL, "PANEL2" to C.PANEL2)
        val pairs = texts.flatMap { (tn, t) -> grounds.map { (gn, g) -> "$tn/$gn" to ratio(t, g) } } +
            listOf("INK/AMBER" to ratio(C.INK, C.AMBER), "WHITE/DANGER_FILL" to ratio(C.WHITE, C.DANGER_FILL),
                "OK/OK_BG" to ratio(C.OK, C.OK_BG), "OK/PANEL2" to ratio(C.OK, C.PANEL2))
        val bad = pairs.filter { it.second < 4.5 }
        assertTrue("ниже 4.5:1: $bad", bad.isEmpty())
    }
}
