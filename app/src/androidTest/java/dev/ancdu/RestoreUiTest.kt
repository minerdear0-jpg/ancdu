package dev.ancdu

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Task 31: браузер переживает смерть процесса (папка, прокрутка, выбор — по именам), курсор «вы были
 * здесь».
 *
 * Смерть процесса в одном процессе: [BrowserState.testProcessDeath] в начале onCreate нового экземпляра
 * (после onSaveInstanceState и onDestroy прежнего) очищает Holder (дерево освобождается — как после
 * смерти: в памяти нет ни дескриптора, ни id узлов) и выдаёт новую метку процесса — сохранённое
 * состояние становится состоянием «умершего». Остаются, как и при настоящей смерти, только Bundle и
 * файлы (кэш дерева, запись «caches»).
 *
 * DESTRUCTIVE-TEST RULE: всё, что удаляется или переименовывается, — в свежем каталоге (mkdtemp) под
 * cacheDir, пути абсолютные и проверены; журнал удалений — [LogSandboxRule]; точки отсчёта — в песочнице.
 */
@RunWith(AndroidJUnit4::class)
class RestoreUiTest {
    @get:Rule val logSandbox = LogSandboxRule()
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context get() = ins.targetContext
    private val prefs get() = ctx.getSharedPreferences(Scans.PREFS, Context.MODE_PRIVATE)
    private var box: File? = null
    private var root: String? = null

    @Before fun setUp() {
        BgScan.auto = false
        Perms.filesOverride = true
        Growth.init(ctx)
        val b = java.nio.file.Files.createTempDirectory(ctx.cacheDir.toPath(), "restore").toFile()
        assertTrue(b.isAbsolute && b.path.startsWith(ctx.cacheDir.path + "/"))
        box = b
        Baseline.dirOverride = File(b, "base").apply { assertTrue(mkdirs()) }
        ins.runOnMainSync { Holder.clear(); Holder.dropPending(); Scans.lastStorage = null }
    }

    @After fun tearDown() {
        BrowserState.testProcessDeath = null
        Motion.override = null
        ins.runOnMainSync {
            for (a in resumedAll()) {
                (a as? BrowserActivity)?.let { if (!it.restoringFromCache) { it.sheet?.dismiss(); it.quickLook?.dismiss() } }
                a.finish()
            }
            Holder.clear(); Holder.dropPending()
        }
        Holder.io.submit {}.get(30, TimeUnit.SECONDS)
        // Кэш дерева песочницы и его запись — свои файлы теста.
        root?.let { r ->
            val f = Holder.cacheFile(ctx, r, false)
            prefs.edit().remove(f.name).commit()
            if (f.path.startsWith(ctx.filesDir.path + "/")) f.delete()
        }
        Baseline.dirOverride = null
        BgScan.auto = true
        Perms.filesOverride = null
        box?.let { d -> if (d.path.startsWith(ctx.cacheDir.path + "/")) d.deleteRecursively() }
    }

    private fun resumedAll(): List<android.app.Activity> =
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList()

    /** Только с главного потока. */
    private fun resumedBrowser(): BrowserActivity? = resumedAll().filterIsInstance<BrowserActivity>().firstOrNull()

