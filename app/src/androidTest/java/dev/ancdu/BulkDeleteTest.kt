package dev.ancdu

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Массовое удаление без root через MediaStore (Task 16) на настоящем MediaProvider.
 * Все пути — абсолютные, внутри собственного mkdtemp-каталога в общем хранилище; уборка в finally.
 */
@RunWith(AndroidJUnit4::class)
class BulkDeleteTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx = ins.targetContext

    private fun waitFor(ms: Long = 10_000, ok: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            if (ok()) return true
            Thread.sleep(100)
        }
        return false
    }

    /** Строки MediaStore ровно [path] и (каталог) строго внутри — с точной проверкой пути. */
    private fun rows(path: String, dir: Boolean): List<String> {
        val rr = ResolverRows(ctx.contentResolver)
        val sel = MediaBulk.selection(path, dir)
        val out = ArrayList<String>()
        var after = Long.MIN_VALUE
        while (true) {
            val q = MediaBulk.pageSelection(sel, after)
            val page = rr.page(q.where, q.args, 500)
            if (page.isEmpty()) break
            after = page.maxOf { it.id }
            for (r in page) if (MediaBulk.inside(r.data, path, dir)) out += r.data!!
            if (page.size < 500) break
        }
        return out
    }

    private fun scanned(dir: File): Long {
        val h = Native.scanStart(dir.path, true, 2, IntArray(1))
        val p = LongArray(6)
        val deadline = System.currentTimeMillis() + 30_000
        while (true) {
            Native.progress(h, p)
            if (p[0] != ST_RUNNING.toLong() || System.currentTimeMillis() > deadline) break
            Thread.sleep(25)
        }
        assertEquals(ST_DONE.toLong(), p[0])
        return h
    }

    @Test fun bulkDeleteRemovesSubtreeAndSparesPrefixSibling() {
        AppOps.restoreAfterExit(ins, "MANAGE_EXTERNAL_STORAGE", AppOps.get(ins, "MANAGE_EXTERNAL_STORAGE"))
        AppOps.set(ins, "MANAGE_EXTERNAL_STORAGE", "allow")
        assertTrue("нет MANAGE_EXTERNAL_STORAGE", Perms.files())

        // mkdtemp в общем хранилище: уникальное имя, только наш каталог.
        val wrap = Files.createTempDirectory(Paths.get("/storage/emulated/0"), "ancdu-test-w").toFile()
        assertTrue(wrap.isAbsolute && wrap.path.startsWith("/storage/emulated/0/ancdu-test-w"))
        var act: BrowserActivity? = null
        try {
            val tag = wrap.name.removePrefix("ancdu-test-w")
            val target = File(wrap, "ancdu-test-$tag").apply { assertTrue(mkdir()) }
            // Соседний каталог с тем же префиксом имени: LIKE '<target>%' без «/» задел бы его.
            val sibling = File(wrap, "ancdu-test-${tag}2").apply { assertTrue(mkdir()) }
            val sentinel = File(sibling, "sentinel.txt").apply { writeText("keep") }
            for (i in 0 until 300) File(target, "f$i.txt").writeText("x$i")
            val deep = File(target, "sub/deep").apply { assertTrue(mkdirs()) }
            for (i in 0 until 5) File(deep, "g$i.txt").writeText("g$i")
            val hidden = File(target, "hidden").apply { assertTrue(mkdir()) }
            File(hidden, ".nomedia").writeText("")
            for (i in 0 until 5) File(hidden, "h$i.txt").writeText("h$i")

            // Строки появляются асинхронно — ждём ограниченно.
            assertTrue("строки MediaStore не появились: ${rows(target.path, true).size}",
                waitFor(30_000) { rows(target.path, true).size >= 300 })
            val sentinelRow = waitFor(10_000) { rows(sentinel.path, false).isNotEmpty() }

            val h = scanned(wrap)
            ins.runOnMainSync { Holder.set(h, Kind.SCAN, wrap.path, "скан", false) }
            val a = ins.startActivitySync(
                Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
            act = a
            ins.waitForIdleSync()
            var idx = -1
            ins.runOnMainSync {
                val src = a.list.source!!
                for (i in 0 until src.count) {
                    val row = Row().also { src.bind(i, it) }
                    if (row.name == "${target.name}/") idx = i
                }
            }
            assertTrue("нет строки ${target.name}/", idx >= 0)

            assertEquals(0, a.deleteBlocking(idx))
            assertTrue("массовый шаг не сработал", Holder.lastBulkRows > 0)
            assertFalse(target.exists())
            assertTrue(sibling.isDirectory)
            assertEquals("keep", sentinel.readText())
            assertEquals(emptyList<String>(), rows(target.path, true))
            if (sentinelRow) assertEquals(listOf(sentinel.path), rows(sentinel.path, false))
            assertTrue(waitFor { var ok = false; ins.runOnMainSync { ok = !a.busy && a.list.source != null }; ok })
            ins.runOnMainSync {
                val src = a.list.source!!
                val names = (0 until src.count).map { Row().also { r -> src.bind(it, r) }.name }
                assertEquals(listOf("${sibling.name}/"), names)
            }
        } finally {
            act?.let { a -> ins.runOnMainSync { a.finish() } }
            // Уборка только своего mkdtemp-каталога (абсолютный путь проверен выше).
            if (wrap.path.startsWith("/storage/emulated/0/ancdu-test-w")) wrap.deleteRecursively()
        }
    }
}
