package dev.ancdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Флаг «обновить сам, сохранив путь»: взвести → дождаться дерева → подставить → итог. */
class AutoPromoteTest {
    private val path = listOf("DCIM".toByteArray(), "Camera".toByteArray())
    private val mib = 1L shl 20

    private fun footer(o: AutoPromote.Outcome) = (o as AutoPromote.Outcome.Footer).text

    @Test fun idleNeverPromotes() {
        val a = AutoPromote()
        assertNull(a.request)
        assertFalse(a.ready(newer = true, busy = false, sheetOpen = false))
        assertFalse(a.failed(newer = false, refreshing = false))
        assertNull(a.take())
    }

    /** Итог удаления r ≠ 0 → обновление → подстановка → «освобождено …» в подвале. */
    @Test fun deleteOutcomeRefreshesThenFooter() {
        val a = AutoPromote()
        a.afterDelete(path, "Camera", 10 * mib)
        assertFalse("дерево ещё не готово", a.ready(newer = false, busy = false, sheetOpen = false))
        assertFalse("скан идёт — не провал", a.failed(newer = false, refreshing = true))
        assertTrue(a.ready(newer = true, busy = false, sheetOpen = false))
        val r = a.take()!!
        assertNull("флаг снят", a.request)
        // узел остался (удалён не весь): освобождено разницу, остаток в списке
        assertEquals("освобождено 6.0 MiB · остаток в списке", footer(AutoPromote.outcome(r, exact = true, disk = 4 * mib)))
        // узла больше нет: освобождено всё
        assertEquals("освобождено 10.0 MiB", footer(AutoPromote.outcome(r, exact = false, disk = 0)))
        // узел вырос с момента подтверждения — не «минус»
        assertEquals("освобождено 0 B · остаток в списке", footer(AutoPromote.outcome(r, exact = true, disk = 11 * mib)))
    }

    /** Никогда не подставлять, пока идёт удаление или открыт лист. */
    @Test fun neverPromotesWhileBusyOrSheetOpen() {
        val a = AutoPromote()
        a.afterDelete(path, "Camera", mib)
        assertFalse(a.ready(newer = true, busy = true, sheetOpen = false))
        assertFalse(a.ready(newer = true, busy = false, sheetOpen = true))
        assertTrue(a.ready(newer = true, busy = false, sheetOpen = false))
    }

    /** Долгий тап по каталогу кэша → обновление → лист того же каталога; пропал — подвал. */
    @Test fun askDeleteOnCacheRefreshesThenSheet() {
        val a = AutoPromote()
        a.beforeDelete(path, "Camera")
        assertTrue(a.ready(newer = true, busy = false, sheetOpen = false))
        val r = a.take()!!
        assertSame(AutoPromote.Outcome.Sheet, AutoPromote.outcome(r, exact = true, disk = 0))
        assertEquals("“Camera” уже нет на диске", footer(AutoPromote.outcome(r, exact = false, disk = 0)))
    }

    /** Навигация отменяет ждущий лист, но не итог удаления. */
    @Test fun navigationCancelsOnlyThePendingSheet() {
        val a = AutoPromote()
        a.beforeDelete(path, "Camera")
        a.cancelSheet()
        assertNull(a.request)
        assertFalse(a.ready(newer = true, busy = false, sheetOpen = false))

        a.afterDelete(path, "Camera", mib)
        a.cancelSheet()
        assertTrue(a.ready(newer = true, busy = false, sheetOpen = false))
    }

    /** Один флаг: новый запрос заменяет прежний. */
    @Test fun oneFlagLastRequestWins() {
        val a = AutoPromote()
        a.afterDelete(path, "Camera", mib)
        a.beforeDelete(listOf("Download".toByteArray()), "Download")
        assertNull(a.request!!.delDisk)
        assertEquals("Download", a.request!!.name)
    }

    /** Скан не удался: дерева нет, скан не идёт и не ждёт. */
    @Test fun refreshFailure() {
        val a = AutoPromote()
        a.beforeDelete(path, "Camera")
        assertFalse(a.failed(newer = true, refreshing = false))
        assertFalse(a.failed(newer = false, refreshing = true))
        assertTrue(a.failed(newer = false, refreshing = false))
    }
}
