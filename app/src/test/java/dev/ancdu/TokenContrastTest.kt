package dev.ancdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Обе палитры ([Palette.DARK], [Palette.LIGHT]): текст на своём фоне — не ниже 4.5:1, не текст
 * (контуры, заливки рядом друг с другом) — не ниже 3:1 (WCAG 2.x, относительная яркость sRGB).
 * Полупрозрачный цвет сравнивается уже наложенным на свой фон.
 */
class TokenContrastTest {
    private fun lum(c: Int): Double {
        fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(c shr 16 and 0xFF) + 0.7152 * ch(c shr 8 and 0xFF) + 0.0722 * ch(c and 0xFF)
    }

    private fun ratio(a: Int, b: Int): Double {
        val x = lum(a); val y = lum(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    /** [fg] (с альфой) поверх непрозрачного [bg]. */
    private fun over(fg: Int, bg: Int): Int {
        val a = (fg ushr 24) / 255.0
        fun mix(sh: Int) = ((fg shr sh and 0xFF) * a + (bg shr sh and 0xFF) * (1 - a) + 0.5).toInt()
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private val palettes = mapOf("DARK" to Palette.DARK, "LIGHT" to Palette.LIGHT)

    private fun check(min: Double, pairs: (Palette) -> List<Pair<String, Double>>) {
        val bad = palettes.flatMap { (n, p) -> pairs(p).filter { it.second < min }.map { "$n ${it.first}=${"%.2f".format(it.second)}" } }
        assertTrue("ниже $min:1: $bad", bad.isEmpty())
    }

    @Test fun ratioMatchesKnownValues() {
        assertTrue(ratio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()) in 20.99..21.01)
        assertTrue(ratio(Palette.DARK.text, Palette.DARK.text) in 0.99..1.01)
        val half = over(0x80FFFFFF.toInt(), 0xFF000000.toInt())
        assertEquals(0xFF, half ushr 24)
        assertTrue((half and 0xFF) in 128..129)
    }

    @Test fun textTokensOnBackgrounds() = check(4.5) { p ->
        val texts = mapOf("TEXT" to p.text, "MUTED" to p.muted, "AMBER_TEXT" to p.amberText,
            "BLUE_HI" to p.blueHi, "DANGER_TEXT" to p.dangerText)
        val grounds = mapOf("BG" to p.bg, "PANEL" to p.panel, "PANEL2" to p.panel2)
        texts.flatMap { (tn, t) -> grounds.map { (gn, g) -> "$tn/$gn" to ratio(t, g) } } + listOf(
            "INK/AMBER" to ratio(p.ink, p.amber),
            "WHITE/DANGER_FILL" to ratio(C.WHITE, p.dangerFill),
            "WHITE/DANGER_PRESSED" to ratio(C.WHITE, p.dangerPressed),
            "OK/OK_BG" to ratio(p.ok, p.okBg),
            "OK/PANEL2" to ratio(p.ok, p.panel2),
            "STAGE_TEXT/STAGE_SCRIM" to ratio(p.stageText, over(p.stageScrim, 0xFF000000.toInt())),
            "STAGE_TEXT/STAGE_SCRIM@white" to ratio(p.stageText, over(p.stageScrim, 0xFFFFFFFF.toInt())))
    }

    @Test fun nonTextTokens() = check(3.0) { p ->
        listOf(
            "FREE/AMBER" to ratio(over(p.free, p.panel), p.amber),
            "FRAME/BG" to ratio(p.frame, p.bg),
            "FRAME/PANEL" to ratio(p.frame, p.panel),
            "AMBER/BG" to ratio(p.amber, p.bg),
            "AMBER/PANEL" to ratio(p.amber, p.panel),
            "BLUE/BG" to ratio(p.blue, p.bg),
            "BLUE/PANEL" to ratio(p.blue, p.panel),
            // Контур фокуса «Удалить» — рядом с фоном листа.
            "FOCUS/PANEL" to ratio(p.focus, p.panel),
            "SWEEP/PANEL" to ratio(p.sweepLine, p.panel),
            // Амберные штрихи на нажатом / выбранном: скобка строки, контуры нажатых кнопок, край флажка.
            "AMBER_TEXT/PANEL2" to ratio(p.amberText, p.panel2))
    }

    /** Светлая палитра — своя (не копия тёмной); видео-сцена в обеих — тёмная. */
    @Test fun lightIsLightStageIsDark() {
        assertTrue(lum(Palette.LIGHT.bg) > lum(Palette.LIGHT.text))
        assertTrue(lum(Palette.DARK.bg) < lum(Palette.DARK.text))
        assertEquals(Palette.DARK.stageScrim, Palette.LIGHT.stageScrim)
        assertEquals(Palette.DARK.stageText, Palette.LIGHT.stageText)
        assertTrue(Palette.LIGHT.free ushr 24 == 0xFF)
        assertEquals(Palette.DARK, Palette.of(night = true))
        assertEquals(Palette.LIGHT, Palette.of(night = false))
    }
}
