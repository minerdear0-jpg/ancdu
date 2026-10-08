package dev.ancdu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserStateTest {
    private fun b(s: String) = s.toByteArray(Charsets.UTF_8)
    private fun raw(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    private fun saved(
        root: String = "/storage/emulated/0", su: Boolean = false, at: Long = now - hour, giants: Boolean = false,
        chain: List<ByteArray> = listOf(b("DCIM"), raw(0xff, 0xfe), b("Camera")),
        cursor: BrowserState.Cursor? = BrowserState.Cursor(chain + listOf(b("v.mp4")), 7, quiet = false),
    ) = BrowserState.Saved(process = 42L, root = root, su = su, savedAt = at, giants = giants, sort = SORT_NAME,
        apparent = true, scroll = 1234, chain = chain, cursor = cursor)

    private fun assertChain(a: List<ByteArray>, b: List<ByteArray>) {
        assertEquals(a.size, b.size)
        for (i in a.indices) assertArrayEquals(a[i], b[i])
    }

    @Test fun codecRoundTrip() {
        val s = saved()
        val d = BrowserState.decode(BrowserState.encode(s))!!
        assertEquals(42L, d.process)
        assertEquals(s.root, d.root)
        assertEquals(s.su, d.su)
        assertEquals(s.savedAt, d.savedAt)
        assertEquals(s.giants, d.giants)
        assertEquals(SORT_NAME, d.sort)
        assertTrue(d.apparent)
        assertEquals(1234, d.scroll)
        assertChain(s.chain, d.chain)
        val c = d.cursor!!
        assertChain(s.cursor!!.chain, c.chain)
        assertEquals(7, c.index)
        assertFalse(c.quiet)
    }

    @Test fun codecRootFolderNoCursorQuietAndSu() {
        val s = saved(root = "/data/media/0", su = true, giants = true, chain = emptyList(), cursor = null)
        val d = BrowserState.decode(BrowserState.encode(s))!!
        assertTrue(d.su); assertTrue(d.giants)
        assertEquals(0, d.chain.size)
        assertNull(d.cursor)
        val q = BrowserState.decode(BrowserState.encode(saved(cursor = BrowserState.Cursor(listOf(b("a")), 0, quiet = true))))!!
        assertTrue(q.cursor!!.quiet)
        // Корень с не-ASCII символами — байты UTF-8, не modified UTF-8.
        val r = BrowserState.decode(BrowserState.encode(saved(root = "/storage/Фото 😀")))!!
        assertEquals("/storage/Фото 😀", r.root)
    }

    @Test fun codecRejectsGarbage() {
        assertNull(BrowserState.decode(null))
        assertNull(BrowserState.decode(ByteArray(0)))
        assertNull(BrowserState.decode(byteArrayOf(99, 1, 2, 3)))
        val good = BrowserState.encode(saved())
        // Обрезанный на любом байте — не состояние (и не исключение).
        for (k in 0 until good.size) assertNull("cut at $k", BrowserState.decode(good.copyOf(k)))
        // Хвост после конца — тоже негодно.
        assertNull(BrowserState.decode(good + byteArrayOf(0)))
        // Недопустимое имя в цепочке («..», пустое) — не состояние.
        assertNull(BrowserState.decode(BrowserState.encode(saved(chain = listOf(b(".."))))))
        assertNull(BrowserState.decode(BrowserState.encode(saved(chain = listOf(b("a"), ByteArray(0)), cursor = null))))
    }

    private fun decide(s: BrowserState.Saved = saved(), at: Long = now, live: BrowserState.Live? = null,
                       cache: String? = s.root, rootExists: Boolean = true, deleting: Boolean = false,
                       giants: Boolean = s.giants) =
        BrowserState.decide(s, at, live, cache, rootExists, deleting, giants)

    @Test fun cacheWhenNoLiveTree() {
        assertEquals(BrowserState.Restore.CACHE, decide())
        // Нет кэша этого корня — обычный старт.
        assertEquals(BrowserState.Restore.NONE, decide(cache = null))
        // Запись кэша — другого корня (не та запись): не восстанавливать.
        assertEquals(BrowserState.Restore.NONE, decide(cache = "/storage/1234-ABCD"))
    }

    @Test fun liveTreeOfTheSameRootAndSuMode() {
        assertEquals(BrowserState.Restore.LIVE, decide(live = BrowserState.Live("/storage/emulated/0", false)))
        // Корень сменился или режим su — обычный старт (не кэш поверх живого чужого дерева).
        assertEquals(BrowserState.Restore.NONE, decide(live = BrowserState.Live("/data", true)))
        assertEquals(BrowserState.Restore.NONE, decide(live = BrowserState.Live("/storage/emulated/0", true)))
    }

    @Test fun at24HoursAndNotAfter() {
        assertEquals(BrowserState.Restore.CACHE, decide(saved(at = now - BrowserState.MAX_AGE_MS)))
        assertEquals(BrowserState.Restore.NONE, decide(saved(at = now - BrowserState.MAX_AGE_MS - 1)))
        // Часы ушли назад (состояние «из будущего»): не верим.
        assertEquals(BrowserState.Restore.NONE, decide(saved(at = now + 1)))
        assertTrue(BrowserState.fresh(now, now))
    }

    @Test fun neverDuringDeleteOrWithoutTheVolumeOrInAnotherMode() {
        assertEquals(BrowserState.Restore.NONE, decide(deleting = true))
        assertEquals(BrowserState.Restore.NONE, decide(live = BrowserState.Live("/storage/emulated/0", false), deleting = true))
        assertEquals(BrowserState.Restore.NONE, decide(rootExists = false))
        // Экран открыт в другом режиме («гиганты» / папки), чем сохранён.
        assertEquals(BrowserState.Restore.NONE, decide(giants = true))
    }

    @Test fun cursorRowFoundOrNeighbourClamped() {
        assertEquals(3, CursorPlace.row(found = 3, index = 9, n = 10))
        // Объекта нет — та же позиция, в пределах списка.
        assertEquals(5, CursorPlace.row(found = -1, index = 5, n = 10))
        assertEquals(9, CursorPlace.row(found = -1, index = 12, n = 10))
        assertEquals(0, CursorPlace.row(found = -1, index = -3, n = 10))
        // Пустая папка — курсора нет.
        assertEquals(-1, CursorPlace.row(found = -1, index = 0, n = 0))
    }

    @Test fun goneNoteOnlyWhenNotQuiet() {
        assertTrue(CursorPlace.noteGone(found = -1, quiet = false))
        // После удаления — без заметки: итог удаления уже в подвале.
        assertFalse(CursorPlace.noteGone(found = -1, quiet = true))
        assertFalse(CursorPlace.noteGone(found = 2, quiet = false))
    }

    @Test fun cursorBelongsToItsFolder() {
        val folder = listOf(b("DCIM"), raw(0xff))
        assertTrue(CursorPlace.inFolder(folder + listOf(b("x")), folder))
        assertFalse(CursorPlace.inFolder(listOf(b("DCIM"), raw(0xfe), b("x")), folder))
        assertFalse(CursorPlace.inFolder(folder, folder))
        assertFalse(CursorPlace.inFolder(folder + listOf(b("x"), b("y")), folder))
        assertTrue(CursorPlace.inFolder(listOf(b("x")), emptyList()))
    }

    /** Не больше двух амберных акцентов: вспышка — только если других меньше двух. */
    @Test fun flashKeepsTheAccentBudget() {
        assertEquals(0, CursorPlace.accents(errors = false, newerFilled = false, badgeAmber = false))
        assertEquals(3, CursorPlace.accents(errors = true, newerFilled = true, badgeAmber = true))
        assertTrue(CursorPlace.flash(motion = true, accents = 0))
        assertTrue(CursorPlace.flash(motion = true, accents = 1))
        assertFalse(CursorPlace.flash(motion = true, accents = 2))
        // Без анимаций — только постоянный блок.
        assertFalse(CursorPlace.flash(motion = false, accents = 0))
    }

    @Test fun noticeTimeoutIsAtLeastFourSeconds() {
        assertEquals(4000L, NoticeTime.ms(recommended = 0))
        assertEquals(4000L, NoticeTime.ms(recommended = 3000))
        assertEquals(12_000L, NoticeTime.ms(recommended = 12_000))
        // С TalkBack — дольше, даже если система не советует.
        assertEquals(NoticeTime.SPOKEN_MS, NoticeTime.ms(recommended = 4000, spoken = true))
        assertEquals(20_000L, NoticeTime.ms(recommended = 20_000, spoken = true))
    }

    @Test fun processTokenChangesOnNewProcess() {
        val p = BrowserState.process
        BrowserState.testNewProcess()
        assertNotNull(BrowserState.process)
        assertTrue(p != BrowserState.process)
    }
}
