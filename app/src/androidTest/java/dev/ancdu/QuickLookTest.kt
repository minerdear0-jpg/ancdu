package dev.ancdu

import android.content.Intent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Карточка быстрого просмотра: тап по файлу открывает её (не лист удаления); текстовое превью,
 * без места под превью у незнакомого вида; «Удалить…» открывает обычный лист. Ничего не удаляется:
 * фикстуры — в свежем каталоге (mkdtemp) под cacheDir, «Удалить» листа не нажимается.
 */
@RunWith(AndroidJUnit4::class)
class QuickLookTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private var act: BrowserActivity? = null
    private var dir: File? = null

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

    /** Свежий каталог (mkdtemp) под cacheDir, абсолютный путь. */
    private fun fixture(): File =
        java.nio.file.Files.createTempDirectory(ctx.cacheDir.toPath(), "ql").toFile().also {
            assertTrue(it.isAbsolute && it.path.startsWith(ctx.cacheDir.path + "/"))
            dir = it
        }

    private fun browse(root: File): BrowserActivity {
        val h = Native.scanStart(root.path, true, 2, IntArray(1))
        val p = LongArray(6)
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            Native.progress(h, p)
            if (p[0] != ST_RUNNING.toLong() || System.currentTimeMillis() > deadline) break
            Thread.sleep(25)
        }
        assertEquals(ST_DONE.toLong(), p[0])
        ins.runOnMainSync { Holder.set(h, Kind.SCAN, root.path, false) }
        val a = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        act = a
        return a
    }

    private fun tap(a: BrowserActivity, name: String): QuickLook {
        ins.runOnMainSync {
            val s = a.list.source!!
            val row = Row()
            val i = (0 until s.count).first { row.reset(); s.bind(it, row); row.name == name }
            s.click(i)
        }
        assertTrue(waitFor { a.quickLook?.dialog?.isShowing == true })
        return a.quickLook!!
    }

    @After fun tearDown() {
        ins.runOnMainSync {
            act?.sheet?.dismiss()
            act?.quickLook?.dismiss()
            act?.finish()
            Holder.clear()
        }
        dir?.deleteRecursively()
    }

    @Test fun textFileShowsPreview() {
        val d = fixture()
        File(d, "notes.txt").writeText("first line\r\nsecond‮line\n" + "x".repeat(5000))
        val a = browse(d)
        val q = tap(a, "notes.txt")
        ins.runOnMainSync {
            assertTrue("тап по файлу не открывает лист", a.sheet?.dialog?.isShowing != true)
            assertEquals(0, a.node)
            assertEquals(PeekKind.TEXT, q.kind)
            assertNotNull("место под превью — сразу", q.box)
            assertEquals(a.getString(R.string.ql_text), q.typeText.text.toString())
            assertEquals("notes.txt", q.nameText.text.toString())
            // «ВЫБРАТЬ» видна (задача 23): вход в режим выбора с этим файлом.
            assertEquals(View.VISIBLE, q.selectButton.visibility)
            assertEquals(a.getString(R.string.ql_select), q.selectButton.text.toString())
            assertEquals(View.VISIBLE, q.deleteButton.visibility)
        }
        assertTrue(waitFor { q.previewText != null })
        ins.runOnMainSync {
            val text = q.previewText!!.text.toString()
            assertTrue(text, text.startsWith("first line\nsecondline\nxxx"))
            assertEquals(Peek.TEXT_LINES, q.previewText!!.maxLines)
            assertNull(q.noPreview)
        }
        // время изменения приходит с фонового потока, с годом
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString()
        assertTrue(waitFor { q.metaText.text.contains(year) })
        ins.runOnMainSync { q.dismiss() }
        assertTrue(waitFor { a.quickLook?.dialog?.isShowing != true })
    }

    @Test fun unknownTypeHasNoBox() {
        val d = fixture()
        File(d, "blob.bin").writeBytes(ByteArray(3000) { it.toByte() })
        val a = browse(d)
        val q = tap(a, "blob.bin")
        ins.runOnMainSync {
            assertEquals(PeekKind.NONE, q.kind)
            assertNull(q.box)
            assertEquals(a.tx.s(R.string.ql_file_ext, "bin"), q.typeText.text.toString())
            q.dismiss()
        }
    }

    /** «Удалить…» закрывает карточку и открывает обычный лист этого файла (ничего не удаляется). */
    @Test fun deleteFromCardOpensSheet() {
        val d = fixture()
        val f = File(d, "victim.log").apply { writeText("log\n") }
        val a = browse(d)
        val q = tap(a, "victim.log")
        ins.runOnMainSync { q.deleteButton.performClick() }
        assertTrue(waitFor { a.sheet?.dialog?.isShowing == true })
        ins.runOnMainSync {
            assertFalse(q.dialog.isShowing)
            val s = a.sheet!!
            assertEquals(f.path, s.p.path)
            assertFalse(s.p.dir)
            s.dismiss()
        }
        assertTrue(f.exists())
    }

    /**
     * FIFO (с расширением текста — место под превью резервируется по имени): карточка открывается,
     * поток превью не висит на read() — «превью недоступно» сразу, не по таймауту; следующая
     * карточка грузит превью как обычно. Символическая ссылка (флаг дерева) — без места под превью.
     */
    @Test fun fifoAndSymlinkNeverRead() {
        val d = fixture()
        val fifo = File(d, "pipe.txt")
        android.system.Os.mkfifo(fifo.path, "600".toInt(8))
        File(d, "real.txt").writeText("hello\n")
        android.system.Os.symlink(File(d, "real.txt").path, File(d, "link.txt").path)
        val a = browse(d)
        var q = tap(a, "pipe.txt")
        ins.runOnMainSync {
            assertEquals(PeekKind.TEXT, q.kind)
            assertNotNull(q.box)
        }
        assertTrue(waitFor(Peek.TIMEOUT_MS - 300) { q.noPreview != null })
        ins.runOnMainSync {
            assertFalse("ждали таймаута — read() висел", q.timedOut)
            assertNull(q.previewText)
            q.dismiss()
        }
        // Пул не занят: несколько FIFO-карточек подряд, затем обычный файл — превью есть.
        repeat(3) {
            q = tap(a, "pipe.txt")
            assertTrue(waitFor { q.noPreview != null })
            ins.runOnMainSync { q.dismiss() }
        }
        q = tap(a, "real.txt")
        assertTrue(waitFor { q.previewText != null })
        ins.runOnMainSync {
            assertEquals("hello\n", q.previewText!!.text.toString())
            q.dismiss()
        }
        q = tap(a, "link.txt")
        ins.runOnMainSync {
            assertEquals(PeekKind.NONE, q.kind)
            assertNull(q.box)
            q.dismiss()
        }
    }
}
