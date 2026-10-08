package dev.ancdu

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * «Что выросло»: дерево свежего каталога (mkdtemp под cacheDir), точки отсчёта — в его же песочнице
 * ([Baseline.dirOverride]): настоящие кэши и точки отсчёта приложения не трогаются. Удаляется только
 * внутри песочницы (old.bin — «ушло»; в конце — сама песочница), пути проверяются.
 */
@RunWith(AndroidJUnit4::class)
class GrowthUiTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context get() = ins.targetContext
    private var box: File? = null
    private val opened = ArrayList<android.app.Activity>()

    @Before fun setUp() {
        BgScan.auto = false
        Perms.filesOverride = true
        Growth.init(ctx)
        val b = java.nio.file.Files.createTempDirectory(ctx.cacheDir.toPath(), "grow").toFile()
        assertTrue(b.isAbsolute && b.path.startsWith(ctx.cacheDir.path + "/"))
        box = b
        Baseline.dirOverride = File(b, "base").apply { assertTrue(mkdirs()) }
        ins.runOnMainSync { Holder.clear(); Holder.dropPending(); Scans.lastStorage = null }
    }

    @After fun tearDown() {
        ins.runOnMainSync {
            for (a in opened) a.finish()
            Holder.clear()
        }
        Holder.io.submit {}.get(30, TimeUnit.SECONDS)
        Baseline.dirOverride = null
        BgScan.auto = true
        Perms.filesOverride = null
        box?.let { d -> if (d.path.startsWith(ctx.cacheDir.path + "/")) d.deleteRecursively() }
    }

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

    /** Корень дерева: a/big.bin 100 000, old.bin 30 000, shrink.bin 200 000, keep.bin 10 000. */
    private fun fixture(): File {
        val t = inBox("tree").apply { assertTrue(mkdirs()) }
        inBox("tree/a").mkdirs()
        inBox("tree/a/big.bin").writeBytes(ByteArray(100_000))
        inBox("tree/old.bin").writeBytes(ByteArray(30_000))
        inBox("tree/shrink.bin").writeBytes(ByteArray(200_000))
        inBox("tree/keep.bin").writeBytes(ByteArray(10_000))
        return t
    }

    /** Правки после точки отсчёта: вырос, сжался, ушёл, появился. */
    private fun mutate() {
        inBox("tree/a/big.bin").writeBytes(ByteArray(400_000))
        inBox("tree/shrink.bin").writeBytes(ByteArray(50_000))
        assertTrue(inBox("tree/old.bin").delete())
        inBox("tree/fresh.bin").writeBytes(ByteArray(70_000))
    }

    /** Готовое дерево [dir] → Holder (как после скана) под ключом [key]. */
    private fun scanIn(dir: File, key: String = dir.path) {
        val h = Native.scanStart(dir.path, true, 2, IntArray(1))
        val p = LongArray(6)
        val deadline = System.currentTimeMillis() + 10_000
        while (Native.progress(h, p).let { p[0] == ST_RUNNING.toLong() } && System.currentTimeMillis() < deadline)
            Thread.sleep(25)
        assertEquals(ST_DONE.toLong(), p[0])
        ins.runOnMainSync { Holder.set(h, Kind.SCAN, key, false, System.currentTimeMillis()) }
        Holder.io.submit {}.get(30, TimeUnit.SECONDS)
    }

    private fun resumed(): BrowserActivity? =
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<BrowserActivity>().firstOrNull()

    private fun browser(): BrowserActivity =
        (ins.startActivitySync(Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity)
            .also { opened += it; ins.waitForIdleSync() }

    private fun rows(b: BrowserActivity): List<Row> {
        val src = b.list.source!!
        return (0 until src.count).map { i -> Row().also { src.bind(i, it) } }
    }

    /** Точка отсчёта := показанное дерево, дождаться пересчёта Δ. */
    private fun markAndWait() {
        val before = Growth.computed.get()
        ins.runOnMainSync { Growth.markNow(ctx) }
        Holder.io.submit {}.get(30, TimeUnit.SECONDS)
        assertTrue(waitFor { Growth.computed.get() > before && Growth.forTree(Holder.h, Holder.gen) != null })
    }

    @Test fun deltaSegmentHiddenWithoutBaseline() {
        scanIn(fixture())
        val b = browser()
        ins.runOnMainSync {
            assertNull(Growth.forTree(Holder.h, Holder.gen))
            val segs = b.segmentTexts()
            assertEquals(listOf(b.getString(R.string.sort_size), b.getString(R.string.sort_name),
                b.getString(R.string.size_disk), b.getString(R.string.size_apparent)), segs)
            assertFalse(segs.contains(b.getString(R.string.sort_delta)))
            // Δ без точки отсчёта не выбирается: остаётся размер.
            b.setSort(SORT_DELTA)
            assertFalse(b.deltaShown)
            assertNull(b.goneText)
        }
    }

    @Test fun browserDeltaSortShowsSignedDeltaNewBadgeAndGone() {
        val t = fixture()
        scanIn(t)
        markAndWait()
        mutate()
        scanIn(t)
        assertTrue("Δ не посчитана", waitFor { Growth.forTree(Holder.h, Holder.gen) != null })
        val b = browser()
        ins.runOnMainSync {
            val tx = b.tx
            assertTrue(b.segmentTexts().contains(b.getString(R.string.sort_delta)))
            b.setApparent(true)
            b.setSort(SORT_DELTA)
            assertTrue(b.deltaShown)
            val r = rows(b)
            // Рост первым: a/ (+300 000 и сам каталог), fresh.bin (+70 000, новый), keep.bin (±0), shrink.bin (−150 000).
            assertEquals(listOf("a/", "fresh.bin", "keep.bin", "shrink.bin"), r.take(4).map { it.name })
            assertTrue(r[0].size, r[0].size.startsWith("+"))
            assertEquals(C.AMBER_TEXT, r[0].sizeColor)
            assertEquals(GrowthText.signed(70_000, tx), r[1].size)
            assertEquals(b.getString(R.string.new_badge), r[1].badge)
            assertEquals(Fmt.size(70_000, tx), r[1].pct)
            assertEquals("±0", r[2].size)
            // Без изменений — приглушённо: выделяется только рост.
            assertEquals(C.MUTED, r[2].sizeColor)
            assertNull(r[2].badge)
            assertEquals(GrowthText.signed(-150_000, tx), r[3].size)
            assertEquals(C.MUTED, r[3].sizeColor)
            assertTrue(r[3].desc, r[3].desc.contains(GrowthText.signed(-150_000, tx)))
            // «Ушло» — последняя строка, без касаний.
            assertEquals(5, r.size)
            assertEquals(GrowthText.gone(tx, 1, 30_000), r[4].note)
            assertEquals(GrowthText.gone(tx, 1, 30_000), b.goneText)
            assertFalse(b.list.source!!.interactive(4))
            val d = Growth.forTree(Holder.h, Holder.gen)!!
            assertEquals(GrowthText.badge(tx, d.baseTime), b.badge.text.toString())
            // Раскладка: в Δ полосы нет, правая колонка — по своему тексту; имени остаётся не меньше,
            // чем в сортировке по размеру, за вычетом колонки текущего размера.
            val w = b.list.width
            assertTrue(w > 0)
            val nameDelta = b.list.nameWidthFor(w)
            val curCol = b.list.rightColWidth
            // Размер (не Δ) — без строки «ушло» и без знаков.
            b.setSort(SORT_SIZE)
            val nameSize = b.list.nameWidthFor(w)
            assertTrue("имя в Δ $nameDelta < $nameSize − $curCol", nameDelta >= nameSize - curCol)
            // Полоса (64dp + отступ) отдана имени: при той же правой колонке имя в Δ шире. При шрифте
            // > 130% полосы нет и в сортировке по размеру.
            if (b.resources.configuration.fontScale <= 1.3f) assertTrue("полоса не отдана имени: $nameDelta, $nameSize, $curCol, ${b.list.rightColWidth}",
                nameDelta - (nameSize - (curCol - b.list.rightColWidth)) >= b.dp(64))
            assertFalse(b.deltaShown)
            assertEquals(4, b.list.source!!.count)
            assertNull(rows(b)[0].badge)
        }
        // Пересоздание: та же сортировка Δ.
        ins.runOnMainSync { b.setSort(SORT_DELTA); b.recreate() }
        assertTrue(waitFor { resumed().let { it != null && it !== b } })
        ins.runOnMainSync {
            val b2 = resumed()!!
            opened += b2
            assertTrue(b2.deltaShown)
            assertNotNull(b2.goneText)
        }
    }

    @Test fun markNowZeroesTheDelta() {
        val t = fixture()
        scanIn(t)
        markAndWait()
        mutate()
        scanIn(t)
        assertTrue(waitFor { Growth.forTree(Holder.h, Holder.gen) != null })
        val b = browser()
        var before = 0
        ins.runOnMainSync {
            b.setSort(SORT_DELTA)
            // Плашка «Δ с …» — касаемая, 44dp; тап — лист точки отсчёта.
            assertTrue(b.badge.isClickable)
            assertTrue(b.badge.minHeight >= b.dp(44))
            assertTrue(b.badge.performClick())
            val sh = b.baselineSheet
            assertNotNull(sh)
            assertTrue(sh!!.dialog.isShowing)
            val d = Growth.forTree(Holder.h, Holder.gen)!!
            assertEquals(Freshness.date(b.tx, R.string.fmt_since_time, d.baseTime), sh.dateText.text.toString())
            assertTrue(sh.infoText.text.toString().startsWith(b.getString(R.string.today)))
            assertTrue(sh.markButton.height >= b.dp(56) || sh.markButton.minimumHeight >= b.dp(56))
            before = Growth.computed.get()
            // «Отметить сейчас»: сразу, без подтверждения; лист закрывается.
            assertTrue(sh.markButton.performClick())
            assertFalse(sh.dialog.isShowing)
        }
        Holder.io.submit {}.get(30, TimeUnit.SECONDS)
        assertTrue(waitFor { Growth.computed.get() > before && b.deltaShown && b.goneText == null })
        ins.runOnMainSync {
            val r = rows(b)
            assertEquals(4, r.size)
            for (row in r) {
                assertEquals(row.name, "±0", row.size)
                assertNull(row.name, row.badge)
            }
        }
    }

    private fun mainActivity(): MainActivity =
        (ins.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity)
            .also { opened += it; ins.waitForIdleSync() }

    /**
     * Главный экран: дерево песочницы — как дерево общего хранилища (ключ STORAGE, точка отсчёта — в
     * песочнице). Без точки отсчёта строки нет; после роста в Telegram/Video — «+… с … · больше всего
     * Telegram/Video ›», у нового крупнейшего файла — NEW; тап — браузер в Telegram/Video в сортировке Δ.
     */
    @Test fun homeShowsGrowthLineAndTapLandsOnMostly() {
        val t = inBox("store").apply { assertTrue(mkdirs()) }
        inBox("store/Telegram/Video").mkdirs()
        inBox("store/Telegram/Video/a.bin").writeBytes(ByteArray(100_000))
        inBox("store/Other").mkdirs()
        inBox("store/Other/x.bin").writeBytes(ByteArray(50_000))
        scanIn(t, Scans.STORAGE)
        val m = mainActivity()
        assertTrue(waitFor { m.biggest.rows.isNotEmpty() })
        ins.runOnMainSync {
            assertNull(m.storage.growth)
            assertFalse(m.storage.status is Status.Grew)
        }
        markAndWait()
        inBox("store/Telegram/Video/new.mp4").writeBytes(ByteArray(3 shl 20))
        inBox("store/Other/x.bin").writeBytes(ByteArray(60_000))
        scanIn(t, Scans.STORAGE)
        assertTrue("Δ не посчитана", waitFor { Growth.forTree(Holder.h, Holder.gen) != null })
        assertTrue("строки «что выросло» нет", waitFor { m.storage.growth != null })
        ins.runOnMainSync {
            val g = m.storage.growth!!
            val d = Growth.forTree(Holder.h, Holder.gen)!!
            assertEquals(d.of(0, false), g.delta)
            assertTrue(g.delta >= 3L shl 20)
            assertEquals("Telegram/Video", g.path)
            assertTrue(m.storage.status is Status.Grew)
            val full = GrowthText.home(m.tx, g.delta, d.baseTime, "Telegram/Video")
            val short = StatusLine.text(m.tx, m.storage.status, System.currentTimeMillis(), wide = false)
            assertTrue(m.storage.statusTxt.text.toString(), m.storage.statusTxt.text.toString() in setOf(full, short))
            assertEquals(C.AMBER_TEXT, m.storage.statusTxt.currentTextColor)
            assertTrue(m.storage.statusTxt.minHeight >= m.dp(48))
        }
        assertTrue(waitFor { m.biggest.rows.firstOrNull()?.name == "new.mp4" })
        ins.runOnMainSync {
            assertTrue(m.biggest.rows[0].isNew)
            assertEquals(m.getString(R.string.new_badge), (m.biggest.rowViews[0] as BigRow).badge)
            assertFalse(m.biggest.rows.first { it.name == "x.bin" }.isNew)
        }
        val mon = ins.addMonitor(BrowserActivity::class.java.name, null, false)
        try {
            ins.runOnMainSync { assertTrue(m.storage.statusTxt.performClick()) }
            val b = ins.waitForMonitorWithTimeout(mon, 10_000) as BrowserActivity?
            assertNotNull("браузер не открылся", b)
            opened += b!!
            assertTrue(waitFor { b.list.source != null && b.deltaShown })
            ins.runOnMainSync {
                assertEquals("Video", b.title.text.toString())
                val r = rows(b)
                assertEquals("new.mp4", r[0].name)
                assertEquals(b.getString(R.string.new_badge), r[0].badge)
                assertTrue(r[0].size.startsWith("+"))
            }
        } finally {
            ins.removeMonitor(mon)
        }
    }
}
