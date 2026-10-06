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
}
