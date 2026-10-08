package dev.ancdu

import dev.ancdu.XmlTxt.Companion.EN
import dev.ancdu.XmlTxt.Companion.RU
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Гиганты»: ключ выбора по цепочке имён, поиск цепочки в живом дереве, тексты, журнал группы из разных папок. */
class GiantsTest {
    private val N = Fmt.NBSP
    private fun b(s: String) = s.toByteArray()
    private fun chain(vararg s: String) = s.map { it.toByteArray() }

    @Test fun chainKeyEqualityHashAndCopy() {
        val a = ChainKey(chain("DCIM", "Camera", "v.mp4"))
        assertEquals(a, ChainKey(chain("DCIM", "Camera", "v.mp4")))
        assertEquals(a.hashCode(), ChainKey(chain("DCIM", "Camera", "v.mp4")).hashCode())
        // Тот же лист в другой папке и та же склейка по-другому — разные ключи.
        assertNotEquals(a, ChainKey(chain("Movies", "v.mp4")))
        assertNotEquals(ChainKey(chain("a", "bc")), ChainKey(chain("ab", "c")))
        assertNotEquals(ChainKey(chain("a")), ChainKey(chain("a", "a")))
        // Невалидный UTF-8, одинаковый после декодирования, — разные ключи.
        val x = ChainKey(listOf(b("d"), byteArrayOf(0x61, 0xFF.toByte())))
        val y = ChainKey(listOf(b("d"), byteArrayOf(0x61, 0xFE.toByte())))
        assertNotEquals(x, y)
        // Свои копии: ни исходный массив, ни выданный наружу ключ не портят.
        val raw = b("a.bin")
        val k = ChainKey(listOf(b("d"), raw))
        raw[0] = 'z'.code.toByte()
        k.names[1][0] = 'q'.code.toByte()
        assertEquals(ChainKey(chain("d", "a.bin")), k)
        assertEquals("d/a.bin", k.rel)
        // Ключ выбора: в одном Selection рядом с ключами по имени.
        val s = Selection()
        s.start(k, 7)
        assertTrue(s.contains(7))
        assertEquals(7, s.nodeOf(ChainKey(chain("d", "a.bin"))))
        assertNull(s.nodeOf(NameKey(b("a.bin"))))
    }

    /** Дерево-фейк: id → (родитель, имя, удалён). */
    private class Fake {
        val parent = HashMap<Int, Int>()
        val name = HashMap<Int, ByteArray>()
        val dead = HashSet<Int>()
        fun add(id: Int, p: Int, nm: String) { parent[id] = p; name[id] = nm.toByteArray() }
        fun child(p: Int, nm: ByteArray): Int? = parent.entries.firstOrNull { (id, pp) -> pp == p && name[id]!!.contentEquals(nm) }?.key
        fun verify(nd: Int, p: Int, nm: ByteArray) = nd !in dead && parent[nd] == p && name[nd]!!.contentEquals(nm)
    }

    @Test fun chainResolverVerifiesEveryStep() {
        val f = Fake().apply {
            add(1, 0, "DCIM"); add(2, 1, "Camera"); add(3, 2, "v.mp4"); add(4, 0, "Movies"); add(5, 4, "v.mp4")
        }
        assertEquals(3, Giants.resolve(chain("DCIM", "Camera", "v.mp4"), f::child, f::verify))
        assertEquals(5, Giants.resolve(chain("Movies", "v.mp4"), f::child, f::verify))
        assertNull(Giants.resolve(chain("DCIM", "v.mp4"), f::child, f::verify))
        // Пустая цепочка — это корень: удалять корень нельзя, поэтому не найдено.
        assertNull(Giants.resolve(emptyList(), f::child, f::verify))
        // Удалённый предок — цепочка не найдена (даже если child() его ещё вернул).
        f.dead += 2
        assertNull(Giants.resolve(chain("DCIM", "Camera", "v.mp4"), f::child, f::verify))
        f.dead.clear()
        // child() вернул узел с чужим родителем или другим именем — шаг не проверен, не найдено.
        val liar: (Int, ByteArray) -> Int? = { p, nm -> if (p == 1) 5 else f.child(p, nm) }
        assertNull(Giants.resolve(chain("DCIM", "Camera", "v.mp4"), liar, f::verify))
        val renamed: (Int, ByteArray) -> Int? = { p, nm -> if (nm.contentEquals(b("v.mp4"))) 2 else f.child(p, nm) }
        assertNull(Giants.resolve(chain("DCIM", "Camera", "v.mp4"), renamed, f::verify))
    }

