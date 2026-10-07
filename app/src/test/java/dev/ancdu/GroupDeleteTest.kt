package dev.ancdu

import dev.ancdu.XmlTxt.Companion.EN
import dev.ancdu.XmlTxt.Companion.RU
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ярус листа группы: строжайшее правило, размер — сумма. */
class GroupTierTest {
    private val mib = 1L shl 20
    private val gib = 1L shl 30

    @Test fun sumReachesPause() {
        // 20 × 60 МиБ = 1200 МиБ ≥ 1 ГиБ: пауза, хотя каждый по отдельности — без неё.
        val disks = List(20) { 60 * mib }
        assertEquals(DeleteTier.NONE, DeletePolicy.tier(viaRoot = false, fast = false, owned = false, disk = 60 * mib))
        assertEquals(DeleteTier.PAUSE, DeletePolicy.groupTier(viaRoot = false, fast = false, owned = List(20) { false }, disks = disks))
        assertEquals(DeleteTier.NONE, DeletePolicy.groupTier(viaRoot = false, fast = false, owned = List(5) { false }, disks = List(5) { 60 * mib }))
    }

    @Test fun strictestWins() {
        // Один чужой — пауза для всей группы.
        assertEquals(DeleteTier.PAUSE, DeletePolicy.groupTier(false, false, listOf(false, true, false), listOf(1, 2, 3)))
        // root-сессия или «быстро через root» — ярус root, что бы ни было в группе.
        assertEquals(DeleteTier.ROOT, DeletePolicy.groupTier(true, false, listOf(false, false), listOf(1, 2)))
        assertEquals(DeleteTier.ROOT, DeletePolicy.groupTier(false, true, listOf(false, true), listOf(gib, gib)))
        assertEquals(DeleteTier.NONE, DeletePolicy.groupTier(false, false, listOf(false, false), listOf(1, 2)))
    }

    @Test fun sumSaturates() {
        assertEquals(Long.MAX_VALUE, DeletePolicy.sum(listOf(Long.MAX_VALUE, 5L)))
        assertEquals(DeleteTier.PAUSE, DeletePolicy.groupTier(false, false, listOf(false, false), listOf(Long.MAX_VALUE, Long.MAX_VALUE)))
        assertEquals(3L, DeletePolicy.sum(listOf(1L, -7L, 2L)))   // отрицательные (нет данных) не вычитаются
    }
}

/** Итоги удаления группы: errno → причина, ENOENT — удалено. */
class GroupResultTest {
    private val mib = 1L shl 20
    private fun ok(name: String, disk: Long = mib, dir: Boolean = false) = ItemResult(name, dir, disk, 0, 1)
    private fun bad(name: String, r: Int, dir: Boolean = false, done: Long = 0) = ItemResult(name, dir, mib, r, done)

    @Test fun errnoToReason() {
        assertNull(GroupResult.fail(ok("a")))
        assertNull("ENOENT — удалено", GroupResult.fail(bad("a", -2)))
        assertEquals(Fail.ACCESS, GroupResult.fail(bad("a", -13)))     // EACCES
        assertEquals(Fail.ACCESS, GroupResult.fail(bad("a", -1)))      // EPERM
        assertEquals(Fail.BUSY, GroupResult.fail(bad("a", -16)))       // EBUSY
        assertEquals(Fail.BUSY, GroupResult.fail(bad("a", -18)))       // EXDEV: rm_tree так отдаёт EBUSY точки монтирования
        assertEquals(Fail.SYMLINK, GroupResult.fail(bad("a", -40)))    // ELOOP
        assertEquals(Fail.CHANGED, GroupResult.fail(bad("a", -116)))   // ESTALE: подменён после скана
        assertEquals(Fail.CHANGED, GroupResult.fail(bad("d", -116, dir = true)))
        assertEquals(Fail.ERROR, GroupResult.fail(bad("a", -5)))       // EIO у файла
        assertEquals(Fail.PARTIAL, GroupResult.fail(bad("d", -5, dir = true)))
        // каталог, из которого что-то удалено, — «удалено частично», какой бы ни была ошибка
        assertEquals(Fail.PARTIAL, GroupResult.fail(bad("d", -13, dir = true, done = 3)))
        assertEquals(Fail.ACCESS, GroupResult.fail(bad("d", -13, dir = true, done = 0)))
        assertEquals(Fail.BLOCKED, GroupResult.fail(ItemResult("s", true, mib, -1, 0, attempted = false, block = Block.SYSTEM)))
    }

