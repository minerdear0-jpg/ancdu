package dev.ancdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Строка «что выросло» главного экрана без собственных удалений в ancdu после точки отсчёта. */
class OwnDeletesTest {
    private val mib = 1L shl 20
    private val gib = 1L shl 30
    private val root = Scans.STORAGE
    private val base = 1_000_000L

    private fun chain(vararg s: String) = s.map { it.toByteArray() }

    private fun entry(time: Long, names: List<ByteArray>, freed: Long?, code: Int = 0, rootKey: String = root,
                      interrupted: Boolean = false, freedLater: Long? = null, group: Boolean = false): LogEntry {
        val s = LogRec.Start(time * 1000, time, rootKey, false, names, group, 1, gib, false, false,
            count = if (group) 3 else 1, group = group)
        val e = if (interrupted) null else LogRec.End(time * 1000, time + 1, code, freed ?: -1L, 1)
        return LogEntry(s, e, freedLater, false)
    }

    @Test fun onlyCompletedOwnDeletesAfterTheBaseInThisRoot() {
        val es = listOf(
            entry(base + 10, chain("Movies", "a.mkv"), 5 * gib),             // counts
            entry(base - 10, chain("Movies", "old.mkv"), gib),               // before the base
            entry(base + 20, chain("x"), gib, rootKey = "/data/media/0"),    // another root
            entry(base + 30, chain("Download", "p"), null, code = -4),       // partial, freed unknown
            entry(base + 40, chain("Download", "q"), null, code = -4, freedLater = 3 * mib), // learned later
            entry(base + 50, chain("DCIM"), null, interrupted = true),       // interrupted: nothing known
            entry(base + 60, chain("Music"), 0L),                            // hard link: 0 freed
            entry(base + 70, chain("Pictures"), 2 * gib, group = true),      // group: its folder
        )
        val f = OwnDeletes.freed(es, root, base)
        assertEquals(listOf("Movies/a.mkv" to 5 * gib, "Download/q" to 3 * mib, "Pictures" to 2 * gib),
            f.map { it.chain.joinToString("/") { b -> String(b) } to it.bytes })
        // Корень сравнивается без «/» в конце.
        assertEquals(3, OwnDeletes.freed(es, "$root/", base).size)
    }

    /** Дерево: 0 ─ 1 Movies ─ 3 Series;  0 ─ 2 Download. Удалённого объекта в дереве уже нет — его ближайший предок. */
    private val parent = mapOf(1 to 0, 2 to 0, 3 to 1)
    private fun deepest(c: List<ByteArray>): Int {
        var cur = 0
        for (nm in c) cur = when (cur to String(nm)) {
            0 to "Movies" -> 1; 0 to "Download" -> 2; 1 to "Series" -> 3; else -> return cur
        }
        return cur
    }

    /** В дереве точки отсчёта удалённое лежит в «ушло» своей папки (delta.c пропускает F_DELETED). */
    @Test fun freedBytesGoToEveryAncestorCappedByGone() {
        val gone = mapOf(3 to 4 * gib, 1 to gib, 0 to mib)
        val own = OwnDeletes.byNode(listOf(OwnDeletes.Freed(chain("Movies", "Series", "e1.mkv"), 4 * gib),
            OwnDeletes.Freed(chain("Movies", "a.mkv"), gib), OwnDeletes.Freed(chain("Gone", "x"), mib)), ::deepest, gone) { parent[it] ?: -1 }
        assertEquals(4 * gib, own[3])
        assertEquals(5 * gib, own[1])
        assertNull(own[2])
        assertEquals(5 * gib + mib, own[0])
        // Насыщение вместо переполнения.
        val big = OwnDeletes.byNode(listOf(OwnDeletes.Freed(chain("Movies"), Long.MAX_VALUE),
            OwnDeletes.Freed(chain("Movies"), Long.MAX_VALUE)), ::deepest, mapOf(1 to Long.MAX_VALUE)) { parent[it] ?: -1 }
        assertEquals(Long.MAX_VALUE, big[0])
    }

    /** Файл создан после точки отсчёта и удалён в ancdu: в «ушло» его нет — ничего не возвращается, строки нет. */
    @Test fun newAfterBaseThenDeletedAddsNothing() {
        val own = OwnDeletes.byNode(listOf(OwnDeletes.Freed(chain("Movies", "new.mkv"), 5 * gib)), ::deepest, emptyMap()) { parent[it] ?: -1 }
        assertTrue(own.isEmpty())
        // Сырая Δ ±0 (создан и удалён между сканами) — строки нет.
        assertFalse(GrowthText.homeShown(OwnDeletes.corrected(0, own[0] ?: 0)))
    }

