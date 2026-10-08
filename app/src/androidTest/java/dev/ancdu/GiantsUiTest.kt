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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * «Гиганты»: плоский список всех крупных файлов. Порог снижен тестовым крючком
 * ([GiantsLevel.testMinBytes] = 100 000 Б): настоящие, но маленькие файлы. DESTRUCTIVE-TEST RULE: всё,
 * что удаляется, — в свежем каталоге (mkdtemp) под cacheDir, пути абсолютные и проверены; журнал —
 * в песочнице [LogSandboxRule]; точки отсчёта — в песочнице ([Baseline.dirOverride]).
 */
@RunWith(AndroidJUnit4::class)
class GiantsUiTest {
    @get:Rule val logSandbox = LogSandboxRule()
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context get() = ins.targetContext
    private val prefs get() = ctx.getSharedPreferences(Scans.PREFS, Context.MODE_PRIVATE)
    private val storageKey get() = Holder.cacheFile(ctx, Scans.STORAGE, false).name
    private var savedMeta: String? = null
    private var box: File? = null
    private val opened = ArrayList<android.app.Activity>()

    @Before fun setUp() {
        savedMeta = prefs.getString(storageKey, null)
        BgScan.auto = false
        Perms.filesOverride = true
        GiantsLevel.testMinBytes = MIN
        Growth.init(ctx)
        val b = java.nio.file.Files.createTempDirectory(ctx.cacheDir.toPath(), "giants").toFile()
        assertTrue(b.isAbsolute && b.path.startsWith(ctx.cacheDir.path + "/"))
        box = b
        Baseline.dirOverride = File(b, "base").apply { assertTrue(mkdirs()) }
        ins.runOnMainSync { Holder.clear(); Holder.dropPending(); Scans.lastStorage = null }
    }

    @After fun tearDown() {
        ins.runOnMainSync {
            for (a in opened + resumedAll()) {
                (a as? BrowserActivity)?.let { it.sheet?.dismiss(); it.quickLook?.dismiss() }
                a.finish()
            }
            Holder.clear()
        }
        Holder.io.submit {}.get(30, TimeUnit.SECONDS)
        GiantsLevel.testMinBytes = null
        Baseline.dirOverride = null
        BgScan.auto = true
        Perms.filesOverride = null
        if (savedMeta == null) prefs.edit().remove(storageKey).commit()
        else prefs.edit().putString(storageKey, savedMeta).commit()
        box?.let { d -> if (d.path.startsWith(ctx.cacheDir.path + "/")) d.deleteRecursively() }
    }

    private fun resumedAll(): List<android.app.Activity> =
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList()

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

    /** Файл внутри песочницы (абсолютный путь проверяется). */
    private fun inBox(rel: String): File = File(box!!, rel).also {
        assertTrue(it.isAbsolute && it.canonicalPath.startsWith(box!!.canonicalPath + "/"))
    }

