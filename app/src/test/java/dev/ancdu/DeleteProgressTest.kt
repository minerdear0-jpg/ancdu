package dev.ancdu

import org.junit.Assert.assertEquals
import org.junit.Test

class DeleteProgressTest {
    @Test fun counterLine() {
        assertEquals("12 340 / 69 370 эл. · 0:12", DeleteProgress.line(12_340, 69_370, 12_400))
        assertEquals("0 / 1 эл. · 0:00", DeleteProgress.line(0, 1, 0))
        assertEquals("5 / 5 эл. · 1:05", DeleteProgress.line(9, 5, 65_000)) // done ≤ max
        assertEquals("0 / 5 эл. · 0:00", DeleteProgress.line(-3, 5, -10))
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
        assertEquals("Удалено 30%", DeleteProgress.announce(31, 100))
        // большие счётчики без переполнения
        assertEquals(500, DeleteProgress.permille(3_000_000_000L, 6_000_000_000L))
    }

    @Test fun cancelledVsStopped() {
        assertEquals(true, DeleteProgress.isCancelled(-DeleteProgress.EINTR, 0))
        assertEquals(false, DeleteProgress.isCancelled(-DeleteProgress.EINTR, 1))
        assertEquals(false, DeleteProgress.isCancelled(0, 0))
        assertEquals(false, DeleteProgress.isCancelled(-5, 0))
        assertEquals("освобождено 1.5 MiB", DeleteProgress.freed(3L shl 19))
    }

    @Test fun totalAndTexts() {
        assertEquals(1L, DeleteProgress.total(0))
        assertEquals(69_370L, DeleteProgress.total(69_370))
        assertEquals("Удаление «DCIM»", DeleteProgress.title("DCIM"))
        assertEquals("Удаление остановлено — удалено 1 200 из 3 000. Пересканируйте.",
            DeleteProgress.stopped(1_200, 3_000))
        assertEquals("Удаление остановлено — удалено 3 000 из 3 000. Пересканируйте.",
            DeleteProgress.stopped(3_100, 3_000))
    }
}