    @Test fun commonFolderAndRelativeNames() {
        val a = chain("DCIM", "Camera", "a.mp4")
        val b2 = chain("DCIM", "Screen", "b.png")
        val c = chain("DCIM", "Camera", "c.mp4")
        assertEquals(listOf("DCIM"), Giants.commonFolder(listOf(a, b2, c)).map { String(it) })
        assertEquals(listOf("DCIM", "Camera"), Giants.commonFolder(listOf(a, c)).map { String(it) })
        // Разные ветки от корня — общей папки нет (корень).
        assertEquals(emptyList<String>(), Giants.commonFolder(listOf(a, chain("Movies", "m.mkv"))).map { String(it) })
        // Файл в самом корне — общая папка — корень.
        assertEquals(emptyList<String>(), Giants.commonFolder(listOf(a, chain("x.iso"))).map { String(it) })
        assertEquals(emptyList<String>(), Giants.commonFolder(emptyList()).map { String(it) })
        // Один файл: его папка.
        assertEquals(listOf("DCIM", "Camera"), Giants.commonFolder(listOf(a)).map { String(it) })
        assertArrayEquals(b("Camera/a.mp4"), Giants.relTo(chain("DCIM"), a))
        assertArrayEquals(b("DCIM/Camera/a.mp4"), Giants.relTo(emptyList(), a))
        // Байты как есть: невалидный UTF-8 не трогается.
        val bad = listOf(b("d"), byteArrayOf(0xFF.toByte()))
        assertArrayEquals(byteArrayOf('d'.code.toByte(), '/'.code.toByte(), 0xFF.toByte()), Giants.relTo(emptyList(), bad))
    }

    @Test fun summaryLinkEmptyAndDescriptions() {
        val gib = 1L shl 30
        assertEquals("312 files ≥ 100${N}MiB · 214.3${N}GiB", GiantsText.summary(EN, 312, (214.3 * gib).toLong()))
        assertEquals("1 file ≥ 100${N}MiB · 1.0${N}GiB", GiantsText.summary(EN, 1, gib))
        assertEquals("312 файлов ≥ 100${N}МиБ · 214,3${N}ГиБ", GiantsText.summary(RU, 312, (214.3 * gib).toLong()))
        assertEquals("1 файл ≥ 100${N}МиБ · 1,0${N}ГиБ", GiantsText.summary(RU, 1, gib))
        assertEquals("2 файла ≥ 100${N}МиБ · 1,0${N}ГиБ", GiantsText.summary(RU, 2, gib))
        assertEquals("21 файл ≥ 100${N}МиБ · 1,0${N}ГиБ", GiantsText.summary(RU, 21, gib))
        assertEquals("1${N}204 файла ≥ 100${N}МиБ · 1,0${N}ГиБ", GiantsText.summary(RU, 1204, gib))
        assertEquals("all 312 ›", GiantsText.link(EN, 312))
        assertEquals("все 312 ›", GiantsText.link(RU, 312))
        assertEquals("All 312 biggest files", GiantsText.linkDesc(EN, 312))
        assertEquals("Все крупные файлы: 312", GiantsText.linkDesc(RU, 312))
        // Ссылка — только когда файлов больше показанных строк.
        assertFalse(GiantsText.linkShown(Biggest.K.toLong()))
        assertTrue(GiantsText.linkShown(Biggest.K + 1L))
        assertEquals("No files over 100${N}MiB", GiantsText.empty(EN))
        assertEquals("Нет файлов больше 100${N}МиБ", GiantsText.empty(RU))
        assertEquals("Biggest files", EN.s(R.string.big_title))
        assertEquals("v.mp4, 1.0${N}GiB, in folder DCIM/Camera/", GiantsText.desc(EN, "v.mp4", "1.0${N}GiB", "DCIM/Camera/", null))
        assertEquals("v.mp4, 1,0${N}ГиБ, в папке DCIM/Camera/", GiantsText.desc(RU, "v.mp4", "1,0${N}ГиБ", "DCIM/Camera/", null))
        val dl = Tag(TagKind.DL)
        assertEquals("a.iso, 1.0${N}GiB, in folder Download/" + dl.desc(EN, null),
            GiantsText.desc(EN, "a.iso", "1.0${N}GiB", "Download/", dl))
    }

