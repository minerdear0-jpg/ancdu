package dev.ancdu

import android.content.Intent
import android.view.KeyEvent
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
 * Превью в листе удаления: квадраты строк, «контактный лист» каталога, место 120dp у одного
 * файла, карточка поверх листа. Ничего не удаляется: фикстуры — в свежем каталоге (mkdtemp) под
 * cacheDir, «Удалить» листа не нажимается.
 */
@RunWith(AndroidJUnit4::class)
class SheetPreviewTest {
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
        java.nio.file.Files.createTempDirectory(ctx.cacheDir.toPath(), "sp").toFile().also {
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

    private fun indexOf(a: BrowserActivity, name: String): Int {
        var i = -1
        ins.runOnMainSync {
            val s = a.list.source!!
            val row = Row()
            i = (0 until s.count).firstOrNull { row.reset(); s.bind(it, row); row.name == name } ?: -1
        }
        assertTrue("нет строки $name", i >= 0)
        return i
    }

    private fun sheetOf(a: BrowserActivity, name: String): DeleteSheet {
        val i = indexOf(a, name)
        ins.runOnMainSync { a.askDelete(i) }
        assertTrue(waitFor { a.sheet?.dialog?.isShowing == true })
        return a.sheet!!
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

    /**
     * Каталог: у картинки — квадрат с превью, у FIFO «pipe.mp4» — знак «VID» сразу (не по таймауту,
     * поток не висит), у подкаталога — «контактный лист» с картинкой внутри.
     */
    @Test fun thumbnailsAndContactSheet() {
        val d = fixture()
        val album = File(d, "album").apply { mkdir() }
        TestMedia.jpeg(File(album, "big.jpg"), 800, 600)
        val sub = File(album, "sub").apply { mkdir() }
        TestMedia.jpeg(File(sub, "inner.jpg"))
        File(sub, "notes.bin").writeBytes(ByteArray(100))
        android.system.Os.mkfifo(File(album, "pipe.mp4").path, "600".toInt(8))
        val a = browse(d)
        val s = sheetOf(a, "album/")
        var big = -1; var pipe = -1; var subRow = -1
        ins.runOnMainSync {
            big = s.childNames.indexOf("big.jpg")
            pipe = s.childNames.indexOf("pipe.mp4")
            subRow = s.childNames.indexOf("sub/")
            assertTrue(s.childNames.toString(), big >= 0 && pipe >= 0 && subRow >= 0)
            // место — сразу, по расширению
            assertNotNull(s.childThumbs[big])
            assertNotNull(s.childThumbs[pipe])
            assertNull("у каталога квадрата нет", s.childThumbs[subRow])
            assertEquals(a.dp(SheetPeek.THUMB_DP), s.childThumbs[big]!!.layoutParams.width)
            assertNull(s.selfBox)
            val c = s.contactRows[subRow]
            assertNotNull("контактный лист каталога", c)
            assertEquals(1, c!!.childCount)
            assertNull(s.contactRows[big])
            // и у самого каталога листа — его крупнейшие картинки (FIFO пустой — мимо)
            assertEquals(2, s.selfContactRow!!.childCount)
        }
        val slot = { v: View? -> s.thumbs.slots.first { it.view === v } }
        assertTrue("FIFO: сразу «нет превью»", waitFor(Peek.TIMEOUT_MS - 300) { slot(s.childThumbs[pipe]).done })
        ins.runOnMainSync { assertTrue(slot(s.childThumbs[pipe]).failed) }
        assertTrue("превью картинки", waitFor { slot(s.childThumbs[big]).bmp != null })
        assertTrue("превью в контактном листе", waitFor { slot(s.contactRows[subRow]!!.getChildAt(0)).bmp != null })
        var b: android.graphics.Bitmap? = null
        ins.runOnMainSync {
            b = slot(s.childThumbs[big]).bmp!!
            assertTrue("уменьшено: ${b!!.width}×${b!!.height}", maxOf(b!!.width, b!!.height) <= 2 * a.dp(SheetPeek.THUMB_DP))
            s.dismiss()
        }
        // Битмапы освобождает обработчик закрытия диалога — он приходит отдельным сообщением.
        assertTrue("битмапы освобождены", waitFor { b!!.isRecycled })
    }

    /**
     * Тап по строке-файлу — карточка ПОВЕРХ листа, без «УДАЛИТЬ…»/«ВЫБРАТЬ»; «Назад» закрывает
     * только карточку — лист открыт, отсчёт не сброшен.
     */
    /** Каталог с детьми больше KIDS_CAP (как DCIM/Camera) — контактный лист не пустеет (JNI требует полный массив). */
    @Test fun contactSheetInHugeDir() {
        val d = fixture()
        val cam = File(d, "cam").apply { mkdir() }
        for (i in 0 until ContactSheet.KIDS_CAP + 44) File(cam, "f%03d.bin".format(i)).writeBytes(ByteArray(1))
        TestMedia.jpeg(File(cam, "shot.jpg"), 800, 600)
        val a = browse(d)
        val s = sheetOf(a, "cam/")
        ins.runOnMainSync { assertEquals("контактный лист большого каталога", 1, s.selfContactRow!!.childCount) }
        ins.runOnMainSync { s.dismiss() }
    }

    @Test fun rowTapOpensCardOverSheet() {
        val d = fixture()
        val album = File(d, "album").apply { mkdir() }
        TestMedia.jpeg(File(album, "a.jpg"))
        File(album, "b.txt").writeText("hello\n")
        val a = browse(d)
        val s = sheetOf(a, "album/")
        ins.runOnMainSync {
            val i = s.childNames.indexOf("a.jpg")
            assertTrue(s.childRows[i].isClickable)
            assertTrue(s.childRows[i].performClick())
        }
        assertTrue(waitFor { s.card?.dialog?.isShowing == true })
        var label = ""
        ins.runOnMainSync {
            val q = s.card!!
            assertTrue(q.fromSheet)
            assertEquals(File(album, "a.jpg").path, q.info.path)
            assertEquals(View.GONE, q.deleteButton.visibility)
            assertEquals(View.GONE, q.selectButton.visibility)
            assertNotNull(q.closeButton)
            assertTrue("лист под карточкой открыт", s.dialog.isShowing)
            assertNull("карточка браузера не открывалась", a.quickLook)
            label = s.deleteButton!!.text.toString()
        }
        // «Назад» уходит окну в фокусе: дождаться, пока его получит окно карточки.
        assertTrue(waitFor { s.card?.dialog?.window?.decorView?.hasWindowFocus() == true })
        ins.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        assertTrue(waitFor { s.card?.dialog?.isShowing != true })
        ins.runOnMainSync {
            assertTrue("«Назад» вернул к листу", s.dialog.isShowing)
            assertTrue(a.sheet === s)
            assertEquals(label, s.deleteButton!!.text.toString())
        }
        // строка текстового файла тоже открывает карточку
        ins.runOnMainSync { s.childRows[s.childNames.indexOf("b.txt")].performClick() }
        assertTrue(waitFor { s.card?.dialog?.isShowing == true && s.card?.info?.name == "b.txt" })
        ins.runOnMainSync { s.dismiss() }
        assertTrue(waitFor { s.card?.dialog?.isShowing != true })
        assertTrue(File(album, "a.jpg").exists())
    }

    /**
     * Двойной тап: ✕ карточки поверх листа закрыта — «Удалить» листа 500 мс не принимает касаний
     * (файл цел, лист открыт); после — удаляет как обычно. Удаляется только файл в свежем каталоге
     * (mkdtemp) под cacheDir, путь проверен.
     */
    @Test fun closingCardGuardsDelete() {
        val d = fixture()
        val f = File(d, "victim.jpg").also { TestMedia.jpeg(it) }
        assertTrue(f.isAbsolute && f.canonicalPath.startsWith(ctx.cacheDir.canonicalPath + "/"))
        val a = browse(d)
        val s = sheetOf(a, "victim.jpg")
        assertEquals(f.path, s.p.path)
        assertTrue("отсчёт", waitFor { s.deleteButton?.isEnabled == true })
        ins.runOnMainSync { assertTrue(s.selfBox!!.performClick()) }
        assertTrue(waitFor { s.card?.dialog?.isShowing == true })
        ins.runOnMainSync {
            val q = s.card!!
            // ✕ — вверху карточки, не над «Удалить» листа
            val at = IntArray(2).also { q.closeButton!!.getLocationOnScreen(it) }
            val del = IntArray(2).also { s.deleteButton!!.getLocationOnScreen(it) }
            assertTrue("✕ ${at[1]} над «Удалить» ${del[1]}", at[1] + q.closeButton!!.height <= del[1])
            assertTrue(q.closeButton!!.height >= a.dp(44))
            q.closeButton!!.performClick()
            assertFalse(q.dialog.isShowing)
            // второй тап того же двойного — в «Удалить» листа
            s.deleteButton!!.performClick()
            assertEquals(1, s.guardedTaps)
            assertTrue("лист открыт", s.dialog.isShowing)
        }
        assertTrue(f.exists())
        Thread.sleep(DeleteSheet.CARD_GUARD_MS + 100)
        ins.runOnMainSync {
            s.deleteButton!!.performClick()
            assertEquals(1, s.guardedTaps)
            assertFalse("удаление пошло", s.dialog.isShowing)
        }
        assertTrue(waitFor { !f.exists() && !Holder.deleting })
    }

    /** Один файл: место 120dp над предупреждением; из карточки («Удалить…») — без него. */
    @Test fun singleFileBoxOnlyNotFromCard() {
        val d = fixture()
        val f = File(d, "solo.jpg").also { TestMedia.jpeg(it) }
        val a = browse(d)
        val s = sheetOf(a, "solo.jpg")
        ins.runOnMainSync {
            assertNotNull(s.selfBox)
            assertEquals(a.dp(SheetPeek.BOX_DP), s.selfBox!!.layoutParams.height)
        }
        assertTrue(waitFor { s.thumbs.slots.first { it.view === s.selfBox }.bmp != null })
        ins.runOnMainSync { s.dismiss() }
        val i = indexOf(a, "solo.jpg")
        ins.runOnMainSync { a.list.source!!.click(i) }
        assertTrue(waitFor { a.quickLook?.dialog?.isShowing == true })
        ins.runOnMainSync { a.quickLook!!.deleteButton.performClick() }
        assertTrue(waitFor { a.sheet?.dialog?.isShowing == true && a.sheet !== s })
        ins.runOnMainSync {
            assertTrue(a.sheet!!.fromCard)
            assertNull("из карточки — без места превью", a.sheet!!.selfBox)
            a.sheet!!.dismiss()
        }
        assertTrue(f.exists())
    }
}
