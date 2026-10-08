package dev.ancdu

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Метки безопасности строк браузера: дерево в свежем каталоге (mkdtemp) под cacheDir повторяет
 * Download/ и Android/data/x/cache общего хранилища (корень дерева — «хранилище» для меток).
 * Ничего не удаляется приложением: лист удаления только открывается; фикстура убирается тестом.
 */
@RunWith(AndroidJUnit4::class)
class TagsTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private var act: BrowserActivity? = null
    private var dir: File? = null

    private fun fixture(): File =
        java.nio.file.Files.createTempDirectory(ctx.cacheDir.toPath(), "tags").toFile().also {
            assertTrue(it.isAbsolute && it.path.startsWith(ctx.cacheDir.path + "/"))
            dir = it
            File(it, "Download").mkdirs()
            File(it, "Download/a.pdf").writeBytes(ByteArray(9000))
            File(it, "Download2").mkdirs()
            File(it, "Download2/b.pdf").writeBytes(ByteArray(10))
            File(it, "Android/data/x/cache").mkdirs()
            File(it, "Android/data/x/cache/blob").writeBytes(ByteArray(20000))
            File(it, "Android/data/x/files").mkdirs()
            File(it, "DCIM/Camera").mkdirs()
            File(it, "DCIM/Camera/v.mp4").writeBytes(ByteArray(30000))
            File(it, "notes.txt").writeBytes(ByteArray(5))
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

    /** Строки текущего уровня: имя → (метка, описание виртуального узла TalkBack). */
    private fun rows(a: BrowserActivity): Map<String, Pair<String?, String>> {
        val out = LinkedHashMap<String, Pair<String?, String>>()
        ins.runOnMainSync {
            val s = a.list.source!!
            val p = a.list.accessibilityNodeProvider
            for (i in 0 until s.count) {
                val r = Row().also { s.bind(i, it) }
                out[r.name] = r.tag to p.createAccessibilityNodeInfo(i)!!.contentDescription.toString()
            }
        }
        return out
    }

    private fun open(a: BrowserActivity, name: String) = ins.runOnMainSync {
        val s = a.list.source!!
        val i = (0 until s.count).first { Row().also { r -> s.bind(it, r) }.name == name }
        s.click(i)
    }

    @After fun tearDown() {
        ins.runOnMainSync {
            act?.sheet?.dismiss()
            act?.finish()
            Holder.clear()
        }
        dir?.let { d -> if (d.path.startsWith(ctx.cacheDir.path + "/")) d.deleteRecursively() }
    }

    @Test fun tagsOnRowsAndInDescriptions() {
        val a = browse(fixture())
        val dl = ctx.getString(R.string.tag_dl)
        val media = ctx.getString(R.string.tag_media)
        val cache = ctx.getString(R.string.tag_cache)
        var top = rows(a)
        assertEquals("dl", top.getValue("Download/").first)
        assertTrue(top.getValue("Download/").second, top.getValue("Download/").second.endsWith(", $dl"))
        assertNull("Download2 — не загрузки", top.getValue("Download2/").first)
        assertEquals("media", top.getValue("DCIM/").first)
        assertTrue(top.getValue("DCIM/").second.endsWith(", $media"))
        assertNull(top.getValue("notes.txt").first)
        assertTrue(top.getValue("notes.txt").second, !top.getValue("notes.txt").second.endsWith(", $dl"))
        open(a, "Download/")
        // The folder's own tag: not on every row, once in the summary.
        assertNull(rows(a).getValue("a.pdf").first)
        ins.runOnMainSync { assertTrue(a.head.summary.text.toString(), a.head.summary.text.endsWith(" · dl")) }
        ins.runOnMainSync { a.onBackPressed() }
        open(a, "Android/"); open(a, "data/"); open(a, "x/")
        top = rows(a)
        assertEquals("cache", top.getValue("cache/").first)
        assertTrue(top.getValue("cache/").second, top.getValue("cache/").second.endsWith(", $cache"))
        assertNull("files/ — не кэш", top.getValue("files/").first)
        open(a, "cache/")
        assertNull(rows(a).getValue("blob").first)
        ins.runOnMainSync { assertTrue(a.head.summary.text.toString(), a.head.summary.text.endsWith(" · cache")) }
    }

    /** Delete sheet of Download/: the object's own «dl» once, not again on its children. */
    @Test fun deleteSheetSuppressesOwnTag() {
        val a = browse(fixture())
        ins.runOnMainSync {
            val s = a.list.source!!
            val i = (0 until s.count).first { Row().also { r -> s.bind(it, r) }.name == "Download/" }
            a.askDelete(i)
        }
        ins.waitForIdleSync()
        ins.runOnMainSync {
            val sh = a.sheet!!
            assertTrue(sh.dialog.isShowing)
            assertEquals(listOf("dl"), sh.tagTexts)
            assertEquals(listOf("a.pdf"), sh.childNames)
            sh.dismiss()
        }
    }

    /** Лист удаления каталога: метка у самого объекта и у его крупнейших детей. */
    @Test fun deleteSheetShowsTags() {
        val a = browse(fixture())
        open(a, "Android/"); open(a, "data/")
        ins.runOnMainSync {
            val s = a.list.source!!
            val i = (0 until s.count).first { Row().also { r -> s.bind(it, r) }.name == "x/" }
            a.askDelete(i)
        }
        ins.waitForIdleSync()
        ins.runOnMainSync {
            val sh = a.sheet!!
            assertTrue(sh.dialog.isShowing)
            // x/ — без метки (x не имя пакета), дети: cache/ — «cache», files/ (0 байт) — без метки
            assertEquals(listOf("cache"), sh.tagTexts)
            sh.dismiss()
        }
    }
}