    @Test fun logOfACrossFolderGroup() {
        val objs = listOf(
            GiantObject(chain("DCIM", "Camera", "a.mp4"), 300, false),
            GiantObject(chain("Movies", "m.mkv"), 500, false),
            GiantObject(chain("DCIM", "Screen", "b.png"), 100, false))
        val (folder, logged) = Giants.logObjects(objs)
        assertTrue(folder.isEmpty())
        assertEquals(listOf("DCIM/Camera/a.mp4", "Movies/m.mkv", "DCIM/Screen/b.png"), logged.map { String(it.name) })
        val a = LogActions.group("/r", false, folder, logged, 3, false, false, mixed = true)
        assertTrue(a.group && a.mixed)
        assertEquals(3, a.count)
        // Крупнейшие первыми, как у группы одной папки.
        assertEquals(listOf("Movies/m.mkv", "DCIM/Camera/a.mp4", "DCIM/Screen/b.png"), a.itemNames.map { String(it) })
        val s = LogActions.start(1, 2, a)
        val back = LogRec.parse(s.format()) as LogRec.Start
        assertTrue(back.group && back.mixed)
        assertEquals(3, back.count)
        assertEquals(listOf("Movies/m.mkv", "DCIM/Camera/a.mp4", "DCIM/Screen/b.png"), back.itemNames.map { String(it) })
        val e = LogEntry(back, null, null, false)
        assertEquals("(several folders) · 3 items", LogRows.title(EN, e))
        assertEquals("(разные папки) · 3 объекта", LogRows.title(RU, e))
        // Общая папка есть — она родитель, имена — пути от неё.
        val (f2, l2) = Giants.logObjects(listOf(objs[0], objs[2]))
        assertEquals(listOf("DCIM"), f2.map { String(it) })
        assertEquals(listOf("Camera/a.mp4", "Screen/b.png"), l2.map { String(it.name) })
        val a2 = LogActions.group("/r", false, f2, l2, 2, false, false, mixed = true)
        assertEquals("DCIM/ · 2 items", LogRows.title(EN, LogEntry(LogRec.parse(LogActions.start(1, 2, a2).format()) as LogRec.Start, null, null, false)))
        // Все в одной папке — обычная группа этой папки.
        val (f3, l3) = Giants.logObjects(listOf(GiantObject(chain("D", "x"), 1, false), GiantObject(chain("D", "y"), 2, false)))
        assertEquals(listOf("D"), f3.map { String(it) })
        assertEquals(listOf("x", "y"), l3.map { String(it.name) })
        // Один объект — одиночное удаление его самого (путь от корня).
        val (f4, l4) = Giants.logObjects(listOf(objs[1]))
        val one = LogActions.group("/r", false, f4, l4, 1, false, false, mixed = true)
        assertFalse(one.group || one.mixed)
        assertEquals(listOf("Movies", "m.mkv"), one.names.map { String(it) })
        // Старая запись группы («g3») читается как обычная группа.
        val old = LogRec.parse(LogActions.start(1, 2, LogActions.group("/r", false, f3, l3, 2, false, false)).format()) as LogRec.Start
        assertTrue(old.group); assertFalse(old.mixed)
        // Прерванное удаление группы из разных папок: строка статуса называет «(разные папки)».
        val d = DeleteLogModel.notice(listOf(e))!!
        assertTrue(d.mixed)
        assertEquals("(several folders)", d.label(EN))
    }
}

/** Жёсткая ссылка: итог удаления не обещает освобождённого места (одиночное, группа, «гиганты»). */
class HardlinkFooterTest {
    private val gib = 1L shl 30

    @Test fun singleFooter() {
        assertEquals(EN.s(R.string.freed, Fmt.size(gib, EN)), DeleteProgress.freed(EN, gib))
        assertEquals(EN.s(R.string.hardlink_note), DeleteProgress.freed(EN, gib, hardlink = true))
        assertEquals("Hard link — space may not be freed", DeleteProgress.freed(EN, gib, hardlink = true))
        assertEquals("Жёсткая ссылка — место может не освободиться", DeleteProgress.freed(RU, gib, hardlink = true))
    }

    @Test fun groupFooterAndAlert() {
        val ok = ItemResult("a", false, gib, 0, 1, hardlink = true)
        val plain = ItemResult("b", false, gib, 0, 1)
        assertTrue(GroupResult.hardlink(listOf(plain, ok)))
        assertFalse(GroupResult.hardlink(listOf(plain)))
        // Не удалённая жёсткая ссылка места и не обещала.
        assertFalse(GroupResult.hardlink(listOf(plain, ItemResult("c", false, gib, -13, 0, hardlink = true))))
        val mixed = listOf(plain, ok)
        val done = GroupResult.outcome(mixed)
        val l = GroupResult.links(mixed)
        assertEquals(1, l.count); assertEquals(gib, l.disk)
        // Часть — ссылки: освобождено без них и короткая приписка.
        assertEquals(DeleteProgress.freed(EN, gib) + " · excl. hard links", GroupResult.footer(EN, done, l))
        assertEquals(DeleteProgress.freed(RU, gib) + " · без жёстких ссылок", GroupResult.footer(RU, done, l))
        assertEquals(DeleteProgress.freed(EN, 2 * gib), GroupResult.footer(EN, done))
        // Все удалённые — ссылки: только примечание.
        val allLinks = listOf(ok, ItemResult("d", false, gib, 0, 1, hardlink = true))
        assertEquals(EN.s(R.string.hardlink_note), GroupResult.footer(EN, GroupResult.outcome(allLinks), GroupResult.links(allLinks)))
        // Не удалённая ссылка не в счёт.
        assertEquals(0, GroupResult.links(listOf(plain, ItemResult("c", false, gib, -13, 0, hardlink = true))).count)
        val stopped = GroupResult.Outcome.Stopped(2, 3, 2 * gib)
        assertTrue(GroupResult.footer(RU, stopped, l).endsWith(DeleteProgress.freed(RU, gib) + " · без жёстких ссылок"))
        val partial = GroupResult.outcome(listOf(ok, ItemResult("c", false, gib, -13, 0)))
        val (_, body) = GroupResult.alert(EN, partial as GroupResult.Outcome.Partial, GroupResult.links(listOf(ok)))
        assertTrue(body, body.startsWith(EN.s(R.string.hardlink_note)))
    }
}
