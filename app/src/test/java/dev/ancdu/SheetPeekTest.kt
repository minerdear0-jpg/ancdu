package dev.ancdu

import dev.ancdu.XmlTxt.Companion.EN
import dev.ancdu.XmlTxt.Companion.RU
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Превью в листе удаления, раскадровка видео: времена кадров, подборка медиа каталога, что видно. */
class SheetPeekTest {
    @Test fun frameTimes() {
        // 0/20/40/60/80% длительности, в микросекундах
        assertArrayEquals(longArrayOf(0, 8_400_000, 16_800_000, 25_200_000, 33_600_000), Storyboard.times(42_000))
        assertArrayEquals(longArrayOf(0, 2_000_000, 4_000_000, 6_000_000, 8_000_000), Storyboard.times(10_000))
        // 1 мс — пять разных моментов, не один и тот же
        assertArrayEquals(longArrayOf(0, 200, 400, 600, 800), Storyboard.times(1))
        // 0 или неизвестно — один кадр с начала
        assertArrayEquals(longArrayOf(0), Storyboard.times(0))
        assertArrayEquals(longArrayOf(0), Storyboard.times(null))
        assertArrayEquals(longArrayOf(0), Storyboard.times(-5))
        // 10 часов — без переполнения
        assertEquals(36_000_000_000L * 4 / 5, Storyboard.times(36_000_000).last())
    }

    @Test fun storyboardMeta() {
        assertEquals("0:42 · 1920×1080", Storyboard.meta(42_000, 1920, 1080, 0))
        // повёрнутое видео — размеры как на экране
        assertEquals("0:42 · 1080×1920", Storyboard.meta(42_000, 1920, 1080, 90))
        assertEquals("1:02:03 · 640×480", Storyboard.meta(3_723_000, 640, 480, 180))
        assertEquals("1080×1920", Storyboard.meta(null, 1920, 1080, 270))
        assertEquals("0:05", Storyboard.meta(5_000, 0, 0, 0))
        assertNull(Storyboard.meta(null, 0, 0, 0))
        assertEquals("0:12 / 0:42", Storyboard.clock(12_400, 42_000))
        assertEquals("0:00 / 0:00", Storyboard.clock(-1, -1))
    }

    @Test fun playerTexts() {
        assertEquals("can't play", EN.s(R.string.ql_cant_play))
        assertEquals("воспроизведение недоступно", RU.s(R.string.ql_cant_play))
        assertEquals("Play video", EN.s(R.string.ql_play))
        assertEquals("Воспроизвести видео", RU.s(R.string.ql_play))
        assertEquals("Unmute", EN.s(R.string.ql_unmute))
        assertEquals("Включить звук", RU.s(R.string.ql_unmute))
        assertEquals("Frame 2 of 5", EN.s(R.string.ql_frame, "2", "5"))
        assertEquals("Кадр 2 из 5", RU.s(R.string.ql_frame, "2", "5"))
        assertEquals("Quick look", EN.s(R.string.sheet_peek))
        assertEquals("Быстрый просмотр", RU.s(R.string.sheet_peek))
    }

    private companion object {
        /** Как MimeTypeMap для расширений теста. */
        val MIME = mapOf("jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp",
            "heic" to "image/heic", "mp4" to "video/mp4", "pdf" to "application/pdf", "mp3" to "audio/mpeg")
    }

    private class Tree {
        val kids = HashMap<Int, MutableList<ContactSheet.Kid>>()
        val names = HashMap<Int, String>()
        var visits = 0
        private var next = 1

        /** Узел под [parent]; у каталога disk — сумма, считается в [fix]. */
        fun add(parent: Int, name: String, disk: Long = 0, flags: Int = 0): Int {
            val id = next++
            names[id] = name
            kids.getOrPut(parent) { ArrayList() } += ContactSheet.Kid(id, disk, flags)
            return id
        }

        fun dir(parent: Int, name: String): Int = add(parent, name, 0, F_DIR)

        /** Размеры каталогов снизу вверх, дети — по убыванию disk (как csr_children). */
        fun fix(node: Int = 0): Long {
            val list = kids[node] ?: return 0
            for ((i, k) in list.withIndex()) if (k.flags and F_DIR != 0) list[i] = ContactSheet.Kid(k.id, fix(k.id) + 1, k.flags)
            list.sortWith(compareByDescending<ContactSheet.Kid> { it.disk }.thenBy { it.id })
            return list.sumOf { it.disk }
        }

        fun pick(root: Int = 0, k: Int = ContactSheet.K, budget: Int = ContactSheet.BUDGET): List<String> =
            ContactSheet.pick(root, { visits++; kids[it].orEmpty() }, { names.getValue(it) }, { MIME[it] }, k, budget)
                .map { names.getValue(it.id) }
    }

