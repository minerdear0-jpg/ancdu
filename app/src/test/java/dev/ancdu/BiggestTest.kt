package dev.ancdu

import dev.ancdu.XmlTxt.Companion.EN
import dev.ancdu.XmlTxt.Companion.RU
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Extra «focus» браузера: путь файла байтами имён от корня. */
class FocusTest {
    private fun b(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test fun roundTrip() {
        val names = listOf(b("DCIM"), b("Camera"), b("v.mp4"))
        val back = Focus.parse(Focus.encode(names))!!
        assertEquals(3, back.size)
        for (i in names.indices) assertArrayEquals(names[i], back[i])
    }

    @Test fun invalidUtf8AndEmojiKeptAsBytes() {
        val raw = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), '.'.code.toByte(), 'b'.code.toByte())
        val names = listOf(b("🎉 dir"), raw)
        val back = Focus.parse(Focus.encode(names))!!
        assertArrayEquals(names[0], back[0])
        assertArrayEquals(raw, back[1])
    }

    @Test fun rejectsBadInput() {
        assertNull(Focus.parse(null))
        assertNull(Focus.parse(ByteArray(0)))
        assertNull(Focus.parse(b("a\u0000\u0000b")))          // пустой компонент
        assertNull(Focus.parse(b("a\u0000")))                  // пустой в конце
        assertNull(Focus.parse(b("\u0000a")))                  // пустой в начале
        assertNull(Focus.parse(b("a\u0000..\u0000b")))
        assertNull(Focus.parse(b(".")))
        assertNull(Focus.parse(b("a/b")))                      // «/» внутри имени
        assertNull(Focus.parse(ByteArray(Focus.MAX_BYTES + 1) { 'a'.code.toByte() }))
        assertEquals(1, Focus.parse(b("...") )!!.size)         // «...» — обычное имя
    }
}

/** Строки «крупнейших файлов» главного экрана. */
class BiggestTest {
    @Test fun parentRelativeToTreeRoot() {
        val r = "/storage/emulated/0"
        assertEquals("Download/", Biggest.relParent(r, "$r/Download"))
        assertEquals("DCIM/Camera/", Biggest.relParent(r, "$r/DCIM/Camera"))
        assertEquals("", Biggest.relParent(r, r))
        assertEquals("Download/", Biggest.relParent("$r/", "$r/Download"))
        assertEquals("data/x/", Biggest.relParent("/", "/data/x"))
        assertEquals("", Biggest.relParent("/", "/"))
        // не под корнем (не бывает, но не падает): полный путь
        assertEquals("/other/d/", Biggest.relParent(r, "/other/d"))
        // «/storage/emulated/00» — не потомок «/storage/emulated/0»
        assertEquals("/storage/emulated/00/", Biggest.relParent(r, "/storage/emulated/00"))
    }

    @Test fun parentShownForRootLevel() {
        assertEquals("Download/", Biggest.parentText(EN, "/storage/emulated/0", "/storage/emulated/0/Download"))
        assertEquals("Internal storage", Biggest.parentText(EN, "/storage/emulated/0", "/storage/emulated/0"))
        assertEquals("Внутренняя память", Biggest.parentText(RU, "/storage/emulated/0", "/storage/emulated/0"))
    }

    @Test fun descriptions() {
        val n = Fmt.NBSP
        assertEquals("big.iso, 1.5${n}GiB, in Download/",
            Biggest.desc(EN, "big.iso", Fmt.size(1_610_612_736, EN), "Download/", null))
        assertEquals("big.iso, 1,5${n}ГиБ, в Download/",
            Biggest.desc(RU, "big.iso", Fmt.size(1_610_612_736, RU), "Download/", null))
        assertEquals("a.zip, 10${n}B, in Download/, downloads",
            Biggest.desc(EN, "a.zip", Fmt.size(10, EN), "Download/", Tag(TagKind.DL)))
        assertEquals("a.zip, 10${n}Б, в Download/, загрузки",
            Biggest.desc(RU, "a.zip", Fmt.size(10, RU), "Download/", Tag(TagKind.DL)))
    }

    @Test fun captionInBothLanguages() {
        assertEquals("Biggest files", EN.s(R.string.big_title))
        assertEquals("Самые крупные файлы", RU.s(R.string.big_title))
    }
}
