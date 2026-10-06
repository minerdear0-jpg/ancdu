package dev.ancdu

import dev.ancdu.XmlTxt.Companion.EN
import dev.ancdu.XmlTxt.Companion.RU
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteProgressTest {
    @Test fun counterLine() {
        assertEquals("12${Fmt.NBSP}340 / 69${Fmt.NBSP}370 эл. · 0:12", DeleteProgress.line(RU, 12_340, 69_370, 12_400))
        assertEquals("0 / 1 эл. · 0:00", DeleteProgress.line(RU, 0, 1, 0))
        assertEquals("5 / 5 эл. · 1:05", DeleteProgress.line(RU, 9, 5, 65_000)) // done ≤ max
        assertEquals("0 / 5 эл. · 0:00", DeleteProgress.line(RU, -3, 5, -10))
        assertEquals("12,340 / 69,370 items · 0:12", DeleteProgress.line(EN, 12_340, 69_370, 12_400))
        assertEquals("0 / 1 item · 0:00", DeleteProgress.line(EN, 0, 1, 0))
    }

    @Test fun elapsed() {
        assertEquals("0:59", DeleteProgress.elapsed(59_999))
        assertEquals("10:00", DeleteProgress.elapsed(600_000))
        assertEquals("1:00:01", DeleteProgress.elapsed(3_601_000))
    }

    @Test fun percentClamp() {
        assertEquals(0, DeleteProgress.permille(0, 100))
        assertEquals(500, DeleteProgress.permille(50, 100))
        assertEquals(1000, DeleteProgress.permille(150, 100))
        assertEquals(0, DeleteProgress.permille(5, 0))
        assertEquals(999, DeleteProgress.permille(68_999, 69_000))
        assertEquals(9, DeleteProgress.decile(68_999, 69_000))
        assertEquals(10, DeleteProgress.decile(69_000, 69_000))
        assertEquals("Удалено 30%", DeleteProgress.announce(RU, 31, 100))
        assertEquals("Deleted 30%", DeleteProgress.announce(EN, 31, 100))
        // большие счётчики без переполнения
        assertEquals(500, DeleteProgress.permille(3_000_000_000L, 6_000_000_000L))
    }

    @Test fun cancelledVsStopped() {
        assertEquals(true, DeleteProgress.isCancelled(-DeleteProgress.EINTR, 0))
        assertEquals(false, DeleteProgress.isCancelled(-DeleteProgress.EINTR, 1))
        assertEquals(false, DeleteProgress.isCancelled(0, 0))
        assertEquals(false, DeleteProgress.isCancelled(-5, 0))
        assertEquals("освобождено 1,5${Fmt.NBSP}МиБ", DeleteProgress.freed(RU, 3L shl 19))
        assertEquals("freed 1.5${Fmt.NBSP}MiB", DeleteProgress.freed(EN, 3L shl 19))
        assertEquals("Удаление отменено — ничего не удалено.", DeleteProgress.cancelled(RU))
    }

    @Test fun totalAndTexts() {
        assertEquals(1L, DeleteProgress.total(0))
        assertEquals(69_370L, DeleteProgress.total(69_370))
        assertEquals("Удаление «DCIM»", DeleteProgress.title(RU, "DCIM"))
        assertEquals("Deleting “DCIM”", DeleteProgress.title(EN, "DCIM"))
        assertEquals("обновляю дерево…", DeleteProgress.refreshing(RU))
        assertEquals("refreshing tree…", DeleteProgress.refreshing(EN))
        assertEquals("освобождено 1,5${Fmt.NBSP}МиБ · остаток в списке", DeleteProgress.freedLeft(RU, 3L shl 19))
        assertEquals("“DCIM” уже нет на диске", DeleteProgress.gone(RU, "DCIM"))
    }

    /** Итог удаления → обновить дерево; «ничего не удалено» — нечего обновлять. */
    @Test fun refreshAfterDelete() {
        val eperm = -1; val eio = -5; val eacces = -13
        val eintr = -DeleteProgress.EINTR; val eloop = -DeleteProgress.ELOOP
        // полный успех: csr_remove уже обновил дерево
        assertFalse(DeleteProgress.refreshAfter(0, viaRoot = false, done = 10, dir = true))
        assertFalse(DeleteProgress.refreshAfter(0, viaRoot = true, done = 10, dir = true))
        // остановлено после начала, частично, ошибка — обновить
        assertTrue(DeleteProgress.refreshAfter(eintr, viaRoot = false, done = 1, dir = true))
        assertTrue(DeleteProgress.refreshAfter(eintr, viaRoot = true, done = 500, dir = true))
        for (r in listOf(eio, eacces)) {
            assertTrue("$r", DeleteProgress.refreshAfter(r, viaRoot = false, done = 0, dir = true))
            assertTrue("$r root", DeleteProgress.refreshAfter(r, viaRoot = true, done = 0, dir = true))
        }
        // без root -EPERM даёт сам rm_tree — часть могла удалиться
        assertTrue(DeleteProgress.refreshAfter(eperm, viaRoot = false, done = 0, dir = true))
        // ничего не удалено: su отказал, симлинк в пути, «Стоп» до начала
        assertFalse(DeleteProgress.refreshAfter(eperm, viaRoot = true, done = 0, dir = true))
        assertFalse(DeleteProgress.refreshAfter(eloop, viaRoot = true, done = 0, dir = true))
        assertFalse(DeleteProgress.refreshAfter(eloop, viaRoot = false, done = 0, dir = true))
        assertFalse(DeleteProgress.refreshAfter(eintr, viaRoot = false, done = 0, dir = true))
        // файл: удалён или нет — сканировать нечего
        for (r in listOf(eio, eacces, eperm, eintr))
            assertFalse("$r file", DeleteProgress.refreshAfter(r, viaRoot = false, done = 1, dir = false))
    }
}