    @Test fun contactSheetTopMediaInSubtree() {
        val t = Tree()
        t.add(0, "huge.bin", 9000)
        t.add(0, "a.jpg", 500)
        val d = t.dir(0, "DCIM")
        val cam = t.dir(d, "Camera")
        t.add(cam, "v.MP4", 4000)
        t.add(cam, "p1.jpg", 300)
        t.add(cam, "notes.txt", 3000)
        t.add(d, "p2.png", 700)
        t.add(d, "p3.webp", 200)
        t.add(d, "x.heic", 100)
        t.fix()
        // крупнейшие медиа всего поддерева по размеру; не медиа — мимо
        assertEquals(listOf("v.MP4", "p2.png", "a.jpg", "p1.jpg"), t.pick())
        assertEquals(listOf("v.MP4", "p2.png", "p1.jpg", "p3.webp"), t.pick(d))
        assertEquals(listOf("v.MP4"), t.pick(d, k = 1))
        assertEquals(listOf("v.MP4", "p1.jpg"), t.pick(cam))
    }

    @Test fun contactSheetFilters() {
        val t = Tree()
        val d = t.dir(0, "d")
        t.add(d, "link.jpg", 900, F_SYMLINK)
        t.add(d, "mnt.jpg", 800, F_OTHERFS)
        t.add(d, "dup.jpg", 700, F_HLDUP)
        t.add(d, "empty.jpg", 0)
        t.add(d, "dir.mp4", 0, F_DIR)
        t.add(d, "photo", 600)
        t.add(d, ".jpg", 500)
        t.add(d, "doc.pdf", 400)
        t.add(d, "song.mp3", 300)
        t.add(d, "ok.jpeg", 10)
        t.fix()
        assertEquals(listOf("ok.jpeg"), t.pick(d))
        // без медиа — пусто (лист строку не показывает)
        val e = Tree()
        val only = e.dir(0, "only")
        e.add(only, "a.bin", 5)
        e.fix()
        assertTrue(e.pick(only).isEmpty())
        assertTrue(e.pick(e.add(0, "file.jpg", 3)).isEmpty())
    }

    @Test fun contactSheetBudget() {
        val t = Tree()
        val d = t.dir(0, "d")
        for (i in 0 until 50) t.add(d, "f$i.bin", 1000L + i)
        t.add(d, "small.jpg", 1)
        t.fix()
        // обход по убыванию размера: бюджет кончился раньше — что нашли (ничего), без зависания
        assertTrue(t.pick(d, budget = 10).isEmpty())
        assertEquals(listOf("small.jpg"), t.pick(d, budget = 100))
        // каталоги открываются по мере надобности: нашли 4 — дальше не идём
        val w = Tree()
        val root = w.dir(0, "r")
        for (i in 0 until 20) { val sub = w.dir(root, "s$i"); w.add(sub, "p$i.jpg", 100L - i) }
        w.fix()
        w.visits = 0
        assertEquals(listOf("p0.jpg", "p1.jpg", "p2.jpg", "p3.jpg"), w.pick(root))
        assertTrue("открыто ${w.visits}", w.visits <= 6)
    }

    @Test fun visibilityRules() {
        // карточка над листом — без «УДАЛИТЬ…» и «ВЫБРАТЬ»
        assertTrue(SheetPeek.cardActions(fromSheet = false))
        assertFalse(SheetPeek.cardActions(fromSheet = true))
        // место 120dp — только у листа одного файла, открытого не из карточки
        assertTrue(SheetPeek.selfBox(fromCard = false, group = false, dir = false, kind = PeekKind.IMAGE))
        assertTrue(SheetPeek.selfBox(fromCard = false, group = false, dir = false, kind = PeekKind.TEXT))
        assertFalse(SheetPeek.selfBox(fromCard = true, group = false, dir = false, kind = PeekKind.IMAGE))
        assertFalse(SheetPeek.selfBox(fromCard = false, group = true, dir = false, kind = PeekKind.IMAGE))
        assertFalse(SheetPeek.selfBox(fromCard = false, group = false, dir = true, kind = PeekKind.IMAGE))
        assertFalse(SheetPeek.selfBox(fromCard = false, group = false, dir = false, kind = PeekKind.NONE))
        // квадрат строки — картинка, видео, APK; знак вида — моно «IMG»/«VID»/«APK»
        for (k in listOf(PeekKind.IMAGE, PeekKind.VIDEO, PeekKind.APK)) assertTrue(SheetPeek.thumb(k))
        for (k in listOf(PeekKind.TEXT, PeekKind.NONE)) assertFalse(SheetPeek.thumb(k))
        assertEquals("IMG", SheetPeek.glyph(PeekKind.IMAGE))
        assertEquals("VID", SheetPeek.glyph(PeekKind.VIDEO))
        assertEquals("APK", SheetPeek.glyph(PeekKind.APK))
        assertNull(SheetPeek.glyph(PeekKind.TEXT))
        // путь только для root, ссылка, другая ФС — вида нет (без чтения)
        assertEquals(PeekKind.IMAGE, Peek.kindFor("jpg", "image/jpeg", rootOnly = false, flags = 0))
        assertEquals(PeekKind.NONE, Peek.kindFor("jpg", "image/jpeg", rootOnly = true, flags = 0))
        assertEquals(PeekKind.NONE, Peek.kindFor("jpg", "image/jpeg", rootOnly = false, flags = F_SYMLINK))
        assertEquals(PeekKind.NONE, Peek.kindFor("mp4", "video/mp4", rootOnly = false, flags = F_DIR))
    }
}