    /** «Изменилось после скана»: причина в сообщении, дерево не обновляется (ничего не удалено). */
    @Test fun changedSinceScan() {
        assertTrue(NativeErr.changedSinceScan(-NativeErr.ESTALE))
        assertFalse(NativeErr.changedSinceScan(-DeleteProgress.ELOOP))
        val res = listOf(ok("a"), bad("d", -NativeErr.ESTALE, dir = true))
        val o = GroupResult.outcome(res) as GroupResult.Outcome.Partial
        assertEquals("освобождено 1,0${Fmt.NBSP}МиБ\n\nНе удалено:\nd/ — изменилось после скана", GroupResult.alert(RU, o).second)
        assertTrue(GroupResult.alert(EN, o).second.endsWith("d/ — changed since scan"))
        assertFalse(GroupResult.needsRefresh(res, viaRoot = false))
        assertFalse(GroupResult.needsRefresh(res, viaRoot = true))
        assertEquals(-NativeErr.ESTALE, GroupResult.code(res, viaRoot = false))
    }

    @Test fun allDeleted() {
        val o = GroupResult.outcome(listOf(ok("a", 2 * mib), bad("b", -2), ok("c", 3 * mib)))
        assertTrue(o is GroupResult.Outcome.Done)
        assertEquals(5 * mib, o.freed)                 // пропавший к началу не считается освобождённым
        assertEquals(0, GroupResult.code(listOf(ok("a"), bad("b", -2)), viaRoot = false))
        assertEquals("освобождено 5,0${Fmt.NBSP}МиБ", GroupResult.footer(RU, o))
    }

    @Test fun stoppedIsFooter() {
        val o = GroupResult.outcome(listOf(ok("a", 3 * mib), bad("b", -4, dir = true, done = 7),
            ItemResult("c", false, mib, -4, 0, attempted = false)))
        assertTrue(o is GroupResult.Outcome.Stopped)
        assertEquals(1, o.deleted); assertEquals(3, o.total)
        assertEquals("Остановлено: удалено 1 из 3 · освобождено 3,0${Fmt.NBSP}МиБ", GroupResult.footer(RU, o))
        assertEquals("Stopped: deleted 1 of 3 · freed 3.0${Fmt.NBSP}MiB", GroupResult.footer(EN, o))
        // остановленный каталог удалён частично — дерево обновится
        assertTrue(GroupResult.needsRefresh(listOf(bad("b", -4, dir = true, done = 7)), viaRoot = false))
        assertFalse(GroupResult.needsRefresh(listOf(ItemResult("c", true, mib, -4, 0, attempted = false)), viaRoot = false))
    }

    @Test fun partialIsAlert() {
        val res = listOf(ok("a.bin", 2 * mib), bad("locked", -13, dir = true), ok("c.bin", mib), bad("x", -16),
            bad("lnk", -40), bad("half", -5, dir = true, done = 2))
        val o = GroupResult.outcome(res)
        assertTrue(o is GroupResult.Outcome.Partial)
        val (title, msg) = GroupResult.alert(RU, o as GroupResult.Outcome.Partial)
        assertEquals("Удалено 2 из 6", title)
        assertEquals("освобождено 3,0${Fmt.NBSP}МиБ\n\nНе удалено:\nlocked/ — нет доступа\nx — занят\n" +
            "lnk — символьная ссылка\nhalf/ — удалено частично", msg)
        assertEquals("Deleted 2 of 6", GroupResult.alert(EN, o).first)
        // код группы — первого каталога, который мог удалиться частично: по нему BgScan обновит дерево
        assertEquals(-13, GroupResult.code(res, viaRoot = false))
        assertTrue(GroupResult.needsRefresh(res, viaRoot = false))
        // ничего частичного — первая ошибка, обновлять нечего
        val flat = listOf(ok("a"), bad("b", -13), bad("c", -16))
        assertEquals(-13, GroupResult.code(flat, viaRoot = false))
        assertFalse(GroupResult.needsRefresh(flat, viaRoot = false))
        // su отказал (-EPERM через root): ничего не удалено, не «частично»
        assertFalse(GroupResult.needsRefresh(listOf(bad("d", -1, dir = true)), viaRoot = true))
        assertTrue(GroupResult.rootRefused(listOf(ok("a"), bad("d", -1, dir = true)), viaRoot = true))
        assertFalse(GroupResult.rootRefused(listOf(bad("d", -1, dir = true)), viaRoot = false))
    }
}

