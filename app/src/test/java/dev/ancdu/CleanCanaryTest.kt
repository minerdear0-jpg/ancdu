package dev.ancdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanCanaryTest {
    /** Подделка MediaProvider+диска; [unlinks] — deletedata=false не поддержан (файл удаляется). */
    private class Env(
        val created: String? = "/storage/emulated/0/Android/media/dev.ancdu/canary-1/canary.txt",
        val rowAppears: Boolean = true,
        val unlinks: Boolean = false,
        val deleteCount: Int? = null,
        val throwOnDelete: Exception? = null,
        val rowStays: Boolean = false,
    ) : CanaryEnv {
        var file = false
        var row = false
        var cleaned: String? = null
        override fun create(): String? { if (created != null) file = true; return created }
        override fun awaitRow(path: String): Long? = if (rowAppears) { row = true; 7L } else null
        override fun deleteRowOnly(id: Long, path: String): Int {
            throwOnDelete?.let { throw it }
            if (!rowStays) row = false
            if (unlinks) file = false
            return deleteCount ?: if (rowStays) 0 else 1
        }
        override fun fileExists(path: String) = file
        override fun rowExists(path: String) = row
        override fun cleanup(path: String) { cleaned = path; file = false }
    }

    @Test fun passesOnlyWhenRowGoneAndFileKept() {
        val e = Env()
        assertTrue(CleanCanary.rowsOnlyWorks(e))
        assertEquals(e.created, e.cleaned)
        assertFalse(e.file)
    }

    @Test fun failsIfFileWasUnlinked() {
        val e = Env(unlinks = true)
        assertFalse(CleanCanary.rowsOnlyWorks(e))
        assertEquals(e.created, e.cleaned)
    }

    @Test fun failsOnEveryOtherAnomaly() {
        for (e in listOf(Env(rowAppears = false), Env(rowStays = true), Env(deleteCount = 2),
                Env(throwOnDelete = IllegalArgumentException("unknown param")),
                Env(throwOnDelete = SecurityException("no")))) {
            assertFalse(CleanCanary.rowsOnlyWorks(e))
            assertEquals(e.created, e.cleaned) // уборка — всегда
        }
        assertFalse(CleanCanary.rowsOnlyWorks(Env(created = null)))
        val boom = object : CanaryEnv {
            override fun create(): String = throw java.io.IOException("нет места")
            override fun awaitRow(path: String): Long? = throw AssertionError()
            override fun deleteRowOnly(id: Long, path: String): Int = throw AssertionError()
            override fun fileExists(path: String): Boolean = throw AssertionError()
            override fun rowExists(path: String): Boolean = throw AssertionError()
            override fun cleanup(path: String) = throw AssertionError()
        }
        assertFalse(CleanCanary.rowsOnlyWorks(boom))
    }

    @Test fun cleanupFailureDoesNotChangeVerdict() {
        val e = object : CanaryEnv by Env() {
            override fun cleanup(path: String) = throw SecurityException("x")
        }
        assertTrue(CleanCanary.rowsOnlyWorks(e))
    }

    @Test fun modeNeedsProvenRowsOnly() {
        assertEquals(CleanCanary.Mode.ROWS_ONLY, CleanCanary.mode(rowsOnlyOk = true, pathExists = false))
        assertEquals(CleanCanary.Mode.SCAN, CleanCanary.mode(rowsOnlyOk = true, pathExists = true))
        assertEquals(CleanCanary.Mode.SCAN, CleanCanary.mode(rowsOnlyOk = false, pathExists = false))
        assertEquals(CleanCanary.Mode.SCAN, CleanCanary.mode(rowsOnlyOk = false, pathExists = true))
    }

    @Test fun interruptedRowsOnlyCleanupIsRescanned() {
        assertFalse(CleanCanary.needsScanAfter(MediaBulk.Outcome(10, false, null)))
        assertTrue(CleanCanary.needsScanAfter(MediaBulk.Outcome(10, true, null)))
        assertTrue(CleanCanary.needsScanAfter(MediaBulk.Outcome(0, false, IllegalStateException())))
    }
}
