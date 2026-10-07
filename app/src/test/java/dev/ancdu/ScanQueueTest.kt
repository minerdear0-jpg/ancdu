package dev.ancdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Очередь сканов BgScan: FIFO без повторов — постановка не вытесняет ждущие цели. */
class ScanQueueTest {
    private val storage = ScanTarget.STORAGE
    private val fixture = ScanTarget("/storage/emulated/0/ancdu-test-x", false)
    private val data = ScanTarget("/data", true)

    /** Вторая постановка (новое удаление, скан главного экрана) не вытесняет первую. */
    @Test fun noBump() {
        val q = ScanQueue()
        q += fixture
        q += storage
        q += data
        assertEquals(fixture, q.pop())
        assertEquals(storage, q.pop())
        assertEquals(data, q.pop())
        assertNull(q.pop())
        assertTrue(q.isEmpty)
    }

    /** Повтор той же цели — одна запись, место прежнее. */
    @Test fun dedup() {
        val q = ScanQueue()
        q += fixture
        q += storage
        q += fixture
        assertEquals(fixture, q.pop())
        assertEquals(storage, q.pop())
        assertNull(q.pop())
    }

    /** Отмена запроса снимает только его цель. */
    @Test fun removeOnlyItsOwn() {
        val q = ScanQueue()
        q += fixture
        q += data
        q += storage
        q.remove(data)
        q.remove(ScanTarget("/data", false))   // тот же путь без su — другой цели нет
        assertFalse(data in q)
        assertEquals(fixture, q.pop())
        assertEquals(storage, q.pop())
        assertNull(q.pop())
    }

    /** «Скан хранилища идёт или ждёт»: идёт он сам или он в очереди за другим корнем. */
    @Test fun storageActiveWithQueuedStorage() {
        val q = ScanQueue()
        assertFalse(q.active(storage, running = null))
        assertTrue(q.active(storage, running = storage))
        assertFalse(q.active(storage, running = data))
        q += storage
        assertTrue("в очереди за обновлением /data", q.active(storage, running = data))
        assertTrue(q.active(storage, running = null))
        q.remove(storage)
        assertFalse(q.active(storage, running = data))
    }

    /** root отклонён после постановки: su-цели выбрасываются, остальные идут по порядку. */
    @Test fun deniedRootDropsSuTargets() {
        val q = ScanQueue()
        q += data
        q += fixture
        q += ScanTarget("/data/data", true)
        q += storage
        assertEquals(fixture, q.next(rootDenied = true))
        assertEquals(storage, q.next(rootDenied = true))
        assertNull(q.next(rootDenied = true))
        assertTrue("su-цели выброшены, не отложены", q.isEmpty)
        // root не отклонён — su-цель запускается как обычно
        q += data
        assertEquals(data, q.next(rootDenied = false))
    }

    /**
     * Состояние скана дерева браузера (BgScan.stateFor): тот же корень И тот же режим su — идёт
     * или ждёт; другой режим или другой корень — NONE.
     */
    @Test fun stateForTarget() {
        val q = ScanQueue()
        val dataNoSu = ScanTarget("/data", false)
        assertEquals(ScanState.NONE, q.state(data, running = null))
        assertEquals(ScanState.RUNNING, q.state(data, running = data))
        assertEquals("другой режим su", ScanState.NONE, q.state(dataNoSu, running = data))
        assertEquals(ScanState.NONE, q.state(fixture, running = data))
        q += fixture
        assertEquals(ScanState.QUEUED, q.state(fixture, running = data))
        assertEquals(ScanState.QUEUED, q.state(fixture, running = null))
        assertEquals(ScanState.NONE, q.state(ScanTarget(fixture.root, true), running = data))
        q.remove(fixture)
        assertEquals(ScanState.NONE, q.state(fixture, running = data))
    }
}