/** Тексты листа и диалога группы: plurals RU 1/2/5/21. */
class GroupTextTest {
    @Test fun titlePlurals() {
        assertEquals("Удалить 1 объект?", GroupSheet.title(RU, 1))
        assertEquals("Удалить 2 объекта?", GroupSheet.title(RU, 2))
        assertEquals("Удалить 5 объектов?", GroupSheet.title(RU, 5))
        assertEquals("Удалить 21 объект?", GroupSheet.title(RU, 21))
        assertEquals("Удалить 1${Fmt.NBSP}204 объекта?", GroupSheet.title(RU, 1204))
        assertEquals("Delete 3 items?", GroupSheet.title(EN, 3))
        assertEquals("Delete 1 item?", GroupSheet.title(EN, 1))
    }

    @Test fun progressTitle() {
        assertEquals("Удаление 2 объектов", DeleteProgress.titleFor(RU, "x", 2))
        assertEquals("Удаление 5 объектов", DeleteProgress.titleFor(RU, "x", 5))
        assertEquals("Удаление 21 объекта", DeleteProgress.titleFor(RU, "x", 21))
        assertEquals("Deleting 3 items", DeleteProgress.titleFor(EN, "x", 3))
        // один объект — прежний заголовок
        assertEquals(DeleteProgress.title(RU, "x"), DeleteProgress.titleFor(RU, "x", 1))
    }

    @Test fun selectedCount() {
        assertEquals("1 выбран", GroupSheet.selected(RU, 1))
        assertEquals("2 выбрано", GroupSheet.selected(RU, 2))
        assertEquals("5 выбрано", GroupSheet.selected(RU, 5))
        assertEquals("21 выбран", GroupSheet.selected(RU, 21))
        assertEquals("3 selected", GroupSheet.selected(EN, 3))
    }

    @Test fun ownersAndGone() {
        assertEquals("данные 2 приложений: WhatsApp, Telegram", GroupSheet.owners(RU, listOf("WhatsApp", "Telegram")))
        assertEquals("данные 5 приложений: A, B, C +2", GroupSheet.owners(RU, listOf("A", "B", "C", "D", "E")))
        assertEquals("data of 2 apps: WhatsApp, Telegram", GroupSheet.owners(EN, listOf("WhatsApp", "Telegram")))
        assertEquals("1 уже нет на диске", GroupSheet.gone(RU, 1))
        assertEquals("2 no longer on disk", GroupSheet.gone(EN, 2))
        assertEquals("Выбор снят: 3", GroupSheet.cleared(RU, 3))
        assertEquals("/storage/emulated/0/Download/", GroupSheet.parentPath("/storage/emulated/0/Download"))
        assertEquals("/", GroupSheet.parentPath("/"))
    }
}

/** Сводка выбранного в один DeletePreview: суммы, крупнейшие пять, строжайшее. */
class GroupPreviewTest {
    private val mib = 1L shl 20
    private fun item(name: String, disk: Long, dir: Boolean = false, items: Long = 1, owner: String? = null,
                     flags: Int = if (dir) F_DIR else 0, fast: Boolean = true, apparent: Long = disk, block: Block? = null) =
        GroupItem(name, dir, disk, apparent, items, flags, owner, block, fast)

    @Test fun aggregates() {
        val items = listOf(item("a.bin", 1 * mib), item("models", 6 * mib, dir = true, items = 1204, owner = "com.x"),
            item("b.bin", 2 * mib, apparent = mib), item("c", 3 * mib), item("d", 4 * mib), item("e", 5 * mib, fast = false),
            item("f", 7 * mib, owner = "dev.ancdu"))
        val (p, g) = GroupSheet.preview(EN, items, "/storage/emulated/0/Download", self = "dev.ancdu",
            viaRoot = false, kind = Kind.SCAN, cacheTime = null, root = RootState.GRANTED, gone = 1)
        assertEquals("7 items", p.name)
        assertEquals("/storage/emulated/0/Download/", p.path)
        assertEquals(28 * mib, p.disk)
        assertEquals(27 * mib, p.apparent)
        assertEquals(1210L, p.items)
        assertTrue("есть каталог — «· N эл.»", p.dir)
        assertEquals(listOf("f" to 7 * mib, "models/" to 6 * mib, "e" to 5 * mib, "d" to 4 * mib, "c" to 3 * mib), p.top)
        assertEquals(listOf(-1L, 1204L, -1L, -1L, -1L), g.topItems)
        assertEquals(2, p.more)
        assertFalse("один не проходит быстрый путь — галочки нет", p.fast)
        assertNull(p.block)
        assertEquals("свой пакет — не владелец", listOf("com.x"), g.owners)
        assertNull("владелец один, но не у всех — не ownerRow", p.owner)
        assertEquals(1, g.ownerItems)
        assertEquals(listOf(false, true, false, false, false, false, false), g.owned)
        assertEquals(7, g.count)
        assertEquals(1, g.gone)
        assertFalse(g.hardlink)
        assertEquals(DeleteTier.PAUSE, g.tier(viaRoot = false, fast = false))   // чужой com.x
    }

