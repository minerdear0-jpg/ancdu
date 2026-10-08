package dev.ancdu

import android.content.Intent
import android.system.Os
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
 * Режим выбора и удаление группы. DESTRUCTIVE-TEST RULE: всё, что удаляется, — в свежем каталоге
 * (mkdtemp) под cacheDir, путь абсолютный и проверен; соседи проверяются на месте после удаления.
 */
@RunWith(AndroidJUnit4::class)
class SelectModeTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private var act: BrowserActivity? = null
    private val dirs = ArrayList<File>()

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
        java.nio.file.Files.createTempDirectory(ctx.cacheDir.toPath(), "sel").toFile().also {
            assertTrue(it.isAbsolute && it.path.startsWith(ctx.cacheDir.path + "/"))
            dirs += it
        }

    private fun file(dir: File, name: String, size: Int): File = File(dir, name).apply { writeBytes(ByteArray(size)) }.also {
        assertTrue(it.isAbsolute && it.path.startsWith(dir.path + "/"))
    }

    private fun browse(root: String): BrowserActivity {
        val h = Native.scanStart(root, true, 2, IntArray(1))
        val p = LongArray(6)
        val deadline = System.currentTimeMillis() + 30_000
        while (true) {
            Native.progress(h, p)
            if (p[0] != ST_RUNNING.toLong() || System.currentTimeMillis() > deadline) break
            Thread.sleep(25)
        }
        assertEquals(ST_DONE.toLong(), p[0])
        ins.runOnMainSync { Holder.set(h, Kind.SCAN, root, false) }
        val a = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        act = a
        return a
    }

    private fun index(a: BrowserActivity, name: String): Int {
        var k = -1
        ins.runOnMainSync {
            val src = a.list.source!!
            val row = Row()
            k = (0 until src.count).firstOrNull { row.reset(); src.bind(it, row); row.name == name } ?: -1
        }
        assertTrue("нет строки $name", k >= 0)
        return k
    }

    /** Кэш, который записал фоновый скан фикстуры: файл и запись «caches». */
    private fun forgetCache(dir: File) {
        Holder.io.submit {}.get()   // saveCache стоит на io
        Holder.cacheFile(ctx, dir.path, false).delete()
        Baseline.files(ctx, dir.path, false).forget()   // точка отсчёта тестового ключа
        ctx.getSharedPreferences(Scans.PREFS, android.content.Context.MODE_PRIVATE).edit()
            .remove(Holder.cacheFile(ctx, dir.path, false).name).commit()
    }

    @After fun tearDown() {
        Holder.beforeItem = null
        ins.runOnMainSync {
            act?.sheet?.dismiss()
            act?.quickLook?.dismiss()
            act?.finish()
            Holder.clear()
        }
        dirs.forEach { it.deleteRecursively() }
    }

    /** Долгое нажатие — режим выбора с этой строкой; шапка и верх списка не двигаются, панель — снизу. */
    @Test fun longPressEntersSelectionHeaderStable() {
        val d = fixture()
        for (k in 0 until 30) file(d, "f%02d.bin".format(k), 1000 + k)
        val a = browse(d.path)
        ins.waitForIdleSync()
        var h0 = 0; var top0 = 0; var lh0 = 0
        ins.runOnMainSync {
            h0 = a.header.height
            top0 = IntArray(2).also { a.list.getLocationOnScreen(it) }[1]
            lh0 = a.list.height
            assertEquals(View.GONE, a.selBar.visibility)
        }
        val i = index(a, "f00.bin")
        ins.runOnMainSync { a.list.source!!.longClick(i) }
        ins.waitForIdleSync()
        ins.runOnMainSync {
            assertTrue(a.selection.active)
            assertEquals(1, a.selection.count)
            assertEquals(View.VISIBLE, a.selBar.visibility)
            assertEquals("шапка не двигается", h0, a.header.height)
            assertEquals("верх списка не двигается", top0, IntArray(2).also { a.list.getLocationOnScreen(it) }[1])
            assertTrue("низ списка поднялся", a.list.height < lh0)
            val min = (44 * ctx.resources.displayMetrics.density).toInt()
            for (b in listOf(a.selExit, a.selAll, a.selDelete)) assertTrue("ниже 44dp", b.height >= min && b.width >= min)
            assertEquals(GroupSheet.selected(a.tx, 1), a.selCount.text.toString())
            assertEquals(a.getString(R.string.sel_all), a.selAll.text.toString())
            // строка — флажок
            val r = Row().also { a.list.source!!.bind(i, it) }
            assertEquals(true, r.checked)
            assertEquals(a.getString(R.string.sel_on), r.stateDesc)
            assertEquals(a.getString(R.string.sel_deselect), r.clickLabel)
            assertEquals(a.getString(R.string.click_label_quick), r.longLabel)
        }
        // длинная строка внизу не уходит под панель
        ins.runOnMainSync { a.leaveSelection(); a.list.scroll = 0 }
        ins.waitForIdleSync()
        var last = -1
        ins.runOnMainSync { last = minOf(a.list.source!!.count, a.list.height / a.list.rowHeight) - 1 }
        ins.runOnMainSync { a.list.source!!.longClick(last) }
        ins.waitForIdleSync()
        ins.runOnMainSync {
            val bottom = (last + 1) * a.list.rowHeight - a.list.scroll
            assertTrue("строка $last под панелью: $bottom > ${a.list.height}", bottom <= a.list.height)
        }
    }

    /** Тапы выбирают и снимают; «ВСЕ» → «НИЧЕГО»; «НИЧЕГО» и снятие последнего — выход. */
    @Test fun togglesAndAllNone() {
        val d = fixture()
        for (k in 0 until 4) file(d, "t$k.bin", 1000 * (k + 1))
        val a = browse(d.path)
        ins.runOnMainSync {
            val s = a.list.source!!
            s.longClick(0)
            s.click(1)
            assertEquals(2, a.selection.count)
            assertEquals(0, a.node)                    // тап в режиме выбора не открывает и не навигирует
            assertTrue(a.quickLook?.dialog?.isShowing != true)
            s.click(1)
            assertEquals(1, a.selection.count)
            a.selAll.performClick()
            assertEquals(4, a.selection.count)
            assertEquals(a.getString(R.string.sel_none), a.selAll.text.toString())
            a.selAll.performClick()
            assertFalse(a.selection.active)
            assertEquals(View.GONE, a.selBar.visibility)
            s.longClick(2)
            s.click(2)                                 // снят последний — выход
            assertFalse(a.selection.active)
        }
    }

    /** «Назад» в режиме выбора снимает выбор, не уходя из папки; переход по панели пути — тоже снимает. */
    @Test fun backExitsWithoutNavigating() {
        val d = fixture()
        File(d, "sub").mkdirs()
        file(d, "sub/x1.bin", 1000); file(d, "sub/x2.bin", 2000)
        val a = browse(d.path)
        val si = index(a, "sub/")
        ins.runOnMainSync { a.list.source!!.click(si) }
        assertTrue(waitFor { a.node != 0 })
        val sub = a.node
        val i = index(a, "x1.bin")
        ins.runOnMainSync {
            a.list.source!!.longClick(i)
            assertTrue(a.selection.active)
            a.onBackPressed()
            assertEquals("назад не навигирует", sub, a.node)
            assertFalse(a.selection.active)
            assertEquals(GroupSheet.cleared(a.tx, 1), a.footerText.toString())
            // переход к предку снимает выбор с подвалом
            a.list.source!!.longClick(i)
            a.jumpTo(0)
            assertEquals(0, a.node)
            assertFalse(a.selection.active)
            assertEquals(GroupSheet.cleared(a.tx, 1), a.footerText.toString())
        }
    }

    /** Запрещённую строку не выбрать: refuse и причина в подвале. */
    @Test fun blockedRowNotSelectable() {
        val a = browse("/system/etc")
        var plain = -1
        ins.runOnMainSync {
            val s = a.list.source!!
            val r = Row()
            plain = (0 until s.count).first { r.reset(); s.bind(it, r); r.mark != "↪" }
            s.longClick(plain)
            assertFalse(a.selection.active)
            assertEquals(a.getString(Block.SYSTEM.res), a.footerText.toString())
        }
    }

    /** Один выбранный — ровно прежний лист (тот же, что из карточки). */
    @Test fun oneSelectedIsTheSingleSheet() {
        val d = fixture()
        val big = File(d, "big").apply { mkdirs() }
        for ((k, n) in listOf(50, 40, 30, 20, 10).withIndex()) file(big, "f$k.bin", n * 1024)
        file(d, "small.bin", 10)
        val a = browse(d.path)
        val i = index(a, "big/")
        ins.runOnMainSync { a.askDelete(i) }
        assertTrue(waitFor { a.sheet?.dialog?.isShowing == true })
        val s1 = a.sheet!!
        var before = emptyList<Any?>()
        fun shape(s: DeleteSheet): List<Any?> = listOf(s.group, s.p.name, s.p.path, s.p.dir, s.p.disk, s.p.apparent,
            s.p.items, s.p.top, s.p.more, s.p.owner, s.p.block, s.p.fast, s.childNames.toList(),
            s.deleteButton?.text?.toString(), s.sizeText?.text?.toString(), s.cancelButton.text.toString(), s.tier)
        ins.runOnMainSync { before = shape(s1); s1.dismiss() }
        ins.runOnMainSync { a.list.source!!.longClick(i); a.deleteSelected() }
        assertTrue(waitFor { a.sheet?.dialog?.isShowing == true && a.sheet !== s1 })
        ins.runOnMainSync {
            val s2 = a.sheet!!
            assertNull(s2.group)
            assertEquals(before, shape(s2))
            s2.dismiss()
        }
        assertTrue(big.exists())
    }

    /** «ВЫБРАТЬ» карточки входит в выбор с этим файлом; в выборе долгое — карточка, её кнопка снимает. */
    @Test fun quickLookSelectEntersSelection() {
        val d = fixture()
        file(d, "f.txt", 100); file(d, "g.txt", 50)
        val a = browse(d.path)
        val i = index(a, "f.txt")
        ins.runOnMainSync { a.list.source!!.click(i) }
        assertTrue(waitFor { a.quickLook?.dialog?.isShowing == true })
        ins.runOnMainSync {
            val q = a.quickLook!!
            assertEquals(View.VISIBLE, q.selectButton.visibility)
            q.selectButton.performClick()
        }
        assertTrue(waitFor { a.quickLook?.dialog?.isShowing != true })
        ins.runOnMainSync {
            assertTrue(a.selection.active)
            assertEquals(true, Row().also { a.list.source!!.bind(i, it) }.checked)
            a.list.source!!.longClick(i)               // файл в режиме выбора — карточка, не переключение
            assertTrue(a.selection.active)
        }
        assertTrue(waitFor { a.quickLook?.dialog?.isShowing == true })
        ins.runOnMainSync {
            val q = a.quickLook!!
            assertEquals(a.getString(R.string.sel_deselect), q.selectButton.text.toString())
            q.selectButton.performClick()
            assertFalse("снят последний — выход", a.selection.active)
        }
    }

    /** Группа из 3 файлов: удалены ровно они, соседи на месте; один диалог, итог в подвале. */
    @Test fun groupDeleteRemovesExactlyThree() {
        val d = fixture()
        val victims = listOf(file(d, "a.bin", 3000), file(d, "b.bin", 2000), file(d, "c.bin", 1000))
        val keep = file(d, "keep.bin", 500)
        File(d, "keepdir").mkdirs()
        val keepIn = file(d, "keepdir/k.bin", 4000)
        val a = browse(d.path)
        val (ia, ib, ic) = listOf("a.bin", "b.bin", "c.bin").map { index(a, it) }
        ins.runOnMainSync {
            val s = a.list.source!!
            s.longClick(ia); s.click(ib); s.click(ic)
            assertEquals(3, a.selection.count)
            a.deleteSelected()
        }
        assertTrue(waitFor { a.sheet?.dialog?.isShowing == true })
        ins.runOnMainSync {
            val sh = a.sheet!!
            assertNotNull(sh.group)
            assertEquals(3, sh.group!!.count)
            assertEquals(GroupSheet.parentPath(d.path), sh.p.path)
            assertEquals(listOf("a.bin", "b.bin", "c.bin"), sh.childNames)
            assertEquals(DeleteTier.NONE, sh.tier)
            assertTrue(sh.deleteButton!!.isEnabled)
            sh.deleteButton!!.performClick()
        }
        assertTrue(waitFor(30_000) { !a.busy && a.list.source != null })
        victims.forEach { assertFalse(it.path, it.exists()) }
        assertTrue(keep.exists()); assertTrue(keepIn.exists())
        ins.runOnMainSync {
            assertFalse(a.selection.active)
            assertEquals(3, Holder.delCount)
            assertTrue(a.footerText.toString(), a.footerText.startsWith(a.prefixOf(R.string.freed)))
            assertEquals(2, a.list.source!!.count)
        }
    }

    /** Один из трёх не удаляется (файл в каталоге 0500): «Удалено 2 из 3», причина по имени. */
    @Test fun partialGroupShowsDeletedTwoOfThree() {
        val d = fixture()
        val f1 = file(d, "f1.bin", 3000)
        val f2 = file(d, "f2.bin", 2000)
        val locked = File(d, "locked").apply { mkdirs() }
        val inside = file(locked, "x.bin", 1000)
        Os.chmod(locked.path, "500".toInt(8))
        try {
            val a = browse(d.path)
            val (i1, i2, il) = listOf("f1.bin", "f2.bin", "locked/").map { index(a, it) }
            ins.runOnMainSync {
                val s = a.list.source!!
                s.longClick(i1); s.click(i2); s.click(il)
                a.deleteSelected()
            }
            assertTrue(waitFor { a.sheet?.dialog?.isShowing == true })
            ins.runOnMainSync { a.sheet!!.deleteButton!!.performClick() }
            assertTrue(waitFor(30_000) { !a.busy && a.lastAlert != null })
            ins.runOnMainSync {
                val (title, msg) = a.lastAlert!!
                assertEquals(a.getString(R.string.group_partial, "2", "3"), title)
                assertTrue(msg, msg.contains("locked/ — "))
                assertFalse(a.selection.active)
            }
            assertFalse(f1.exists()); assertFalse(f2.exists())
            assertTrue(inside.exists())
            // Каталог удалён не весь — дерево обновляется само (как после одного).
            assertTrue(waitFor(30_000) { !BgScan.active })
        } finally {
            Os.chmod(locked.path, "700".toInt(8))
            forgetCache(d)
        }
    }

    /** «Стоп» после первого объекта: остальные не начинаются, итог — в подвале, без сообщения. */
    @Test fun stopMidBatchSkipsTheRest() {
        val d = fixture()
        val s1 = file(d, "s1.bin", 3000)
        val s2 = file(d, "s2.bin", 2000)
        val s3 = file(d, "s3.bin", 1000)
        val a = browse(d.path)
        val (i1, i2, i3) = listOf("s1.bin", "s2.bin", "s3.bin").map { index(a, it) }
        Holder.beforeItem = { k -> if (k == 1) ins.runOnMainSync { a.stopDelete() } }
        ins.runOnMainSync {
            val s = a.list.source!!
            s.longClick(i1); s.click(i2); s.click(i3)
            a.deleteSelected()
        }
        assertTrue(waitFor { a.sheet?.dialog?.isShowing == true })
        ins.runOnMainSync { a.sheet!!.deleteButton!!.performClick() }
        assertTrue(waitFor(30_000) { !a.busy && a.list.source != null })
        assertFalse(s1.exists())
        assertTrue(s2.exists()); assertTrue(s3.exists())
        ins.runOnMainSync {
            assertNull(a.lastAlert)
            assertEquals(a.getString(R.string.group_stopped, "1", "3", DeleteProgress.freed(a.tx, Holder.delResults[0].disk)) +
                " · " + a.getString(R.string.log_link), a.footerText.toString())
        }
    }
}
