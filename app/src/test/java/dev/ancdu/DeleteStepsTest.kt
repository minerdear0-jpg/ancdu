package dev.ancdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Порядок шагов удаления (Holder.delete на io) с подделками массового шага и ядра. */
class DeleteStepsTest {
    @Test fun nativeAlwaysRunsAfterBulk() {
        val log = ArrayList<String>()
        val r = DeleteSteps.run(bulk = { log += "bulk" }, arm = { log += "arm"; true }, native = { log += "native"; 0 })
        assertEquals(0, r)
        assertEquals(listOf("bulk", "arm", "native"), log)
    }

    @Test fun nativeRunsWithoutBulk() {
        val log = ArrayList<String>()
        assertEquals(-5, DeleteSteps.run(bulk = null, arm = { true }, native = { log += "native"; -5 }))
        assertEquals(listOf("native"), log)
    }

    @Test fun bulkFailureFallsThroughToNative() {
        for (e in listOf(SecurityException("s"), IllegalArgumentException("i"), RuntimeException("r"))) {
            val errs = ArrayList<Throwable>()
            var ran = false
            val r = DeleteSteps.run(bulk = { throw e }, arm = { true }, native = { ran = true; 0 },
                onBulkError = { errs += it })
            assertEquals(0, r)
            assertTrue(ran)
            assertEquals(listOf<Throwable>(e), errs)
        }
    }

    @Test fun stopDuringBulkSkipsNative() {
        var stop = false
        var ran = false
        val r = DeleteSteps.run(bulk = { stop = true }, arm = { !stop }, native = { ran = true; 0 })
        assertEquals(-DeleteProgress.EINTR, r)
        assertTrue(!ran)
    }

    @Test fun stoppedResultReadsAsStoppedOrCancelled() {
        // Стоп во время массового шага: удалено N строк — «остановлено», 0 — «отменено».
        val r = -DeleteProgress.EINTR
        assertTrue(DeleteProgress.isCancelled(r, DeleteSteps.done(0, 0)))
        assertTrue(!DeleteProgress.isCancelled(r, DeleteSteps.done(1500, 0)))
        assertEquals("Удаление остановлено — удалено 1 500 из 5 001. Пересканируйте.",
            DeleteProgress.stopped(DeleteSteps.done(1500, 0), 5001))
    }

    @Test fun progressSumsRowsAndNativeClamped() {
        assertEquals(0L, DeleteSteps.done(0, 0))
        assertEquals(5001L, DeleteSteps.done(5000, 1))     // строки файлов + каталог от ядра
        assertEquals(7L, DeleteSteps.done(7, -3))
        assertEquals(3L, DeleteSteps.done(-1, 3))
        assertEquals(Long.MAX_VALUE, DeleteSteps.done(Long.MAX_VALUE, 5))
        // Строк больше, чем items (устаревшие строки): диалог держит ≤ total.
        assertEquals(1000, DeleteProgress.permille(DeleteSteps.done(6000, 10), 5001))
        assertEquals("5 001 / 5 001 эл. · 0:06", DeleteProgress.line(DeleteSteps.done(6000, 10), 5001, 6200))
    }
}