    /** Путь создан заново после удаления: цепочка доходит до НОВОГО узла (у файла «ушло» нет) — ничего не возвращается. */
    @Test fun recreatedPathAddsNothing() {
        // Movies/Series «пересоздан»: deepest() доходит до узла 3; «ушло» удалённого e1.mkv — у 3 нет (его нет в базе
        // как ушедшего: вместо него сопоставлен новый узел), у Movies тоже нет.
        val own = OwnDeletes.byNode(listOf(OwnDeletes.Freed(chain("Movies", "Series"), 4 * gib)), ::deepest, emptyMap()) { parent[it] ?: -1 }
        assertTrue(own.isEmpty())
        // Старое дерево (кэш до удаления): объект ещё в дереве, «ушло» пусто — двойного счёта нет.
        assertTrue(OwnDeletes.byNode(listOf(OwnDeletes.Freed(chain("Movies", "Series"), 4 * gib)), ::deepest,
            mapOf(1 to 4 * gib)) { parent[it] ?: -1 }.isEmpty())
    }

    /** Группа из разных папок: цепочка — общая папка, предел — «ушло» во всём её поддереве. */
    @Test fun mixedGroupIsCappedByGoneInItsSubtree() {
        // Общая папка — корень (0): удалено 6 ГиБ; в базе ушло 4 ГиБ под Series (3) и 1 ГиБ под Download (2).
        val gone = mapOf(3 to 4 * gib, 2 to gib)
        val own = OwnDeletes.byNode(listOf(OwnDeletes.Freed(emptyList(), 6 * gib, mixed = true)), ::deepest, gone) { parent[it] ?: -1 }
        assertEquals(5 * gib, own[0])
        // Поправка — только у корня (вниз не раздаётся: куда именно, журнал знает лишь для 20 имён).
        assertNull(own[1])
        // Группа одной папки: предел — «ушло» самой папки.
        val one = OwnDeletes.byNode(listOf(OwnDeletes.Freed(chain("Movies"), 3 * gib)), ::deepest, mapOf(1 to 2 * gib, 3 to gib)) { parent[it] ?: -1 }
        assertEquals(2 * gib, one[1]); assertEquals(2 * gib, one[0])
    }

    /** Удалил 5 ГиБ и вырос на 2 ГиБ в той же папке: сырая −3, поправка 5 (НЕ ограничена −Δ папки) → +2. */
    @Test fun deleteFiveGrowTwoShowsPlusTwo() {
        val own = OwnDeletes.byNode(listOf(OwnDeletes.Freed(chain("Movies", "big.mkv"), 5 * gib)), ::deepest, mapOf(1 to 5 * gib)) { parent[it] ?: -1 }
        assertEquals(2 * gib, OwnDeletes.corrected(-3 * gib, own[0]!!))
        assertEquals(2 * gib, OwnDeletes.corrected(-3 * gib, own[1]!!))
    }

    @Test fun signFlipsAndOwnDeletesOnlyHideTheLine() {
        // Удалил 5 ГиБ сам, а выросло на 1 ГиБ: сырая Δ −4 ГиБ, на главном — +1 ГиБ.
        assertEquals(gib, OwnDeletes.corrected(-4 * gib, 5 * gib))
        // Только собственные удаления: Δ 0 — строки нет.
        assertEquals(0L, OwnDeletes.corrected(-5 * gib, 5 * gib))
        assertFalse(GrowthText.homeShown(OwnDeletes.corrected(-5 * gib, 5 * gib)))
        assertTrue(GrowthText.homeShown(OwnDeletes.corrected(-5 * gib, 5 * gib + 2 * mib)))
        assertEquals(Long.MAX_VALUE, OwnDeletes.corrected(Long.MAX_VALUE, 1))
        assertEquals(-3L, OwnDeletes.corrected(-3, 0))
    }

    @Test fun mostlyPathFollowsTheCorrectedDelta() {
        // Сырые Δ: Movies −5 ГиБ (сам удалил 5 ГиБ), Download +1 ГиБ; корень −4 ГиБ.
        val raw = mapOf(0 to listOf(Mostly.Kid(1, -5 * gib, true), Mostly.Kid(2, gib, true)))
        val own = mapOf(0 to 5 * gib, 1 to 5 * gib)
        val rawPath = Mostly.path(-4 * gib, { raw[it].orEmpty() })
        assertEquals(listOf(1), rawPath)                    // «больше всего Movies» — по собственному удалению
        val fixed = Mostly.path(OwnDeletes.corrected(-4 * gib, own[0] ?: 0), { OwnDeletes.kids(raw[it].orEmpty(), own) })
        assertEquals(listOf(2), fixed)                      // на самом деле выросло Download
    }

    /** Журнал не приписывает освобождённого жёстким ссылкам (правило Task 30): ни одиночной, ни в группе. */
    @Test fun logFreedSkipsHardLinks() {
        val link = ItemResult("a", false, gib, 0, 1, hardlink = true)
        val plain = ItemResult("b", false, mib, 0, 1)
        assertEquals(0L, LogActions.end(1, 2, 0, listOf(link), total = 1).freed)
        assertEquals(gib, LogActions.end(1, 2, 0, listOf(ItemResult("a", false, gib, 0, 1)), total = 1).freed)
        assertEquals(mib, LogActions.end(1, 2, 0, listOf(link, plain), total = 2).freed)
    }
}
