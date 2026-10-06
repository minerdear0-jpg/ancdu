package dev.ancdu

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Заголовок и крошки браузера, пустая папка, итог скана в плашке, экран приложений после resume. */
@RunWith(AndroidJUnit4::class)
class UxTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context get() = ins.targetContext

    /** Опрос с таймаутом: выходит при успехе, иначе после [ms]. [ok] — на главном потоке. */
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

    private fun browse(dir: File): BrowserActivity {
        val h = Native.scanStart(dir.path, true, 2, IntArray(1))
        val p = LongArray(6)
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            Native.progress(h, p)
            if (p[0] != ST_RUNNING.toLong() || System.currentTimeMillis() > deadline) break
            Thread.sleep(25)
        }
        assertEquals(ST_DONE.toLong(), p[0])
        ins.runOnMainSync { Holder.set(h, Kind.SCAN, dir.path, "скан", false) }
        val a = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        return a
    }

    private fun clickName(a: BrowserActivity, name: String) {
        ins.runOnMainSync {
            val s = a.list.source!!
            val row = Row()
            val i = (0 until s.count).first { row.reset(); s.bind(it, row); row.name == name }
            s.click(i)
        }
    }

    @Test fun emptyFolderMessages() {
        val dir = File(ctx.cacheDir, "ux1").apply { deleteRecursively(); mkdirs() }
        File(dir, "e").mkdirs()
        val locked = File(dir, "locked").apply { mkdirs() }
        File(dir, "x.bin").writeBytes(ByteArray(100))
        assertTrue(locked.setReadable(false, false) && locked.setExecutable(false, false))
        try {
            val a = browse(dir)
            ins.runOnMainSync { assertEquals(View.GONE, a.empty.visibility) }
            clickName(a, "e/")
            ins.runOnMainSync {
                assertEquals(View.VISIBLE, a.empty.visibility)
                assertEquals("пусто", a.empty.text.toString())
                a.onBackPressed()
                assertEquals(View.GONE, a.empty.visibility)
            }
            clickName(a, "locked/")
            ins.runOnMainSync {
                assertEquals(View.VISIBLE, a.empty.visibility)
                assertEquals("⚠ нет доступа", a.empty.text.toString())
                a.finish()
            }
        } finally {
            locked.setReadable(true, true); locked.setExecutable(true, true)
            dir.deleteRecursively()
            ins.runOnMainSync { Holder.clear() }
        }
    }

    /** Заголовок — имя папки; тап по крошке ведёт к предку и восстанавливает его прокрутку. */
    @Test fun titleAndBreadcrumbs() {
        val dir = File(ctx.cacheDir, "ux2").apply { deleteRecursively(); mkdirs() }
        for (k in 0 until 60) File(dir, "f$k.bin").writeBytes(ByteArray(10_000 + k * 100))
        File(dir, "a/b").mkdirs()
        File(dir, "a/b/c.bin").writeBytes(ByteArray(10))
        try {
            val a = browse(dir)
            ins.runOnMainSync { assertEquals(dir.path, a.title.text.toString()) }
            var saved = 0
            ins.runOnMainSync {
                a.list.scroll = 5 * a.list.rowHeight
                saved = a.list.scroll
            }
            assertTrue(saved > 0)
            clickName(a, "a/")
            clickName(a, "b/")
            ins.runOnMainSync {
                assertEquals("b", a.title.text.toString())
                assertEquals(3, a.crumbNodes.size)
                assertEquals(0, a.crumbNodes[0])
                assertEquals(a.node, a.crumbNodes[2])
                a.jumpTo(a.crumbNodes[0])
                assertEquals(0, a.node)
                assertEquals(dir.path, a.title.text.toString())
                assertEquals(saved, a.list.scroll)
                a.finish()
            }
        } finally {
            dir.deleteRecursively()
            ins.runOnMainSync { Holder.clear() }
        }
    }

    /** Быстрый скан: экран прогресса не строится; итог виден в плашке браузера. */
    @Test fun quickScanNoFlashAndBadge() {
        val dir = File(ctx.cacheDir, "ux3").apply { deleteRecursively(); mkdirs() }
        File(dir, "one.bin").writeBytes(ByteArray(100))
        val cache = Holder.cacheFile(ctx, dir.path, false)
        val prefs = ctx.getSharedPreferences("caches", Context.MODE_PRIVATE)
        val browserMon = ins.addMonitor(BrowserActivity::class.java.name, null, false)
        try {
            val scan = ins.startActivitySync(Intent(ctx, ScanActivity::class.java)
                .putExtra(EXTRA_ROOT, dir.path).putExtra(EXTRA_SU, false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ScanActivity
            val b = ins.waitForMonitorWithTimeout(browserMon, 10_000) as BrowserActivity?
            assertNotNull("браузер не открылся", b)
            ins.runOnMainSync { assertFalse(scan.built) }
            assertTrue(waitFor { b!!.badge.text.isNotEmpty() })
            ins.runOnMainSync {
                val t = b!!.badge.text.toString()
                assertTrue(t, Regex("скан · [0-9 ]+ эл\\. · \\d+,\\d с").matches(t))
                b.finish()
            }
        } finally {
            ins.removeMonitor(browserMon)
            Holder.io.submit {}.get()   // кэш пишется на io
            cache.delete()
            prefs.edit().remove(cache.name).commit()
            dir.deleteRecursively()
            ins.runOnMainSync { Holder.clear() }
        }
    }

    /** Возврат на экран приложений: те же view, прокрутка на месте, данные перечитаны. */
    @Test fun appsKeepViewsAcrossResume() {
        val prev = AppOps.get(ins, "GET_USAGE_STATS")
        try {
            AppOps.set(ins, "GET_USAGE_STATS", "allow")
            val a = ins.startActivitySync(Intent(ctx, AppsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AppsActivity
            assertTrue("список не загрузился", waitFor(20_000) { a.list.source!!.count > 15 })
            lateinit var list: NcduListView
            var saved = 0
            ins.runOnMainSync {
                list = a.list
                list.scroll = 3 * list.rowHeight
                saved = list.scroll
            }
            assertTrue(saved > 0)
            var before = 0
            ins.runOnMainSync {
                before = a.loaded
                ins.callActivityOnPause(a)
                ins.callActivityOnResume(a)
            }
            assertTrue("данные не перечитаны", waitFor(20_000) { a.loaded > before })
            ins.runOnMainSync {
                assertSame(list, a.list)
                assertTrue(a.list.isAttachedToWindow)
                assertEquals(saved, a.list.scroll)
                a.finish()
            }
        } finally {
            AppOps.set(ins, "GET_USAGE_STATS", prev)
        }
    }
}
