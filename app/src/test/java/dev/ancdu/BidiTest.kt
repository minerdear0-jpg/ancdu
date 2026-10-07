package dev.ancdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Управляющие символы направления текста: видимыми в листах и строках, вырезанными в превью. */
class BidiTest {
    @Test fun visibleForm() {
        // «photo‮gpj.exe» выглядел бы как «photoexe.jpg»
        assertEquals("photo⟨U+202E⟩gpj.exe", Bidi.visible("photo‮gpj.exe"))
        val all = (0x202A..0x202E) + (0x2066..0x2069)
        for (cp in all) assertEquals("a⟨U+%04X⟩b".format(cp), Bidi.visible("a" + cp.toChar() + "b"))
        assertEquals("⟨U+2066⟩⟨U+2069⟩", Bidi.visible("⁦⁩"))
    }

    @Test fun neighboursUntouched() {
        // U+2029, U+202F, U+2065, U+206A и обычный текст — не управляющие направления
        val s = "x  ⁪😀ё"
        assertSame(s, Bidi.visible(s))
        assertSame(s, Bidi.strip(s))
    }

    @Test fun strip() {
        assertEquals("photogpj.exe", Bidi.strip("photo‮gpj.exe"))
        assertEquals("ab", Bidi.strip("‪a⁨b⁩"))
    }
}