    private fun waitFor(ms: Long = 10_000, ok: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            var r = false
            ins.runOnMainSync { r = ok() }
            if (r) return true
            Thread.sleep(25)
        }
        return false
    }

    private fun inBox(rel: String): File = File(box!!, rel).also {
        assertTrue(it.isAbsolute && it.canonicalPath.startsWith(box!!.canonicalPath + "/"))
    }

    private fun put(rel: String, size: Int): File = inBox(rel).also { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(size)) }

    /** tree/A/B — 40 файлов (прокрутка), tree/A/other.bin, tree/C/c.bin, tree/top.bin. */
    private fun fixture(): File {
        val t = inBox("tree").apply { assertTrue(mkdirs()) }
        // Разное число блоков: порядок по диску — f00, f01, … (без равных размеров).
        for (i in 0 until 40) put("tree/A/B/f%02d.bin".format(i), (60 - i) * 4096)
        put("tree/A/other.bin", 5_000)
        put("tree/C/c.bin", 7_000)
        put("tree/top.bin", 3_000)
        root = t.path
        return t
    }

    private fun scan(dir: File): Long {
        val h = Native.scanStart(dir.path, true, 2, IntArray(1))
        val p = LongArray(6)
        val deadline = System.currentTimeMillis() + 10_000
        while (Native.progress(h, p).let { p[0] == ST_RUNNING.toLong() } && System.currentTimeMillis() < deadline)
            Thread.sleep(25)
        assertEquals(ST_DONE.toLong(), p[0])
        return h
    }

    /** Кэш дерева [dir] и запись «caches» — как после скана (то, что переживает смерть процесса). */
    private fun writeCache(dir: File, h: Long) {
        val f = Holder.cacheFile(ctx, dir.path, false)
        assertEquals(0, Holder.io.submit<Int> { Native.saveCache(h, f.path) }.get(30, TimeUnit.SECONDS))
        prefs.edit().putString(f.name, CacheMeta(dir.path, false, 0, 0, System.currentTimeMillis()).format()).commit()
    }

    /** Дерево [dir] в Holder (и его кэш на диске). */
    private fun cached(dir: File) {
        val h = scan(dir)
        writeCache(dir, h)
        ins.runOnMainSync { Holder.set(h, Kind.SCAN, dir.path, false, System.currentTimeMillis()) }
        Holder.io.submit {}.get(30, TimeUnit.SECONDS)
    }

    private fun browse(): BrowserActivity =
        (ins.startActivitySync(Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            as BrowserActivity).also { ins.waitForIdleSync() }

    private fun rows(b: BrowserActivity): List<Row> {
        val src = b.list.source!!
        return (0 until src.count).map { i -> Row().also { src.bind(i, it) } }
    }

    private fun index(b: BrowserActivity, name: String): Int {
        var k = -1
        ins.runOnMainSync { k = rows(b).indexOfFirst { it.name == name } }
        assertTrue("нет строки $name", k >= 0)
        return k
    }

    private fun nameAt(b: BrowserActivity, i: Int): String { var s = ""; ins.runOnMainSync { s = rows(b)[i].name }; return s }

    private fun open(b: BrowserActivity, dir: String) {
        val i = index(b, dir)
        val was = b.node
        ins.runOnMainSync { b.list.source!!.click(i) }
        assertTrue(waitFor { b.node != was })
    }

    private fun selectedNames(b: BrowserActivity): Set<String> {
        var s = emptySet<String>()
        ins.runOnMainSync { s = b.selection.nodes.map { Native.str(Native.name(b.h, it)) }.toSet() }
        return s
    }

    /** Смерть процесса при следующем onCreate: Holder пуст, новая метка процесса; [age] — состарить Bundle. */
    private fun dieOnRecreate(age: ((BrowserState.Saved) -> BrowserState.Saved)? = null) {
        BrowserState.testProcessDeath = { st ->
            if (age != null) BrowserState.decode(st.getByteArray(BrowserState.KEY))?.let {
                st.putByteArray(BrowserState.KEY, BrowserState.encode(age(it)))
            }
            Holder.clear(); Holder.dropPending()
            BrowserState.testNewProcess()
        }
    }

    /** Новый (не [old]) экземпляр со списком — после кэша и пересоздания. */
    private fun next(old: BrowserActivity): BrowserActivity {
        assertTrue(waitFor { resumedBrowser().let { it != null && it !== old && !it.restoringFromCache && it.list.source != null } })
        lateinit var b: BrowserActivity
        ins.runOnMainSync { b = resumedBrowser()!! }
        return b
    }

    // ---------- Part 1 ----------

    /** Папка, прокрутка и выбор по именам переживают смерть процесса; дерево — снова из кэша. */
    @Test fun browserSurvivesProcessDeath() {
        val t = fixture()
        cached(t)
        val b = browse()
        open(b, "A/"); open(b, "B/")
        var scroll = 0
        ins.runOnMainSync {
            b.list.scroll = b.list.rowHeight * 5 + 7
            scroll = b.list.scroll
            b.list.source!!.longClick(6)
            b.list.source!!.click(8)
        }
        assertTrue(scroll > 0)
        val picked = selectedNames(b)
        assertEquals(2, picked.size)
        val oldH = b.h
        dieOnRecreate()
        ins.runOnMainSync { b.recreate() }
        val c = next(b)
        ins.runOnMainSync {
            assertNotEquals("дерево открыто заново (кэш), не старый дескриптор", oldH, c.h)
            assertEquals(Kind.CACHE, Holder.kind)
            assertEquals(t.path, Holder.root)
            assertEquals(File(t, "A/B").path, c.currentPath)
            assertTrue("прокрутка ${c.list.scroll} ≈ $scroll", kotlin.math.abs(c.list.scroll - scroll) <= c.list.rowHeight)
            assertTrue(c.selection.active)
            assertNull("лист не восстанавливается", c.sheet)
            assertNull(c.quickLook)
        }
        assertEquals(picked, selectedNames(c))
    }

    /** Папки уже нет: ближайший предок и одна заметка «Папки уже нет: B». */
    @Test fun goneFolderOpensTheAncestorWithOneNote() {
        val t = fixture()
        cached(t)
        val b = browse()
        open(b, "A/"); open(b, "B/")
        // Новое состояние диска (переименование в песочнице) и новый кэш — как после скана в другом процессе.
        assertTrue(inBox("tree/A/B").renameTo(inBox("tree/A/B2")))
        val h2 = scan(t)
        writeCache(t, h2)
        Holder.io.submit { Native.free(h2) }.get(30, TimeUnit.SECONDS)
        dieOnRecreate()
        ins.runOnMainSync { b.recreate() }
        val c = next(b)
        ins.runOnMainSync {
            assertEquals(File(t, "A").path, c.currentPath)
            assertEquals(c.tx.s(R.string.folder_gone, "B"), c.footerText.toString())
            assertFalse(c.selection.active)
            assertEquals(-1, c.cursorRow)
        }
    }

    /** Старше суток — обычный старт: дерева нет, браузер закрывается без сообщения. */
    @Test fun stateOlderThanADayIsNotRestored() {
        val t = fixture()
        cached(t)
        val b = browse()
        open(b, "A/")
        dieOnRecreate { s ->
            BrowserState.Saved(s.process, s.root, s.su, s.savedAt - BrowserState.MAX_AGE_MS - 60_000, s.giants, s.sort,
                s.apparent, s.scroll, s.chain, s.cursor)
        }
        ins.runOnMainSync { b.recreate() }
        assertTrue(waitFor { resumedBrowser() == null })
        ins.waitForIdleSync()
        assertEquals("кэш не открывался", 0L, Holder.h)
    }

    // ---------- Part 2 ----------

    /** «Назад» из папки — курсор на её строке, вспышка 1,5 с; тап по строке снимает его; без анимаций — без вспышки. */
    @Test fun backLeavesTheCursorOnTheFolder() {
        val t = fixture()
        cached(t)
        Motion.override = true
        val b = browse()
        open(b, "A/")
        ins.runOnMainSync { b.onBackPressed() }
        val a = index(b, "A/")
        ins.runOnMainSync {
            assertEquals(0, b.node)
            assertEquals(a, b.cursorRow)
            assertEquals(a, b.list.cursorRow)
            assertTrue("вспышка прибытия", b.list.flashing)
            val r = rows(b)
            assertTrue(r[a].desc, r[a].desc.endsWith(b.tx.s(R.string.cursor_here)))
            assertFalse(r.filterIndexed { i, _ -> i != a }.any { it.desc.endsWith(b.tx.s(R.string.cursor_here)) })
        }
        assertTrue("вспышка кончается", waitFor(3000) { !b.list.flashing })
        ins.runOnMainSync { assertEquals("блок остаётся", a, b.cursorRow) }
        // Тап по строке файла: курсор снят, открыта карточка; закрыта — курсор на файле.
        val f = index(b, "top.bin")
        ins.runOnMainSync { b.list.source!!.click(f) }
        assertTrue(waitFor { b.quickLook?.dialog?.isShowing == true })
        ins.runOnMainSync { assertEquals(-1, b.cursorRow) }
        ins.runOnMainSync { b.quickLook!!.dismiss() }
        assertTrue(waitFor { b.cursorRow == f })
        // Без анимаций — только постоянный блок.
        Motion.override = false
        open(b, "C/")
        ins.runOnMainSync {
            assertEquals(-1, b.cursorRow)
            b.onBackPressed()
        }
        val c = index(b, "C/")
        ins.runOnMainSync {
            assertEquals(c, b.cursorRow)
            assertFalse(b.list.flashing)
        }
    }

    /** Объекта курсора больше нет (новое дерево): курсор на соседе, «… уже нет на диске». */
    @Test fun goneObjectMovesTheCursorToItsNeighbour() {
        val t = fixture()
        cached(t)
        val b = browse()
        open(b, "A/"); open(b, "B/")
        val k = index(b, "f05.bin")
        ins.runOnMainSync { b.list.source!!.click(k) }
        assertTrue(waitFor { b.quickLook?.dialog?.isShowing == true })
        ins.runOnMainSync { b.quickLook!!.dismiss() }
        assertTrue(waitFor { b.cursorRow == k })
        assertTrue(inBox("tree/A/B/f05.bin").delete())
        val h2 = scan(t)
        ins.runOnMainSync {
            Holder.offer(h2, Kind.SCAN, t.path, false, System.currentTimeMillis())
            b.promotePending()
            assertEquals(h2, b.h)
            assertEquals(k, b.cursorRow)
            assertEquals(DeleteProgress.gone(b.tx, "f05.bin"), b.footerText.toString())
        }
        assertEquals("f06.bin", nameAt(b, k))
        // Сорт сменился — курсор по имени, не по номеру строки.
        ins.runOnMainSync { b.setSort(SORT_NAME) }
        ins.runOnMainSync { assertEquals("f06.bin", rows(b)[b.cursorRow].name) }
    }

    /** Своё удаление: курсор на месте удалённого, без «уже нет на диске» (итог — в подвале). */
    @Test fun afterDeleteTheCursorTakesThePlaceQuietly() {
        val t = fixture()
        cached(t)
        val b = browse()
        open(b, "A/"); open(b, "B/")
        val k = index(b, "f10.bin")
        val victim = inBox("tree/A/B/f10.bin")
        assertEquals(0, b.deleteBlocking(k))
        assertFalse(victim.exists())
        assertTrue(waitFor { !b.busy && b.list.source != null })
        ins.runOnMainSync {
            assertEquals(k, b.cursorRow)
            assertEquals("f11.bin", rows(b)[k].name)
            assertNotEquals(DeleteProgress.gone(b.tx, "f10.bin"), b.footerText.toString())
            assertFalse(b.footerText.toString(), b.footerText.contains(DeleteProgress.gone(b.tx, "f10.bin")))
        }
    }

    /** Курсор переживает пересоздание и смерть процесса (по именам, с тем же объектом). */
    @Test fun cursorSurvivesRecreateAndProcessDeath() {
        val t = fixture()
        cached(t)
        Motion.override = true
        val b = browse()
        open(b, "A/")
        ins.runOnMainSync { b.onBackPressed() }
        val a = index(b, "A/")
        ins.runOnMainSync { b.recreate() }
        val c = next(b)
        ins.runOnMainSync {
            assertEquals(a, c.cursorRow)
            assertFalse("пересоздание — не прибытие", c.list.flashing)
        }
        dieOnRecreate()
        ins.runOnMainSync { c.recreate() }
        val d = next(c)
        var flashing = false
        ins.runOnMainSync { flashing = d.list.flashing }
        assertTrue("возврат после смерти процесса — прибытие", flashing)
        val a2 = index(d, "A/")
        ins.runOnMainSync {
            assertEquals(Kind.CACHE, Holder.kind)
            assertEquals(a2, d.cursorRow)
        }
    }
}
