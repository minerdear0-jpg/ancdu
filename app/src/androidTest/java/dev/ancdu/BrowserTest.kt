package dev.ancdu

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BrowserTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    /** Опрос с таймаутом: выходит при успехе, иначе после [ms]. */
    private fun waitFor(ms: Long = 10_000, ok: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            var r = false
            ins.runOnMainSync { r = ok() }
            if (r) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun scan(dir: File) {
        val h = scanned(dir)
        ins.runOnMainSync { Holder.set(h, Kind.SCAN, dir.path, "скан", false) }   // Holder.set — только главный поток
    }

    /** Готовое дерево [dir], ещё не в Holder. */
    private fun scanned(dir: File): Long {
        val h = Native.scanStart(dir.path, true, 2, IntArray(1))
        val p = LongArray(6)
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            Native.progress(h, p)
            if (p[0] != ST_RUNNING.toLong() || System.currentTimeMillis() > deadline) break
            Thread.sleep(25)
        }
        assertEquals(ST_DONE.toLong(), p[0])
        return h
    }

    /** Только с главного потока. */
    private fun resumedBrowser(): BrowserActivity? =
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<BrowserActivity>().firstOrNull()

    /** Удаление переживает пересоздание экрана: новый экземпляр не читает дерево, пока оно идёт. */
    @Test fun deleteSurvivesRecreate() {
        val ctx = ins.targetContext
        val dir = File(ctx.cacheDir, "br2").apply { deleteRecursively(); mkdirs() }
        File(dir, "keep.bin").writeBytes(ByteArray(50_000))
        File(dir, "gone.bin").writeBytes(ByteArray(10))
        scan(dir)
        val act1 = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        var count = 0
        ins.runOnMainSync { count = act1.list.source!!.count }
        assertEquals(2, count)

        // Заслонка на io: удаление встанет в очередь за ней и не начнётся, пока её не откроем.
        val gate = CountDownLatch(1)
        Holder.io.execute { gate.await(30, TimeUnit.SECONDS) }
        var r = Int.MIN_VALUE
        val deleter = Thread { r = act1.deleteBlocking(1) }.apply { start() } // по размеру: 1 — gone.bin
        assertTrue(waitFor { Holder.deleting })

        ins.runOnMainSync { act1.recreate() }
        assertTrue(waitFor { resumedBrowser().let { it != null && it !== act1 } })
        lateinit var act2: BrowserActivity
        ins.runOnMainSync { act2 = resumedBrowser()!! }
        assertNotSame(act1, act2)
        ins.runOnMainSync {
            assertTrue(act2.busy)
            assertNull(act2.list.source)
            assertEquals(0, act2.loads)
        }

        gate.countDown()
        deleter.join(30_000)
        assertFalse(deleter.isAlive)
        assertEquals(0, r)
        assertTrue(waitFor { !act2.busy && act2.list.source != null })
        ins.runOnMainSync {
            assertEquals(1, act2.loads)
            val src = act2.list.source!!
            assertEquals(1, src.count)
            val row = Row().also { src.bind(0, it) }
            assertEquals("keep.bin", row.name)
        }
        assertFalse(File(dir, "gone.bin").exists())
        assertTrue(File(dir, "keep.bin").exists())

        ins.runOnMainSync { act2.finish() }
        dir.deleteRecursively()
    }

    /**
     * Смена сессии при открытом браузере: Holder.set синхронно отцепляет его от старого дескриптора
     * (до того как free(old) уходит на io), затем экран пересоздаётся на новом дереве.
     */
    @Test fun setWhileBrowserResumedDetachesSynchronously() {
        val ctx = ins.targetContext
        val a = File(ctx.cacheDir, "br3a").apply { deleteRecursively(); mkdirs() }
        val b = File(ctx.cacheDir, "br3b").apply { deleteRecursively(); mkdirs() }
        File(a, "one.bin").writeBytes(ByteArray(10))
        File(a, "two.bin").writeBytes(ByteArray(20))
        File(b, "only.bin").writeBytes(ByteArray(30))
        scan(a)
        val act1 = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        var count = 0
        ins.runOnMainSync { count = act1.list.source!!.count }
        assertEquals(2, count)

        val hb = scanned(b)
        // Заслонка на io: free(old) не выполнится, пока не откроем, — отцепление не может «успеть» за счёт io.
        val gate = CountDownLatch(1)
        Holder.io.execute { gate.await(30, TimeUnit.SECONDS) }
        ins.runOnMainSync {
            Holder.set(hb, Kind.SCAN, b.path, "скан", false)
            assertNull(act1.list.source)   // отцеплен синхронно, внутри set
        }
        gate.countDown()

        assertTrue(waitFor { resumedBrowser().let { it != null && it !== act1 } })
        lateinit var act2: BrowserActivity
        ins.runOnMainSync { act2 = resumedBrowser()!! }
        ins.runOnMainSync {
            val src = act2.list.source!!
            assertEquals(1, src.count)
            val row = Row().also { src.bind(0, it) }
            assertEquals("only.bin", row.name)
        }
        ins.runOnMainSync { act2.finish() }
        a.deleteRecursively(); b.deleteRecursively()
    }

    @Test fun navigateSortDelete() {
        val ctx = ins.targetContext
        val dir = File(ctx.cacheDir, "br").apply { deleteRecursively(); mkdirs() }
        File(dir, "sub").mkdirs()
        File(dir, "sub/big.bin").writeBytes(ByteArray(300_000))
        File(dir, "a.txt").writeBytes(ByteArray(5000))
        File(dir, "b.txt").writeBytes(ByteArray(10))

        scan(dir)

        val act = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        val row = Row()
        // Чтения дерева — только с главного потока (контракт Native).
        fun name(i: Int): String {
            var s = ""
            ins.runOnMainSync { row.reset(); act.list.source!!.bind(i, row); s = row.name }
            return s
        }
        fun count(): Int { var c = 0; ins.runOnMainSync { c = act.list.source!!.count }; return c }

        assertEquals(3, count())
        assertEquals("sub/", name(0))
        ins.runOnMainSync { act.list.source!!.click(0) }
        assertNotEquals(0, act.node)
        assertEquals(1, count())
        assertEquals("big.bin", name(0))
        ins.runOnMainSync { act.onBackPressed() }
        assertEquals(0, act.node)

        ins.runOnMainSync { act.setSort(SORT_NAME) }
        assertEquals("a.txt", name(0))
        val bIndex = (0 until 3).first { name(it) == "b.txt" }
        assertEquals(0, act.deleteBlocking(bIndex))
        assertFalse(File(dir, "b.txt").exists())
        assertEquals(2, count())

        ins.runOnMainSync { act.finish() }
        dir.deleteRecursively()
    }
}
