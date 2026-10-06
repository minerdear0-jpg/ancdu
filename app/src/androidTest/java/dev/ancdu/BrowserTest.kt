package dev.ancdu

import android.content.Intent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
            assertTrue(act2.footerText.toString(), act2.footerText.startsWith("освобождено "))
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
     * Диалог удаления: заголовок с именем, счётчик «N / M эл.», «Стоп» → «Останавливаю…».
     * Удаление стоит на io за заслонкой, стоп нажат до его начала: ядро не вызывается, итог -EINTR,
     * всё на месте, сообщение «Удаление отменено» (ничего не удалено — дерево не обновляется).
     */
    @Test fun deleteDialogShowsProgressAndStops() {
        val ctx = ins.targetContext
        val dir = File(ctx.cacheDir, "br5").apply { deleteRecursively(); mkdirs() }
        val sub = File(dir, "sub").apply { mkdirs() }
        for (i in 0 until 3) File(sub, "f$i.bin").writeBytes(ByteArray(50_000))
        File(dir, "keep.bin").writeBytes(ByteArray(10))
        scan(dir)
        val act = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()

        val gate = CountDownLatch(1)
        Holder.io.execute { gate.await(30, TimeUnit.SECONDS) }
        var r = Int.MIN_VALUE
        val deleter = Thread { r = act.deleteBlocking(0) }.apply { start() } // по размеру: 0 — sub/
        assertTrue(waitFor { act.waitBar != null })
        ins.runOnMainSync {
            assertEquals("sub", Holder.delName)
            assertEquals(4L, Holder.delTotal) // sub + 3 файла
            assertTrue(act.waitText!!.text.startsWith("0 / 4 эл."))
            assertEquals("Стоп", act.waitStop!!.text.toString())
            assertEquals(1000, act.waitBar!!.max)
            assertNotNull(act.waitBar!!.contentDescription)
            act.stopDelete()
            assertEquals("Останавливаю…", act.waitStop!!.text.toString())
            assertFalse(act.waitStop!!.isEnabled)
        }

        gate.countDown()
        deleter.join(30_000)
        assertFalse(deleter.isAlive)
        assertEquals(-DeleteProgress.EINTR, r)
        assertTrue(waitFor { !act.busy && act.list.source != null })
        ins.runOnMainSync {
            assertNull(act.waitBar)
            // ещё не начиналось — «отменено»; обновлять нечего
            assertEquals("Удаление отменено", act.lastAlert?.first)
            assertEquals("Удаление отменено — ничего не удалено.", act.lastAlert?.second)
        }
        for (i in 0 until 3) assertTrue(File(sub, "f$i.bin").exists())

        // Отменённый в очереди стоп не переходит на следующее удаление: то же удаляется целиком.
        assertEquals(0, act.deleteBlocking(0))
        assertFalse(sub.exists())

        ins.runOnMainSync { act.finish() }
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

    /** Кэш: каталог мимо листа не удаляется (сначала обновление дерева), файл — можно. */
    @Test fun cacheRefusesDirDelete() {
        val ctx = ins.targetContext
        val dir = File(ctx.cacheDir, "br4").apply { deleteRecursively(); mkdirs() }
        File(dir, "sub").mkdirs()
        File(dir, "sub/big.bin").writeBytes(ByteArray(300_000))
        File(dir, "f.txt").writeBytes(ByteArray(10))
        val h = scanned(dir)
        ins.runOnMainSync { Holder.set(h, Kind.CACHE, dir.path, "кэш от 01.01 00:00", false) }

        val act = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        val row = Row()
        fun name(i: Int): String {
            var s = ""
            ins.runOnMainSync { row.reset(); act.list.source!!.bind(i, row); s = row.name }
            return s
        }
        assertEquals("sub/", name(0))
        assertEquals(Int.MIN_VALUE, act.deleteBlocking(0))   // отказ: удаление не запускалось
        assertTrue(File(dir, "sub/big.bin").exists())
        assertEquals("f.txt", name(1))
        assertEquals(0, act.deleteBlocking(1))
        assertFalse(File(dir, "f.txt").exists())

        ins.runOnMainSync { act.finish() }
        dir.deleteRecursively()
    }

    /** Свежий каталог теста (mkdtemp) под cacheDir приложения — абсолютный путь. */
    private fun tmpDir(prefix: String): File =
        java.nio.file.Files.createTempDirectory(ins.targetContext.cacheDir.toPath(), prefix).toFile()

    /**
     * Ждущее дерево: offer не трогает экран, чип «новее · обновить» виден; тап подставляет его и
     * открывает тот же путь по именам; пропавший путь — ближайший существующий предок.
     */
    @Test fun pendingChipPromotesAndKeepsPath() {
        val ctx = ins.targetContext
        val dir = tmpDir("pend")
        File(dir, "sub/deep").mkdirs()
        File(dir, "sub/deep/a.bin").writeBytes(ByteArray(20_000))
        File(dir, "z.bin").writeBytes(ByteArray(10))
        scan(dir)
        val act = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        val row = Row()
        fun name(i: Int): String {
            var s = ""
            ins.runOnMainSync { row.reset(); act.list.source!!.bind(i, row); s = row.name }
            return s
        }
        fun count(): Int { var c = 0; ins.runOnMainSync { c = act.list.source!!.count }; return c }
        fun path(): String { var s = ""; ins.runOnMainSync { s = Native.str(Native.path(Holder.h, act.node)) }; return s }
        try {
            assertEquals("sub/", name(0))
            ins.runOnMainSync { act.list.source!!.click(0) }
            assertEquals("deep/", name(0))
            ins.runOnMainSync { act.list.source!!.click(0) }
            assertEquals(File(dir, "sub/deep").path, path())
            ins.runOnMainSync { assertEquals(View.GONE, act.newer.visibility) }

            File(dir, "sub/deep/b.bin").writeBytes(ByteArray(10))
            val h2 = scanned(dir)
            var h1 = 0L
            ins.runOnMainSync {
                h1 = Holder.h
                Holder.offer(h2, Kind.SCAN, dir.path, "скан · новее", false)
                act.refreshPending()
            }
            ins.runOnMainSync {
                assertEquals(h1, Holder.h)               // offer не подставил дерево
                assertEquals(View.VISIBLE, act.newer.visibility)
                act.newer.performClick()
            }
            ins.runOnMainSync {
                assertEquals(h2, Holder.h)
                assertEquals(0L, Holder.pending)
                assertEquals(View.GONE, act.newer.visibility)
                assertEquals("скан · новее", act.badge.text.toString())
            }
            assertEquals(File(dir, "sub/deep").path, path())
            assertEquals(2, count())

            // путь пропал — ближайший предок
            File(dir, "sub/deep").deleteRecursively()
            val h3 = scanned(dir)
            ins.runOnMainSync { Holder.offer(h3, Kind.SCAN, dir.path, "скан", false); act.refreshPending() }
            ins.runOnMainSync { act.newer.performClick() }
            ins.runOnMainSync { assertEquals(h3, Holder.h) }
            assertEquals(File(dir, "sub").path, path())
            assertEquals(0, count())
        } finally {
            ins.runOnMainSync { Holder.dropPending(); act.finish() }
            dir.deleteRecursively()
        }
    }

    /**
     * Кэш + ждущее более новое дерево: долгий тап сразу подставляет новое (без скана) и открывает
     * лист того же файла уже в нём; файла в новом дереве нет — листа нет, подвал «“X” уже нет на диске».
     */
    @Test fun newerLongPressPromotesThenSheet() {
        val ctx = ins.targetContext
        val dir = tmpDir("pendc")
        File(dir, "f.txt").writeBytes(ByteArray(10))
        File(dir, "g.txt").writeBytes(ByteArray(20))
        val h = scanned(dir)
        val h2 = scanned(dir)
        ins.runOnMainSync {
            Holder.set(h, Kind.CACHE, dir.path, "кэш от 01.01 00:00", false)
            Holder.offer(h2, Kind.SCAN, dir.path, "скан", false)
        }
        val act = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        val row = Row()
        fun index(name: String): Int {
            var k = -1
            ins.runOnMainSync {
                val src = act.list.source!!
                k = (0 until src.count).first { row.reset(); src.bind(it, row); row.name == name }
            }
            return k
        }
        try {
            ins.runOnMainSync { assertEquals(View.VISIBLE, act.newer.visibility) }
            val fi = index("f.txt")
            ins.runOnMainSync { act.list.source!!.longClick(fi) }
            assertTrue(waitFor { act.sheet?.dialog?.isShowing == true })
            ins.runOnMainSync {
                assertEquals(h2, Holder.h)                 // подставлено мгновенно
                assertEquals(Kind.SCAN, Holder.kind)
                assertEquals(View.GONE, act.newer.visibility)
                val s = act.sheet!!
                assertEquals(File(dir, "f.txt").path, s.p.path)
                assertNotNull(s.deleteButton)
                s.dismiss()
            }

            // Файл пропал с диска: новое дерево его не знает — листа нет, подвал говорит об этом.
            File(dir, "g.txt").delete()
            val h3 = scanned(dir)
            ins.runOnMainSync { Holder.offer(h3, Kind.SCAN, dir.path, "скан", false); act.refreshPending() }
            ins.runOnMainSync { assertEquals(h2, Holder.h) }   // лист закрыт, но флага нет — ждёт чип
            val gi = index("g.txt")
            var before: DeleteSheet? = null
            ins.runOnMainSync { before = act.sheet; act.list.source!!.longClick(gi) }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                assertEquals(h3, Holder.h)
                assertTrue(act.sheet === before && act.sheet?.dialog?.isShowing != true)
                assertEquals("“g.txt” уже нет на диске", act.footerText.toString())
            }
            assertTrue(File(dir, "f.txt").exists())
        } finally {
            ins.runOnMainSync { Holder.dropPending(); act.finish() }
            dir.deleteRecursively()
        }
    }
}
