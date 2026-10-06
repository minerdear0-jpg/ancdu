package dev.ancdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanCanaryTest {
    /** Подделка MediaProvider+диска; [unlinks] — deletedata=false не поддержан (файл удаляется). */
    private class Env(
        val dirMade: String? = "/storage/emulated/0/Android/media/dev.ancdu/canary-1",
        val throwOnWrite: Exception? = null,
        val rowAppears: Boolean = true,
        val unlinks: Boolean = false,
        val deleteCount: Int? = null,
        val throwOnDelete: Exception? = null,
        val rowStays: Boolean = false,
    ) : CanaryEnv {
        val created: String? get() = dirMade?.let { "$it/canary.txt" }
        var file = false
        var row = false
        var cleaned: String? = null
        override fun makeDir(): String? = dirMade
        override fun writeFile(dir: String): String {
            throwOnWrite?.let { throw it }
            file = true
            return "$dir/canary.txt"
        }
        override fun awaitRow(path: String): Long? = if (rowAppears) { row = true; 7L } else null
        override fun deleteRowOnly(id: Long, path: String): Int {
            throwOnDelete?.let { throw it }
            if (!rowStays) row = false
            if (unlinks) file = false
            return deleteCount ?: if (rowStays) 0 else 1
        }
        override fun fileExists(path: String) = file
        override fun rowExists(path: String) = row
        override fun cleanup(dir: String) { cleaned = dir; file = false }
    }

    @Test fun passesOnlyWhenRowGoneAndFileKept() {
        val e = Env()
        assertTrue(CleanCanary.rowsOnlyWorks(e))
        assertEquals(e.dirMade, e.cleaned)
        assertFalse(e.file)
    }

    @Test fun failsIfFileWasUnlinked() {
        val e = Env(unlinks = true)
        assertFalse(CleanCanary.rowsOnlyWorks(e))
        assertEquals(e.dirMade, e.cleaned)
    }

    @Test fun failsOnEveryOtherAnomaly() {
        for (e in listOf(Env(rowAppears = false), Env(rowStays = true), Env(deleteCount = 2),
                Env(throwOnDelete = IllegalArgumentException("unknown param")),
                Env(throwOnDelete = SecurityException("no")))) {
            assertFalse(CleanCanary.rowsOnlyWorks(e))
            assertEquals(e.dirMade, e.cleaned) // уборка — всегда
        }
        assertFalse(CleanCanary.rowsOnlyWorks(Env(dirMade = null)))
        val boom = object : CanaryEnv {
            override fun makeDir(): String = throw java.io.IOException("нет места")
            override fun writeFile(dir: String): String = throw AssertionError()
            override fun awaitRow(path: String): Long? = throw AssertionError()
            override fun deleteRowOnly(id: Long, path: String): Int = throw AssertionError()
            override fun fileExists(path: String): Boolean = throw AssertionError()
            override fun rowExists(path: String): Boolean = throw AssertionError()
            override fun cleanup(dir: String) = throw AssertionError()
        }
        assertFalse(CleanCanary.rowsOnlyWorks(boom))
    }

    @Test fun cleanupFailureDoesNotChangeVerdict() {
        val e = object : CanaryEnv by Env() {
            override fun cleanup(dir: String) = throw SecurityException("x")
        }
        assertTrue(CleanCanary.rowsOnlyWorks(e))
    }

    @Test fun modeNeedsProvenRowsOnly() {
        assertEquals(CleanCanary.Mode.ROWS_ONLY, CleanCanary.mode(rowsOnlyOk = true, pathExists = false))
        assertEquals(CleanCanary.Mode.SCAN, CleanCanary.mode(rowsOnlyOk = true, pathExists = true))
        assertEquals(CleanCanary.Mode.SCAN, CleanCanary.mode(rowsOnlyOk = false, pathExists = false))
        assertEquals(CleanCanary.Mode.SCAN, CleanCanary.mode(rowsOnlyOk = false, pathExists = true))
    }

    @Test fun writeFailureStillRemovesTheDir() {
        for (ex in listOf(java.io.IOException("ENOSPC"), SecurityException("x"), IllegalStateException("y"))) {
            val e = Env(throwOnWrite = ex)
            assertFalse(CleanCanary.rowsOnlyWorks(e))
            assertEquals(e.dirMade, e.cleaned)
        }
    }

    @Test fun incompleteRowsOnlyCleanupIsRescanned() {
        assertFalse(CleanCanary.needsScanAfter(MediaBulk.Outcome(10, false, null, matched = 10)))
        assertTrue(CleanCanary.needsScanAfter(MediaBulk.Outcome(10, true, null, matched = 10)))
        assertTrue(CleanCanary.needsScanAfter(MediaBulk.Outcome(0, false, IllegalStateException(), matched = 0)))
        assertTrue(CleanCanary.needsScanAfter(MediaBulk.Outcome(0, false, null, matched = 0)))   // ничего не удалено
        assertTrue(CleanCanary.needsScanAfter(MediaBulk.Outcome(7, false, null, matched = 10)))  // меньше найденного
    }

    @Test fun realRunsFeedTheRescanDecision() {
        val d = "/storage/emulated/0/gone"
        // всё удалено — сканер не нужен
        val ok = MediaBulk.run(FakeRows(listOf("$d/a", "$d/b")), d, true, stopped = { false }) {}
        assertEquals(2L, ok.matched)
        assertFalse(CleanCanary.needsScanAfter(ok))
        // строк не нашлось — 0 удалено, досверить
        assertTrue(CleanCanary.needsScanAfter(MediaBulk.run(FakeRows(emptyList()), d, true, stopped = { false }) {}))
        // MediaProvider удалил не всё
        val stubborn = object : MediaRows {
            val inner = FakeRows(listOf("$d/a", "$d/b", "$d/c"))
            override fun page(where: String, args: Array<String>, limit: Int) = inner.page(where, args, limit)
            override fun delete(ids: LongArray, where: String, args: Array<String>) = 1
        }
        val part = MediaBulk.run(stubborn, d, true, stopped = { false }) {}
        assertEquals(3L, part.matched)
        assertTrue(CleanCanary.needsScanAfter(part))
        // страница без продвижения — ошибка, досверить
        val stuck = object : MediaRows {
            override fun page(where: String, args: Array<String>, limit: Int) = listOf(MediaRow(Long.MIN_VALUE, "$d/a"))
            override fun delete(ids: LongArray, where: String, args: Array<String>) = 0
        }
        assertTrue(CleanCanary.needsScanAfter(MediaBulk.run(stuck, d, true, chunk = 1, stopped = { false }) {}))
    }
}