    @Test fun oneOwnerHardlinkBlocked() {
        val items = listOf(item("a", mib, owner = "com.x"), item("b", mib, flags = F_HLDUP, owner = "com.x"),
            item("d", mib, dir = true, block = Block.REFRESH_FAILED, owner = "com.x"))
        val (p, g) = GroupSheet.preview(RU, items, "/x", self = "dev.ancdu", viaRoot = false, kind = Kind.CACHE,
            cacheTime = "1 янв.", root = RootState.UNKNOWN, gone = 0)
        assertEquals("владелец у всех — прежний ownerRow", "com.x", p.owner)
        assertEquals(3, g.ownerItems)
        assertTrue(g.hardlink)
        assertEquals(Block.REFRESH_FAILED, p.block)
        assertEquals("1 янв.", p.cacheTime)
        assertEquals("3 объекта", p.name)
        assertEquals(0, p.more)
    }
}

/** Полировка: предел строк, стоп с настоящими ошибками, «не начато», владельцы, запрет, копия ключа. */
class GroupPolishTest {
    private val mib = 1L shl 20

    @Test fun partialAlertCapsAtTenBySize() {
        val res = listOf(ItemResult("ok", false, mib, 0, 1)) +
            (1..13).map { ItemResult("f$it", false, it * mib, -13, 0) }
        val o = GroupResult.outcome(res) as GroupResult.Outcome.Partial
        val (_, msg) = GroupResult.alert(RU, o)
        val lines = msg.substringAfter("Не удалено:\n").split("\n")
        assertEquals(11, lines.size)
        assertEquals("f13 — нет доступа", lines[0])          // крупнейший первым
        assertEquals("f4 — нет доступа", lines[9])
        assertEquals("…ещё 3", lines[10])
    }

    @Test fun stopWithRealFailureIsPartial() {
        val res = listOf(ItemResult("a", false, mib, 0, 1), ItemResult("b", false, mib, -13, 0),
            ItemResult("c", false, mib, -4, 0, attempted = false))
        val o = GroupResult.outcome(res)
        assertTrue(o is GroupResult.Outcome.Partial)
        assertTrue((o as GroupResult.Outcome.Partial).stopped)
        val (title, msg) = GroupResult.alert(RU, o)
        assertEquals("Удалено 1 из 3 · остановлено", title)
        assertEquals("Deleted 1 of 3 · stopped", GroupResult.alert(EN, o).first)
        assertTrue(msg, msg.contains("b — нет доступа") && msg.contains("c — не начато"))
        // чистый «Стоп» без ошибок — по-прежнему молча, подвал
        val pure = GroupResult.outcome(listOf(ItemResult("a", false, mib, 0, 1),
            ItemResult("d", true, mib, -4, 5), ItemResult("c", false, mib, -4, 0, attempted = false)))
        assertTrue(pure is GroupResult.Outcome.Stopped)
    }

    @Test fun neverAttemptedIsNotStarted() {
        // после отказа su остальные не начинались
        assertEquals(Fail.NOT_STARTED, GroupResult.fail(ItemResult("x", false, mib, -1, 0, attempted = false)))
        assertEquals(Fail.NOT_STARTED, GroupResult.fail(ItemResult("x", true, mib, -4, 0, attempted = false)))
        assertEquals("не начато", RU.s(Fail.NOT_STARTED.res))
        assertEquals("not started", EN.s(Fail.NOT_STARTED.res))
        // запрещённый к началу — по-прежнему «удаление запрещено»
        assertEquals(Fail.BLOCKED, GroupResult.fail(ItemResult("s", true, mib, -1, 0, attempted = false, block = Block.SYSTEM)))
    }

    @Test fun ownerTexts() {
        assertEquals("данные WhatsApp: 2 из 3", GroupSheet.ownerPart(RU, "WhatsApp", 2, 3))
        assertEquals("WhatsApp data: 2 of 3", GroupSheet.ownerPart(EN, "WhatsApp", 2, 3))
        assertEquals("Нельзя удалить 3 объекта", GroupSheet.blockedTitle(RU, 3))
        assertEquals("Can't delete 3 items", GroupSheet.blockedTitle(EN, 3))
    }

    @Test fun nameKeyCopiesBytes() {
        val raw = "a.bin".toByteArray()
        val k = NameKey(raw)
        raw[0] = 'z'.code.toByte()
        assertEquals(NameKey("a.bin".toByteArray()), k)
        k.bytes[0] = 'q'.code.toByte()
        assertEquals("a.bin", String(k.bytes))
    }
}
