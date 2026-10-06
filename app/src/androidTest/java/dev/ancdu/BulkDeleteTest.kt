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

    private data class Case(val victim: File, val keepDir: File, val keepFile: File, val minRows: Long)

    /** [n] файлов «$prefix$i.txt» в [dir] от имени shell (абсолютные пути); ждёт завершения sh. */
    private fun shellCreate(dir: File, prefix: String, n: Int) {
        require(dir.isAbsolute && dir.path.startsWith("/storage/emulated/0/ancdu-test-w"))
        val fds = ins.uiAutomation.executeShellCommandRw("sh")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(fds[1]).use {
            it.write(("i=0; while [ \$i -lt $n ]; do echo x > \"${dir.path}/$prefix\$i.txt\"; i=\$((i+1)); done; " +
                "echo done\nexit\n").toByteArray())
        }
        val out = android.os.ParcelFileDescriptor.AutoCloseInputStream(fds[0]).use { String(it.readBytes()) }
        assertTrue("sh: $out", out.contains("done"))
        assertEquals(n, dir.listFiles { f -> f.name.startsWith(prefix) }?.size ?: 0)
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
            // Файлы другого процесса (shell) через FUSE: их строки is_pending = 1 и чужие —
            // именно их не видел массовый шаг до MediaMatch (регрессия с DUT).
            shellCreate(target, "f", 300)
            val deep = File(target, "sub/deep").apply { assertTrue(mkdirs()) }
            for (i in 0 until 5) File(deep, "g$i.txt").writeText("g$i")
            val hidden = File(target, "hidden").apply { assertTrue(mkdir()) }
            File(hidden, ".nomedia").writeText("")
            for (i in 0 until 5) File(hidden, "h$i.txt").writeText("h$i")
            // Пара для метасимвола: неэкранированный «_» в LIKE задел бы tX<tag>.
            val wild = File(wrap, "t_$tag").apply { assertTrue(mkdir()) }
            shellCreate(wild, "w", 20)
            val wildSib = File(wrap, "tX$tag").apply { assertTrue(mkdir()) }
            val wildSentinel = File(wildSib, "sentinel.txt").apply { writeText("keep") }

            // Строки появляются асинхронно — ждём ограниченно.
            assertTrue("строки MediaStore не появились: ${rows(target.path, true).size}",
                waitFor(30_000) { rows(target.path, true).size >= 300 })
            assertTrue("строки t_ не появились", waitFor(30_000) { rows(wild.path, true).size >= 20 })
            // Без строк соседей проверка «не задели соседа» была бы пустой.
            assertTrue("нет строки ${sentinel.path}", waitFor(15_000) { rows(sentinel.path, false).isNotEmpty() })
            assertTrue("нет строки ${wildSentinel.path}", waitFor(15_000) { rows(wildSentinel.path, false).isNotEmpty() })

            val h = scanned(wrap)
            ins.runOnMainSync { Holder.set(h, Kind.SCAN, wrap.path, false) }
            val a = ins.startActivitySync(
                Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
            act = a
            ins.waitForIdleSync()
            fun indexOf(name: String): Int {
                var idx = -1
                ins.runOnMainSync {
                    val src = a.list.source!!
                    for (i in 0 until src.count) if (Row().also { src.bind(i, it) }.name == name) idx = i
                }
                assertTrue("нет строки $name", idx >= 0)
                return idx
            }
            fun idle() = assertTrue(waitFor { var ok = false; ins.runOnMainSync { ok = !a.busy && a.list.source != null }; ok })

            for ((victim, keepDir, keepFile, minRows) in listOf(Case(target, sibling, sentinel, 300),
                    Case(wild, wildSib, wildSentinel, 20))) {
                assertEquals(0, a.deleteBlocking(indexOf("${victim.name}/")))
                // Массовый шаг действительно прошёл (MediaProvider принял LIKE … ESCAPE), а не тихий откат на rm_tree.
                // …и удалил пачками все строки файлов, а не одну строку каталога (иначе — пофайловый FUSE).
                assertTrue("массовый шаг для ${victim.name}: ${Holder.lastBulkRows} строк, нужно ≥ $minRows",
                    Holder.lastBulkRows >= minRows)
                assertFalse(victim.exists())
                assertTrue(keepDir.isDirectory)
                assertEquals("keep", keepFile.readText())
                assertEquals(emptyList<String>(), rows(victim.path, true))
                assertEquals(listOf(keepFile.path), rows(keepFile.path, false))
                idle()
            }
            ins.runOnMainSync {
                val src = a.list.source!!
                val names = (0 until src.count).map { Row().also { r -> src.bind(it, r) }.name }.toSet()
                assertEquals(setOf("${sibling.name}/", "${wildSib.name}/"), names)
            }
        } finally {
            act?.let { a -> ins.runOnMainSync { a.finish() } }
            // Уборка только своего mkdtemp-каталога (абсолютный путь проверен выше).
            if (wrap.path.startsWith("/storage/emulated/0/ancdu-test-w")) wrap.deleteRecursively()
        }
    }
}