    private fun put(rel: String, size: Int): File = inBox(rel).also { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(size)) }

    /**
     * Дерево: шесть «гигантов» (≥ 100 000 Б) в пяти папках и мелочь рядом с ними.
     * Порядок по диску: big.iso, v1.mp4, v2.mp4, m.mkv, x.bin, top.bin.
     */
    private fun fixture(): File {
        val t = inBox("tree").apply { assertTrue(mkdirs()) }
        put("tree/Download/big.iso", 600_000)
        put("tree/DCIM/Camera/v1.mp4", 500_000)
        put("tree/DCIM/Camera/v2.mp4", 400_000)
        put("tree/Movies/m.mkv", 300_000)
        put("tree/deep/a/b/x.bin", 200_000)
        put("tree/top.bin", 150_000)
        put("tree/Download/s.txt", 1_000)
        put("tree/DCIM/Camera/keep.jpg", 50_000)
        put("tree/Movies/keep.mkv", 90_000)
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

    /** Готовое дерево [dir] → Holder под ключом [key]. */
    private fun scanIn(dir: File, key: String = dir.path) {
        val h = scan(dir)
        ins.runOnMainSync { Holder.set(h, Kind.SCAN, key, false, System.currentTimeMillis()) }
        Holder.io.submit {}.get(30, TimeUnit.SECONDS)
    }

    private fun giants(): BrowserActivity =
        (ins.startActivitySync(Intent(ctx, BrowserActivity::class.java).putExtra(EXTRA_GIANTS, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity).also { opened += it; ins.waitForIdleSync() }

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

    @Test fun homeLinkCountsAllGiantsAndOpensThem() {
        scanIn(fixture(), Scans.STORAGE)
        val m = (ins.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity)
            .also { opened += it; ins.waitForIdleSync() }
        assertTrue(waitFor { m.biggest.rows.size == Biggest.K && m.biggest.allLink.visibility == View.VISIBLE })
        ins.runOnMainSync {
            assertEquals(6L, m.biggest.giantsTotal)
            assertEquals(GiantsText.link(m.tx, 6), m.biggest.allLink.text.toString())
            assertEquals(GiantsText.linkDesc(m.tx, 6), m.biggest.allLink.contentDescription)
            // Бюджет: ссылка — в шапке секции (рядом с заголовком), новой строки нет.
            val header = m.biggest.box.getChildAt(0) as android.view.ViewGroup
            assertEquals(header, m.biggest.allLink.parent)
            assertEquals(2 * Biggest.K - 1, (m.biggest.rowViews.last().parent as android.view.ViewGroup).childCount)
            assertTrue(m.biggest.allLink.height >= m.dp(44))
            m.biggest.allLink.performClick()
        }
        assertTrue(waitFor { resumedAll().filterIsInstance<BrowserActivity>().firstOrNull()?.giants == true })
        ins.runOnMainSync {
            val b = resumedAll().filterIsInstance<BrowserActivity>().first()
            opened += b
            assertEquals(6, b.list.source!!.count)
        }
    }

    @Test fun listOrderSubLinesHeaderAndSegments() {
        val t = fixture()
        scanIn(t)
        val b = giants()
        ins.runOnMainSync {
            val r = rows(b)
            assertEquals(listOf("big.iso", "v1.mp4", "v2.mp4", "m.mkv", "x.bin", "top.bin"), r.map { it.name })
            val rootTitle = PathText.rootTitle(t.path, b.getString(R.string.internal_storage))
            assertEquals(listOf("Download/", "DCIM/Camera/", "DCIM/Camera/", "Movies/", "deep/a/b/", rootTitle), r.map { it.sub })
            assertTrue(r.all { it.pct.isEmpty() && it.bar > 0f })
            assertEquals(1f, r[0].bar, 0.001f)
            assertEquals(GiantsText.desc(b.tx, "v1.mp4", r[1].size, "DCIM/Camera/", null) + ", " + b.getString(R.string.tag_media), r[1].desc)
            assertEquals(b.getString(R.string.big_title), b.title.text.toString())
            assertEquals(t.path, b.currentPath)
            var sum = 0L
            for (i in 0 until b.list.source!!.count) sum += b.value(i)
            assertEquals(GiantsText.summary(b.tx, 6, sum), b.head.summary.text.toString())
            // Сортировка: только РАЗМЕР (Δ — с точкой отсчёта); имени нет; режим размера — есть.
            assertEquals(listOf(b.getString(R.string.sort_size), b.getString(R.string.size_disk), b.getString(R.string.size_apparent)),
                b.segmentTexts())
            assertTrue(b.list.withSub)
            assertTrue(b.list.rowHeight >= b.dp(64))
        }
        // «Назад» — домой (экран закрывается).
        @Suppress("DEPRECATION")
        ins.runOnMainSync { b.onBackPressed() }
        assertTrue(waitFor { b.isFinishing || b.isDestroyed })
    }

    @Test fun emptyStateWithoutGiants() {
        val t = inBox("small").apply { mkdirs() }
        put("small/a.txt", 1000)
        scanIn(t)
        val b = giants()
        ins.runOnMainSync {
            assertEquals(0, b.list.source!!.count)
            assertEquals(View.VISIBLE, b.empty.visibility)
            assertEquals(GiantsText.empty(b.tx), b.empty.text.toString())
        }
    }

    @Test fun tapOpensQuickLook() {
        scanIn(fixture())
        val b = giants()
        val i = index(b, "m.mkv")
        ins.runOnMainSync { b.list.source!!.click(i) }
        assertTrue(waitFor { b.quickLook?.dialog?.isShowing == true })
        ins.runOnMainSync {
            assertEquals("m.mkv", b.quickLook!!.info.name)
            assertTrue(b.quickLook!!.info.path.endsWith("/tree/Movies/m.mkv"))
            assertFalse(b.selection.active)
        }
    }

    @Test fun groupDeleteAcrossThreeFoldersRemovesExactlyThose() {
        val t = fixture()
        val victims = listOf(inBox("tree/Download/big.iso"), inBox("tree/DCIM/Camera/v2.mp4"), inBox("tree/deep/a/b/x.bin"))
        val keep = listOf("tree/DCIM/Camera/v1.mp4", "tree/Movies/m.mkv", "tree/top.bin", "tree/Download/s.txt",
            "tree/DCIM/Camera/keep.jpg", "tree/Movies/keep.mkv").map { inBox(it) }
        scanIn(t)
        val b = giants()
        val idx = listOf("big.iso", "v2.mp4", "x.bin").map { index(b, it) }
        ins.runOnMainSync {
            val s = b.list.source!!
            s.longClick(idx[0]); s.click(idx[1]); s.click(idx[2])
            assertEquals(3, b.selection.count)
            b.deleteSelected()
        }
        assertTrue(waitFor { b.sheet?.dialog?.isShowing == true })
        ins.runOnMainSync {
            val sh = b.sheet!!
            assertEquals(3, sh.group!!.count)
            // Каждая строка листа — путь от корня (имена разных папок не уникальны).
            assertEquals(listOf("Download/big.iso", "DCIM/Camera/v2.mp4", "deep/a/b/x.bin"), sh.childNames)
            assertEquals(GroupSheet.objects(b.tx, 3), sh.p.name)
            assertEquals(GroupSheet.parentPath(t.path), sh.p.path)
            sh.deleteButton!!.performClick()
        }
        assertTrue(waitFor(30_000) { !b.busy && b.list.source != null })
        victims.forEach { assertFalse(it.path, it.exists()) }
        keep.forEach { assertTrue(it.path, it.exists()) }
        Holder.io.submit {}.get(30, TimeUnit.SECONDS)
        ins.runOnMainSync {
            assertFalse(b.selection.active)
            // Список перечитан из ядра: остались три.
            assertEquals(listOf("v1.mp4", "m.mkv", "top.bin"), rows(b).map { it.name })
            assertTrue(b.footerText.toString(), b.footerText.startsWith(b.prefixOf(R.string.freed)))
        }
        // Журнал: ОДНО действие — «(разные папки)», пути от корня.
        val lines = DeleteLogStore(logSandbox.file).lines()
        assertEquals(lines.toString(), 2, lines.size)
        val e = DeleteLogModel.entries(lines).single()
        assertTrue(e.start.group && e.start.mixed)
        assertEquals(3, e.start.count)
        assertTrue(e.start.names.isEmpty())
        assertEquals(listOf("Download/big.iso", "DCIM/Camera/v2.mp4", "deep/a/b/x.bin"), e.start.itemNames.map { String(it) })
        assertEquals(LogOutcome.DELETED, e.outcome)
        ins.runOnMainSync {
            assertEquals(b.getString(R.string.log_several_folders) + " · " + GroupSheet.objects(b.tx, 3), LogRows.title(b.tx, e))
        }
    }

    @Test fun newerTreeRebindsSelectionAndDropsTheVanished() {
        val t = fixture()
        scanIn(t)
        val b = giants()
        val idx = listOf("v1.mp4", "m.mkv").map { index(b, it) }
        ins.runOnMainSync { val s = b.list.source!!; s.longClick(idx[0]); s.click(idx[1]); assertEquals(2, b.selection.count) }
        // m.mkv исчезает с диска (удаляет тест, внутри песочницы), более новое дерево ждёт в Holder.
        val gone = inBox("tree/Movies/m.mkv")
        assertTrue(gone.delete())
        val h2 = scan(t)
        ins.runOnMainSync { Holder.offer(h2, Kind.SCAN, t.path, false); b.promotePending() }
        ins.runOnMainSync {
            assertEquals(h2, b.h)
            assertTrue(b.selection.active)
            assertEquals(1, b.selection.count)
            val r = rows(b)
            assertEquals(listOf("big.iso", "v1.mp4", "v2.mp4", "x.bin", "top.bin"), r.map { it.name })
            // Остался выбран именно v1.mp4 — по цепочке имён в новом дереве.
            assertEquals(true, r[1].checked)
            assertTrue(r.filterIndexed { i, _ -> i != 1 }.all { it.checked == false })
        }
    }

    @Test fun deltaSortInGiants() {
        val t = fixture()
        scanIn(t)
        val before = Growth.computed.get()
        ins.runOnMainSync { Growth.markNow(ctx) }
        Holder.io.submit {}.get(30, TimeUnit.SECONDS)
        assertTrue(waitFor { Growth.computed.get() > before && Growth.forTree(Holder.h, Holder.gen) != null })
        put("tree/DCIM/Camera/v2.mp4", 900_000)
        put("tree/Movies/new.bin", 120_000)
        val c = Growth.computed.get()
        scanIn(t)
        assertTrue(waitFor { Growth.computed.get() > c && Growth.forTree(Holder.h, Holder.gen) != null })
        val b = giants()
        ins.runOnMainSync {
            assertEquals(listOf(b.getString(R.string.sort_size), b.getString(R.string.sort_delta),
                b.getString(R.string.size_disk), b.getString(R.string.size_apparent)), b.segmentTexts())
            b.setSort(SORT_DELTA)
            assertTrue(b.deltaShown)
            val r = rows(b)
            assertEquals("v2.mp4", r[0].name)
            assertTrue(r[0].size, r[0].size.startsWith("+"))
            val nw = r.first { it.name == "new.bin" }
            assertEquals(b.getString(R.string.new_badge), nw.badge)
            // Без строки «ушло»: «гиганты» — не папка.
            assertEquals(null, b.goneText)
            assertEquals(r.size, b.list.source!!.count)
            assertNotNull(r[0].sub)
        }
    }

    private companion object {
        const val MIN = 100_000L
    }
}
