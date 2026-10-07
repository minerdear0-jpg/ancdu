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

/** Лист удаления: превью из дерева, запреты, пауза серьёзного удаления. */
@RunWith(AndroidJUnit4::class)
class DeleteSheetTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private var act: BrowserActivity? = null
    private val dirs = ArrayList<File>()

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

    private fun fixture(name: String): File =
        File(ctx.cacheDir, name).apply { deleteRecursively(); mkdirs() }.also { dirs += it }

    private fun scanned(root: String): Long {
        val h = Native.scanStart(root, true, 2, IntArray(1))
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

    private fun browse(root: String, kind: Kind = Kind.SCAN, viaRoot: Boolean = false): BrowserActivity {
        val h = scanned(root)
        ins.runOnMainSync { Holder.set(h, kind, root, viaRoot) }
        val a = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        act = a
        return a
    }

    private fun rows(a: BrowserActivity): List<Row> {
        val out = ArrayList<Row>()
        ins.runOnMainSync {
            val s = a.list.source!!
            for (i in 0 until s.count) out += Row().also { s.bind(i, it) }
        }
        return out
    }

    private fun indexOf(a: BrowserActivity, name: String): Int = rows(a).indexOfFirst { it.name == name }
        .also { assertTrue("нет строки $name", it >= 0) }

    private fun longPress(a: BrowserActivity, i: Int): DeleteSheet {
        ins.runOnMainSync { a.list.source!!.longClick(i) }
        assertTrue(waitFor { a.sheet?.dialog?.isShowing == true })
        return a.sheet!!
    }

    @After fun tearDown() {
        ins.runOnMainSync {
            act?.sheet?.dismiss()
            act?.finish()
            Holder.clear()
        }
        dirs.forEach { it.deleteRecursively() }
    }

    @Test fun dirPreviewShowsTopChildrenAndDeletes() {
        val dir = fixture("ds1")
        val big = File(dir, "big").apply { mkdirs() }
        for ((k, n) in listOf(50, 40, 30, 20, 10).withIndex()) File(big, "f$k.bin").writeBytes(ByteArray(n * 1024))
        File(dir, "small.bin").writeBytes(ByteArray(10))
        val a = browse(dir.path)
        val i = indexOf(a, "big/")

        var s = longPress(a, i)
        ins.runOnMainSync {
            assertEquals(listOf("f0.bin", "f1.bin", "f2.bin"), s.childNames)
            assertEquals(2, s.p.more)
            assertEquals(6L, s.p.items)
            assertNull(s.blockText)
            val b = s.deleteButton!!
            assertTrue(b.isEnabled)
            assertEquals(a.getString(R.string.delete_btn_size, Fmt.size(s.p.disk, a.tx)), b.text.toString())
            assertNotNull(b.contentDescription)
            s.cancelButton.performClick()
        }
        assertTrue(waitFor { !s.dialog.isShowing })
        assertTrue(big.exists())

        // Подтверждение листа удаляет ровно показанный узел.
        s = longPress(a, i)
        ins.runOnMainSync { s.deleteButton!!.performClick() }
        assertTrue(waitFor(30_000) { !a.busy && a.list.source != null && a.list.source!!.count == 1 })
        assertFalse(big.exists())
        assertTrue(File(dir, "small.bin").exists())
    }

    @Test fun fileTapOpensSheet() {
        val dir = fixture("ds2")
        File(dir, "sub").mkdirs()
        File(dir, "one.bin").writeBytes(ByteArray(5000))
        val a = browse(dir.path)
        val i = indexOf(a, "one.bin")
        ins.runOnMainSync { a.list.source!!.click(i) }
        assertTrue(waitFor { a.sheet?.dialog?.isShowing == true })
        ins.runOnMainSync {
            val s = a.sheet!!
            assertEquals(0, a.node)              // тап по файлу не навигирует
            assertTrue(s.childNames.isEmpty())
            assertNotNull(s.deleteButton)
            assertFalse(s.p.dir)
            s.dismiss()
        }
        // тап по каталогу по-прежнему открывает его
        val sub = indexOf(a, "sub/")
        ins.runOnMainSync { a.list.source!!.click(sub) }
        assertTrue(waitFor { a.node != 0 })
    }

    /**
     * ↪: в пространстве имён приложения Android/data и Android/obb — bind-монтирования с другой ФС
     * (нужен доступ ко всем файлам; прежний режим appops вернётся после выхода процесса теста).
     */
    @Test fun otherFsRowHasNoDelete() {
        AppOps.restoreAfterExit(ins, "MANAGE_EXTERNAL_STORAGE", AppOps.get(ins, "MANAGE_EXTERNAL_STORAGE"))
        AppOps.set(ins, "MANAGE_EXTERNAL_STORAGE", "allow")
        assertTrue("нет MANAGE_EXTERNAL_STORAGE", Perms.files())
        val a = browse("/storage/emulated/0/Android")
        val all = rows(a)
        val other = all.indexOfFirst { it.mark == "↪" }
        assertTrue("нет ↪-строк: " + all.map { it.mark + it.name }, other >= 0)
        val s = longPress(a, other)
        ins.runOnMainSync {
            assertNull(s.deleteButton)
            assertEquals(a.getString(Block.OTHER_FS.res), s.blockText!!.text.toString())
            assertEquals(a.getString(R.string.close), s.cancelButton.text.toString())
            s.dismiss()
        }
    }

    /** Системный путь (/system/etc и всё внутри) — без кнопки «Удалить». */
    @Test fun systemPathRowHasNoDelete() {
        val a = browse("/system/etc")
        val all = rows(a)
        val plain = all.indexOfFirst { it.mark != "↪" }
        assertTrue("нет обычных строк: " + all.map { it.mark + it.name }, plain >= 0)
        val s = longPress(a, plain)
        ins.runOnMainSync {
            assertNull(s.deleteButton)
            assertEquals(a.getString(Block.SYSTEM.res), s.blockText!!.text.toString())
            s.dismiss()
        }
    }

    /**
     * Индекс: долгий тап по каталогу — в шапке «обновление · …», экран сам сканирует корень и открывает
     * лист того же каталога уже в свежем дереве (с кнопкой «Удалить»); файл — лист сразу.
     */
    @Test fun indexDirRefreshesThenSheet() {
        val dir = fixture("ds3")
        File(dir, "photos").mkdirs()
        File(dir, "photos/a.jpg").writeBytes(ByteArray(3000))
        File(dir, "b.jpg").writeBytes(ByteArray(10))
        Perms.filesOverride = true
        try {
            val a = browse(dir.path, kind = Kind.INDEX)
            var s = longPress(a, indexOf(a, "b.jpg"))
            ins.runOnMainSync {
                assertEquals(Kind.INDEX, Holder.kind)        // файл: без обновления
                assertNotNull(s.deleteButton)
                assertNull(s.blockText)
                s.dismiss()
            }
            val i = indexOf(a, "photos/")
            ins.runOnMainSync {
                a.list.source!!.longClick(i)
                // Ход обновления — в шапке: полоса и «обновление · …» (идёт или ждёт).
                assertEquals(View.VISIBLE, a.scanLine.visibility)
                assertTrue(a.badge.text.toString(), a.badge.text.startsWith(a.prefixOf(R.string.refresh_count)))
            }
            assertTrue(waitFor(30_000) { Holder.kind == Kind.SCAN && a.sheet?.dialog?.isShowing == true })
            s = a.sheet!!
            ins.runOnMainSync {
                assertEquals(File(dir, "photos").path, s.p.path)
                assertNotNull(s.deleteButton)
                assertNull(s.blockText)
                s.dismiss()
            }
            assertTrue(waitFor { !BgScan.active })
        } finally {
            Perms.filesOverride = null
            forgetCache(dir)
        }
    }

    /** Кэш, который записал фоновый скан фикстуры: файл и запись «caches». */
    private fun forgetCache(dir: File) {
        Holder.io.submit {}.get()   // saveCache стоит на io
        Holder.cacheFile(ctx, dir.path, false).delete()
        ctx.getSharedPreferences(Scans.PREFS, android.content.Context.MODE_PRIVATE).edit()
            .remove(Holder.cacheFile(ctx, dir.path, false).name).commit()
    }

    /**
     * Данные приложения как root: кнопка выключена 2,5 с с отсчётом 3-2-1, затем включается;
     * предупреждение — одной строкой «⚠ Удаление от root · без корзины. Отменить нельзя.».
     */
    @Test fun seriousDeleteCountsDown() {
        val dir = fixture("ds4")
        File(dir, "x").mkdirs()
        File(dir, "x/data.bin").writeBytes(ByteArray(4000))
        val a = browse(dir.path, viaRoot = true)   // путь /data/user/0/dev.ancdu/… — владелец есть
        val s = longPress(a, indexOf(a, "x/"))
        val t0 = System.currentTimeMillis()
        ins.runOnMainSync {
            assertEquals(ctx.packageName, s.p.owner)
            assertTrue(s.ownerText!!.text.toString().endsWith(a.suffixOf(R.string.owner)))
            val b = s.deleteButton!!
            assertFalse(b.isEnabled)
            assertTrue(b.text.toString(), b.text.startsWith(a.prefixOf(R.string.delete_in)))
            b.performClick()                      // нажатие до конца паузы ничего не делает
            // Одна строка-предупреждение (root вместе с «без корзины»), без повтора обычной.
            val root = s.rootText!!
            assertEquals("⚠ " + a.getString(R.string.root_no_trash), root.text.toString())
            assertEquals(View.VISIBLE, root.visibility)
            val box = root.parent as android.view.ViewGroup
            val texts = (0 until box.childCount).mapNotNull { (box.getChildAt(it) as? android.widget.TextView)?.text?.toString() }
            assertFalse(texts.toString(), texts.contains("⚠ " + a.getString(R.string.no_trash)))
        }
        assertFalse(a.busy)
        assertTrue(waitFor(6_000) { s.deleteButton!!.isEnabled })
        assertTrue(System.currentTimeMillis() - t0 >= DeletePolicy.ROOT_PAUSE_MS - 100)
        ins.runOnMainSync {
            assertEquals(listOf(3L, 2L, 1L), s.countdownShown)
            assertEquals(a.getString(R.string.delete_btn_size, Fmt.size(s.p.disk, a.tx)), s.deleteButton!!.text.toString())
            s.dismiss()
        }
        assertTrue(File(dir, "x/data.bin").exists())
    }

    /** Данные другого приложения без root: пауза 1,5 с, отсчёт 2-1, строки root нет. Синтетическое превью, «Удалить» не нажимается. */
    @Test fun otherAppDeleteCountsTwo() {
        val dir = fixture("ds-other")
        File(dir, "a.bin").writeBytes(ByteArray(10))
        val a = browse(dir.path)
        val pv = DeletePreview(
            name = "com.example.other", path = "/storage/emulated/0/Android/data/com.example.other", dir = true,
            disk = 1000, apparent = 1000, items = 3, flags = F_DIR, top = emptyList(), more = 0,
            owner = "com.example.other", viaRoot = false, block = null, kind = Kind.SCAN, cacheTime = null)
        lateinit var s: DeleteSheet
        var chosen: Boolean? = null
        ins.runOnMainSync {
            s = DeleteSheet(a, pv) { chosen = it }.also { it.show() }
            assertFalse(s.deleteButton!!.isEnabled)
            assertNull(s.rootText)
        }
        assertTrue(waitFor(4_000) { s.deleteButton!!.isEnabled })
        ins.runOnMainSync {
            assertEquals(listOf(2L, 1L), s.countdownShown)
            s.dismiss()
        }
        assertNull(chosen)
    }

    /** Размер и видимый размер не обрезаются: не влезли в строку — видимый переносится. */
    @Test fun sizeLineWrapsNotTruncates() {
        val dir = fixture("ds-size")
        File(dir, "a.bin").writeBytes(ByteArray(10))
        val a = browse(dir.path)
        val pv = DeletePreview(
            name = "DCIM", path = "/storage/emulated/0/DCIM", dir = true, disk = (81.6 * (1L shl 30)).toLong(),
            apparent = (81.4 * (1L shl 30)).toLong(), items = 63_767, flags = F_DIR, top = emptyList(), more = 0,
            owner = null, viaRoot = false, block = null, kind = Kind.SCAN, cacheTime = null)
        lateinit var s: DeleteSheet
        ins.runOnMainSync { s = DeleteSheet(a, pv) {}.also { it.show() } }
        ins.waitForIdleSync()
        ins.runOnMainSync {
            val v = s.sizeText!!
            val text = v.text.toString()
            assertTrue(text, text.contains(Fmt.size(pv.disk, a.tx).replace(' ', Fmt.NBSP)))
            assertTrue(text, text.contains(Fmt.size(pv.apparent, a.tx).replace(' ', Fmt.NBSP)))
            for (i in 0 until v.layout.lineCount) assertEquals(text, 0, v.layout.getEllipsisCount(i))
            assertTrue(v.layout.lineCount <= 2)
            s.dismiss()
        }
    }

    /** Галочка «быстро через root»: только при доступном быстром пути, по умолчанию от 1000 эл.
     *  и только при выданном root (неизвестно/отказ — выключена).
     *  Лист строится из синтетического превью; «Удалить» не нажимается — ничего не удаляется. */
    @Test fun fastRootCheckbox() {
        val dir = fixture("ds-fast")
        File(dir, "a.bin").writeBytes(ByteArray(10))
        val a = browse(dir.path)
        fun pv(fast: Boolean, items: Long, root: RootState = RootState.GRANTED) = DeletePreview(
            name = "DCIM", path = "/storage/emulated/0/DCIM", dir = true, disk = 1000, apparent = 1000,
            items = items, flags = F_DIR, top = emptyList(), more = 0, owner = null, viaRoot = false,
            block = null, kind = Kind.SCAN, cacheTime = null, fast = fast, root = root)
        ins.runOnMainSync {
            var chosen: Boolean? = null
            val big = DeleteSheet(a, pv(true, 1500)) { chosen = it }.also { it.show() }
            assertNotNull(big.fastBox)
            assertTrue(big.fastBox!!.isChecked)
            assertEquals(a.getString(R.string.fast_box), big.fastBox!!.text.toString())
            big.dismiss()
            for (st in listOf(RootState.UNKNOWN, RootState.DENIED)) {
                val unsure = DeleteSheet(a, pv(true, 1500, st)) { chosen = it }.also { it.show() }
                assertNotNull(unsure.fastBox)
                assertFalse("$st", unsure.fastBox!!.isChecked)
                unsure.dismiss()
            }
            val small = DeleteSheet(a, pv(true, 10)) { chosen = it }.also { it.show() }
            assertFalse(small.fastBox!!.isChecked)
            small.dismiss()
            val none = DeleteSheet(a, pv(false, 5000)) { chosen = it }.also { it.show() }
            assertNull(none.fastBox)
            none.dismiss()
            assertNull(chosen)
        }
    }
}
