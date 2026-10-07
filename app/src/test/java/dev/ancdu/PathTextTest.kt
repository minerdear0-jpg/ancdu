package dev.ancdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Строка пути шапки: многоточие в начале целыми сегментами; заголовок корня. */
class PathTextTest {
    private val byCodePoint: (String) -> Float = { it.codePointCount(0, it.length).toFloat() }
    private val wa = "/storage/emulated/0/WhatsApp/Media/WhatsApp Video"

    @Test fun segments() {
        assertEquals(listOf("storage", "emulated", "0"), PathText.segments("/storage/emulated/0"))
        assertEquals(emptyList<String>(), PathText.segments("/"))
        assertEquals(listOf("a", "b"), PathText.segments("//a//b/"))
    }

    @Test fun fitsWhole() {
        val f = PathText.fit(wa, 100f, byCodePoint)
        assertEquals("/storage/emulated/0/WhatsApp/Media/", f.head)
        assertEquals("WhatsApp Video", f.last)
        assertEquals(wa, f.text)
    }

    @Test fun dropsWholeLeadingSegments() {
        // «…/WhatsApp/Media/WhatsApp Video» — 31 кодовая точка
        val f = PathText.fit(wa, 31f, byCodePoint)
        assertEquals("…/WhatsApp/Media/", f.head)
        assertEquals("WhatsApp Video", f.last)
        // на одну меньше — уходит ещё один сегмент целиком, не часть
        val g = PathText.fit(wa, 30f, byCodePoint)
        assertEquals("…/Media/", g.head)
        assertEquals("…/WhatsApp Video", PathText.fit(wa, 16f, byCodePoint).text)
        for (w in 16..60) {
            val t = PathText.fit(wa, w.toFloat(), byCodePoint)
            assertTrue("w=$w: ${t.text}", byCodePoint(t.text) <= w)
            assertEquals("w=$w", "WhatsApp Video", t.last)
        }
    }

    @Test fun lastSegmentMiddleEllipsized() {
        val f = PathText.fit(wa, 10f, byCodePoint)
        assertEquals("…/", f.head)
        assertEquals(8f, byCodePoint(f.last))
        assertTrue(f.last, f.last.startsWith("What") && f.last.endsWith("deo") && f.last.contains('…'))
        // один сегмент: «/» не заменяется на «…/»
        val one = PathText.fit("/averyveryverylongname", 10f, byCodePoint)
        assertEquals("/", one.head)
        assertEquals(9f, byCodePoint(one.last))
    }

    @Test fun rootPath() {
        val f = PathText.fit("/", 10f, byCodePoint)
        assertEquals("/", f.text)
        assertEquals("/", f.last)
    }

    @Test fun rootTitle() {
        val internal = XmlTxt.EN.s(R.string.internal_storage)
        assertEquals("Internal storage", internal)
        assertEquals("Внутренняя память", XmlTxt.RU.s(R.string.internal_storage))
        assertEquals(internal, PathText.rootTitle("/storage/emulated/0", internal))
        assertEquals(internal, PathText.rootTitle("/storage/emulated/10/", internal))
        assertEquals("/", PathText.rootTitle("/", internal))
        assertEquals("/", PathText.rootTitle("", internal))
        assertEquals("DCIM", PathText.rootTitle("/storage/emulated/0/DCIM", internal))
        assertEquals("emulated", PathText.rootTitle("/storage/emulated", internal))
        assertEquals("cache", PathText.rootTitle("/data/user/0/dev.ancdu/cache", internal))
    }
}
