package dev.ancdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EllipsisTest {
    private val byCodePoint: (String) -> Float = { it.codePointCount(0, it.length).toFloat() }
    private val byChar: (String) -> Float = { it.length.toFloat() }

    private fun noLoneSurrogates(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return false
                i += 2
            } else {
                if (Character.isLowSurrogate(c)) return false
                i++
            }
        }
        return true
    }

    @Test fun keepsStartAndEnd() {
        val s = "com.google.android.apps.messaging"
        assertEquals(s, Ellipsis.middle(s, 100f, byCodePoint))
        val r = Ellipsis.middle(s, 18f, byCodePoint)
        assertEquals(18f, byCodePoint(r))
        assertTrue(r, r.startsWith("com.goog"))
        assertTrue(r, r.endsWith("essaging"))
        assertTrue(r.contains('…'))
        // каталог: завершающий слеш виден
        assertTrue(Ellipsis.middle("very-long-directory-name/", 12f, byCodePoint).endsWith("/"))
    }

    @Test fun tinyWidths() {
        assertEquals("…", Ellipsis.middle("abcdef", 1f, byCodePoint))
        assertEquals("", Ellipsis.middle("abcdef", 0.5f, byCodePoint))
        assertEquals("", Ellipsis.middle("", 0f, byCodePoint))
    }

    @Test fun neverSplitsSurrogatePairs() {
        val emoji = "😀😁😂🤣😃😄😅😆😉😊" // 10 кодовых точек, 20 UTF-16
        for (w in 1..20) {
            val r = Ellipsis.middle(emoji, w.toFloat(), byChar)
            assertTrue("w=$w: $r", noLoneSurrogates(r))
            assertTrue("w=$w", byChar(r) <= w)
        }
        val mixed = "a😀b😀c😀d😀e😀f😀g"
        for (w in 1..mixed.length) assertTrue(noLoneSurrogates(Ellipsis.middle(mixed, w.toFloat(), byChar)))
    }

    @Test fun replacementCharacter() {
        val s = "bad��name�.bin"
        val r = Ellipsis.middle(s, 9f, byCodePoint)
        assertEquals(9f, byCodePoint(r))
        assertTrue(r.startsWith("bad�"))
        assertTrue(r.endsWith(".bin"))
        assertFalse(r.contains("name"))
    }

    @Test fun extension() {
        assertEquals("jpg", Ellipsis.ext("IMG_2024.jpg"))
        assertEquals("gz", Ellipsis.ext("a.tar.gz"))
        assertEquals("jpeg", Ellipsis.ext("photo.jpeg"))
        assertEquals(null, Ellipsis.ext("README"))
        assertEquals(null, Ellipsis.ext(".bashrc"))           // точка в начале — не расширение
        assertEquals(null, Ellipsis.ext("archive.backup1"))   // 7 кодовых точек — слишком длинное
        assertEquals(null, Ellipsis.ext("file.a b"))          // с пробелом
        assertEquals(null, Ellipsis.ext("trailing."))
        assertEquals("😀", Ellipsis.ext("x.😀"))               // одна кодовая точка
    }

    @Test fun stemKeepsExtension() {
        val s = "VID_20240612_183000_very_long_name.mp4"
        assertEquals(s, Ellipsis.stemKeepExt(s, 100f, byCodePoint))
        assertEquals("VID_2024061….mp4", Ellipsis.stemKeepExt(s, 16f, byCodePoint))
        assertEquals("VID_20240612….mp4", Ellipsis.stemKeepExt(s, 17.5f, byCodePoint))
    }

    @Test fun stemFallsBackToEnd() {
        // нет расширения — обычное многоточие в конце
        assertEquals("README_very_lo…", Ellipsis.stemKeepExt("README_very_long_name", 15f, byCodePoint))
        // dotfile
        assertEquals(".bashrc_ex…", Ellipsis.stemKeepExt(".bashrc_extra_long", 11f, byCodePoint))
        // длинное «расширение» — не расширение
        assertEquals("archive.bac…", Ellipsis.stemKeepExt("archive.backup123", 12f, byCodePoint))
        // уже, чем расширение + 4 знака («abc…» + «.jpeg» = 9) — обычное в конце
        assertEquals("abc….jpeg", Ellipsis.stemKeepExt("abcdefghij.jpeg", 9f, byCodePoint))
        assertEquals("abcdefg…", Ellipsis.stemKeepExt("abcdefghij.jpeg", 8.5f, byCodePoint))
        assertEquals("abcdefg…", Ellipsis.stemKeepExt("abcdefghij.jpeg", 8f, byCodePoint))
        assertEquals("…", Ellipsis.stemKeepExt("abcdefghij.jpeg", 1f, byCodePoint))
        assertEquals("", Ellipsis.stemKeepExt("abcdefghij.jpeg", 0.5f, byCodePoint))
    }

    @Test fun dirKeepsSlash() {
        assertEquals("very-long-dir…/", Ellipsis.stemKeepExt("very-long-directory.name/", 15f, byCodePoint))
        assertEquals("short/", Ellipsis.stemKeepExt("short/", 6f, byCodePoint))
        for (w in 5..24) {
            val r = Ellipsis.stemKeepExt("very-long-directory.name/", w.toFloat(), byCodePoint)
            assertTrue("w=$w: $r", byCodePoint(r) <= w && r.endsWith("…/"))
        }
    }

    @Test fun stemNeverSplitsSurrogates() {
        val s = "😀😁😂🤣😃😄😅😆😉😊.png"
        for (w in 1..s.length) {
            val r = Ellipsis.stemKeepExt(s, w.toFloat(), byChar)
            assertTrue("w=$w: $r", noLoneSurrogates(r) && byChar(r) <= w)
        }
    }

    /** Многоточие посередине по произвольному условию «влезает» (две строки листа). */
    @Test fun middleFitPredicate() {
        val s = "com.google.android.apps.messaging"
        assertEquals(s, Ellipsis.middleFit(s) { true })
        val r = Ellipsis.middleFit(s) { it.length <= 18 }
        assertEquals(Ellipsis.middle(s, 18f, byChar), r)
        assertEquals("", Ellipsis.middleFit(s) { false })
    }
}
