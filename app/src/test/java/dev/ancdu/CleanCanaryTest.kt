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
        /** Прочие строки MediaStore под base: путь → _id. */
        val otherRows: MutableMap<String, Long> = mutableMapOf(),
        /** Прочие пути, существующие на диске. */
        val present: Set<String> = emptySet(),
        val dirStays: Boolean = false,
    ) : CanaryEnv {
        val base = "/storage/emulated/0/Android/media/dev.ancdu"
        val created: String? get() = dirMade?.let { "$it/canary.txt" }
        var file = false
        var row = false
        var dir = false
        var cleaned: String? = null
        val rowOnlyDeleted = ArrayList<String>()
        val scanned = ArrayList<String>()
        init { dirMade?.let { otherRows[it] = 99L } } // строка самого каталога канарейки
        override fun makeDir(): String? = dirMade.also { dir = it != null }
        override fun writeFile(dir: String): String {
            throwOnWrite?.let { throw it }
            file = true
            return "$dir/canary.txt"
        }
        override fun awaitRow(path: String): Long? = if (rowAppears) { row = true; 7L } else null
        override fun deleteRowOnly(id: Long, path: String): Int {
            if (path != created) {
                check(otherRows[path] == id) { "чужой _id для $path" }
                rowOnlyDeleted += path; otherRows.remove(path); return 1
            }
            throwOnDelete?.let { throw it }
            if (!rowStays) row = false
            if (unlinks) file = false
            return deleteCount ?: if (rowStays) 0 else 1
        }
        override fun fileExists(path: String) = when (path) {
            created -> file
            dirMade -> dir
            else -> path in present
        }
        override fun rowExists(path: String) = row
        override fun cleanup(dir: String) { cleaned = dir; file = false; if (!dirStays) this.dir = false }
        override fun base() = base
        override fun rowPaths(base: String) = otherRows.keys.filter { it.startsWith("$base/") }
        override fun rowId(path: String) = otherRows[path]
        override fun scan(path: String) { scanned += path }
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
            override fun base(): String? = throw AssertionError()
            override fun rowPaths(base: String): List<String> = throw AssertionError()
            override fun rowId(path: String): Long? = throw AssertionError()
            override fun scan(path: String) = throw AssertionError()
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

    @Test fun provenCanaryDropsItsOwnDirRowOnly() {
        val e = Env()
        assertTrue(CleanCanary.rowsOnlyWorks(e))
        assertEquals(listOf(e.dirMade), e.rowOnlyDeleted)
        assertEquals(emptyList<String>(), e.scanned)
    }

    @Test fun failedCanaryScansItsDirInstead() {
        val e = Env(unlinks = true)
        assertFalse(CleanCanary.rowsOnlyWorks(e))
        assertEquals(emptyList<String>(), e.rowOnlyDeleted)
        assertEquals(listOf(e.dirMade), e.scanned)
    }

    @Test fun dirThatSurvivedCleanupKeepsItsRow() {
        val e = Env(dirStays = true)
        assertTrue(CleanCanary.rowsOnlyWorks(e))
        assertEquals(emptyList<String>(), e.rowOnlyDeleted)
        assertEquals(emptyList<String>(), e.scanned)
    }

    @Test fun sweepsOnlyAbsentEarlierCanaryDirs() {
        val b = "/storage/emulated/0/Android/media/dev.ancdu"
        val rows = mutableMapOf("$b/canary-old" to 1L, "$b/canary-live" to 2L, "$b/canary-x/canary.txt" to 3L,
            "$b/other" to 4L, "$b/canary-" to 5L, "$b/canary-old2" to 6L)
        val e = Env(otherRows = rows, present = setOf("$b/canary-live"))
        assertTrue(CleanCanary.rowsOnlyWorks(e))
        assertEquals(setOf(e.dirMade, "$b/canary-old", "$b/canary-old2"), e.rowOnlyDeleted.toSet())
        assertEquals(setOf("$b/canary-live", "$b/canary-x/canary.txt", "$b/other", "$b/canary-"), rows.keys)
    }

    @Test fun sweepAfterFailedCanaryUsesScan() {
        val b = "/storage/emulated/0/Android/media/dev.ancdu"
        val e = Env(rowAppears = false, otherRows = mutableMapOf("$b/canary-old" to 1L))
        assertFalse(CleanCanary.rowsOnlyWorks(e))
        assertEquals(emptyList<String>(), e.rowOnlyDeleted)
        assertEquals(setOf(e.dirMade, "$b/canary-old"), e.scanned.toSet())
    }

    @Test fun canaryDirGuard() {
        val b = "/storage/emulated/0/Android/media/dev.ancdu"
        assertTrue(CleanCanary.isCanaryDir("$b/canary-123", b))
        for (p in listOf("$b/canary-", "$b/canary-1/x", "$b/canaryx", "$b/other", "$b", "/storage/emulated/0/canary-1",
                "$b-evil/canary-1", "$b/sub/canary-1"))
            assertFalse(p, CleanCanary.isCanaryDir(p, b))
        assertFalse(CleanCanary.isCanaryDir("/x/canary-1", "/x/"))
        assertFalse(CleanCanary.isCanaryDir("x/canary-1", "x"))
    }
}
