package dev.ancdu

import android.content.Intent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BrowserTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    /** Опрос с таймаутом: выходит при успехе, иначе после [ms]. */
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

    private fun scan(dir: File) {
        val h = scanned(dir)
        ins.runOnMainSync { Holder.set(h, Kind.SCAN, dir.path, false) }   // Holder.set — только главный поток
    }

    /** Готовое дерево [dir], ещё не в Holder. */
    private fun scanned(dir: File): Long {
        val h = Native.scanStart(dir.path, true, 2, IntArray(1))
        val p = LongArray(6)
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            Native.progress(h, p)
            if (p[0] != ST_RUNNING.toLong() || System.currentTimeMillis() > deadline) break
            Thread.sleep(25)
        }
        assertEquals(ST_DONE.toLong(), p[0])
        return h
    }

    /** Только с главного потока. */
    private fun resumedBrowser(): BrowserActivity? =
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<BrowserActivity>().firstOrNull()

    /** Удаление переживает пересоздание экрана: новый экземпляр не читает дерево, пока оно идёт. */
    @Test fun deleteSurvivesRecreate() {
        val ctx = ins.targetContext
        val dir = File(ctx.cacheDir, "br2").apply { deleteRecursively(); mkdirs() }
        File(dir, "keep.bin").writeBytes(ByteArray(50_000))
        File(dir, "gone.bin").writeBytes(ByteArray(10))
        scan(dir)
        val act1 = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        var count = 0
        ins.runOnMainSync { count = act1.list.source!!.count }
        assertEquals(2, count)

        // Заслонка на io: удаление встанет в очередь за ней и не начнётся, пока её не откроем.
        val gate = CountDownLatch(1)
        Holder.io.execute { gate.await(30, TimeUnit.SECONDS) }
        var r = Int.MIN_VALUE
        val deleter = Thread { r = act1.deleteBlocking(1) }.apply { start() } // по размеру: 1 — gone.bin
        assertTrue(waitFor { Holder.deleting })

        ins.runOnMainSync { act1.recreate() }
        assertTrue(waitFor { resumedBrowser().let { it != null && it !== act1 } })
        lateinit var act2: BrowserActivity
        ins.runOnMainSync { act2 = resumedBrowser()!! }
        assertNotSame(act1, act2)
        ins.runOnMainSync {
            assertTrue(act2.busy)
            assertNull(act2.list.source)
            assertEquals(0, act2.loads)
        }

        gate.countDown()
        deleter.join(30_000)
        assertFalse(deleter.isAlive)
        assertEquals(0, r)
        assertTrue(waitFor { !act2.busy && act2.list.source != null })
        ins.runOnMainSync {
            assertTrue(act2.footerText.toString(), act2.footerText.startsWith(act2.prefixOf(R.string.freed)))
            assertEquals(1, act2.loads)
            val src = act2.list.source!!
            assertEquals(1, src.count)
            val row = Row().also { src.bind(0, it) }
            assertEquals("keep.bin", row.name)
        }
        assertFalse(File(dir, "gone.bin").exists())
        assertTrue(File(dir, "keep.bin").exists())

        ins.runOnMainSync { act2.finish() }
        dir.deleteRecursively()
    }

    /**
     * Диалог удаления: заголовок с именем, счётчик «N / M эл.», «Стоп» → «Останавливаю…».
     * Удаление стоит на io за заслонкой, стоп нажат до его начала: ядро не вызывается, итог -EINTR,
     * всё на месте; без диалога — подвал «Удаление отменено — ничего не удалено.» (дерево не обновляется).
     */
    @Test fun deleteDialogShowsProgressAndStops() {
        val ctx = ins.targetContext
        val dir = File(ctx.cacheDir, "br5").apply { deleteRecursively(); mkdirs() }
        val sub = File(dir, "sub").apply { mkdirs() }
        for (i in 0 until 3) File(sub, "f$i.bin").writeBytes(ByteArray(50_000))
        File(dir, "keep.bin").writeBytes(ByteArray(10))
        scan(dir)
        val act = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()

        val gate = CountDownLatch(1)
        Holder.io.execute { gate.await(30, TimeUnit.SECONDS) }
        var r = Int.MIN_VALUE
        val deleter = Thread { r = act.deleteBlocking(0) }.apply { start() } // по размеру: 0 — sub/
        assertTrue(waitFor { act.waitBar != null })
        ins.runOnMainSync {
            assertEquals("sub", Holder.delName)
            assertEquals(4L, Holder.delTotal) // sub + 3 файла
            assertTrue(act.waitText!!.text.startsWith(act.tx.q(R.plurals.items, 4, "0 / 4")))
            assertEquals(act.getString(R.string.stop), act.waitStop!!.text.toString())
            assertEquals(1000, act.waitBar!!.max)
            assertNotNull(act.waitBar!!.contentDescription)
            act.stopDelete()
            assertEquals(act.getString(R.string.stopping), act.waitStop!!.text.toString())
            assertFalse(act.waitStop!!.isEnabled)
        }

        gate.countDown()
        deleter.join(30_000)
        assertFalse(deleter.isAlive)
        assertEquals(-DeleteProgress.EINTR, r)
        assertTrue(waitFor { !act.busy && act.list.source != null })
        ins.runOnMainSync {
            assertNull(act.waitBar)
            // ещё не начиналось — «отменено» в подвале, без диалога; обновлять нечего
            assertNull(act.lastAlert)
            assertEquals(DeleteProgress.cancelled(act.tx), act.footerText.toString())
        }
        for (i in 0 until 3) assertTrue(File(sub, "f$i.bin").exists())

        // Отменённый в очереди стоп не переходит на следующее удаление: то же удаляется целиком.
        assertEquals(0, act.deleteBlocking(0))
        assertFalse(sub.exists())

        ins.runOnMainSync { act.finish() }
        dir.deleteRecursively()
    }

    /**
     * Смена сессии при открытом браузере: Holder.set синхронно отцепляет его от старого дескриптора
     * (до того как free(old) уходит на io), затем экран пересоздаётся на новом дереве.
     */
    @Test fun setWhileBrowserResumedDetachesSynchronously() {
        val ctx = ins.targetContext
        val a = File(ctx.cacheDir, "br3a").apply { deleteRecursively(); mkdirs() }
        val b = File(ctx.cacheDir, "br3b").apply { deleteRecursively(); mkdirs() }
        File(a, "one.bin").writeBytes(ByteArray(10))
        File(a, "two.bin").writeBytes(ByteArray(20))
        File(b, "only.bin").writeBytes(ByteArray(30))
        scan(a)
        val act1 = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        var count = 0
        ins.runOnMainSync { count = act1.list.source!!.count }
        assertEquals(2, count)

        val hb = scanned(b)
        // Заслонка на io: free(old) не выполнится, пока не откроем, — отцепление не может «успеть» за счёт io.
        val gate = CountDownLatch(1)
        Holder.io.execute { gate.await(30, TimeUnit.SECONDS) }
        ins.runOnMainSync {
            Holder.set(hb, Kind.SCAN, b.path, false)
            assertNull(act1.list.source)   // отцеплен синхронно, внутри set
        }
        gate.countDown()

        assertTrue(waitFor { resumedBrowser().let { it != null && it !== act1 } })
        lateinit var act2: BrowserActivity
        ins.runOnMainSync { act2 = resumedBrowser()!! }
        ins.runOnMainSync {
            val src = act2.list.source!!
            assertEquals(1, src.count)
            val row = Row().also { src.bind(0, it) }
            assertEquals("only.bin", row.name)
        }
        ins.runOnMainSync { act2.finish() }
        a.deleteRecursively(); b.deleteRecursively()
    }

    @Test fun navigateSortDelete() {
        val ctx = ins.targetContext
        val dir = File(ctx.cacheDir, "br").apply { deleteRecursively(); mkdirs() }
        File(dir, "sub").mkdirs()
        File(dir, "sub/big.bin").writeBytes(ByteArray(300_000))
        File(dir, "a.txt").writeBytes(ByteArray(5000))
        File(dir, "b.txt").writeBytes(ByteArray(10))

        scan(dir)

        val act = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        val row = Row()
        // Чтения дерева — только с главного потока (контракт Native).
        fun name(i: Int): String {
            var s = ""
            ins.runOnMainSync { row.reset(); act.list.source!!.bind(i, row); s = row.name }
            return s
        }
        fun count(): Int { var c = 0; ins.runOnMainSync { c = act.list.source!!.count }; return c }

        assertEquals(3, count())
        assertEquals("sub/", name(0))
        ins.runOnMainSync { act.list.source!!.click(0) }
        assertNotEquals(0, act.node)
        assertEquals(1, count())
        assertEquals("big.bin", name(0))
        ins.runOnMainSync { act.onBackPressed() }
        assertEquals(0, act.node)

        ins.runOnMainSync { act.setSort(SORT_NAME) }
        assertEquals("a.txt", name(0))
        val bIndex = (0 until 3).first { name(it) == "b.txt" }
        assertEquals(0, act.deleteBlocking(bIndex))
        assertFalse(File(dir, "b.txt").exists())
        assertEquals(2, count())

        ins.runOnMainSync { act.finish() }
        dir.deleteRecursively()
    }

    /** Кэш: каталог мимо листа не удаляется (сначала обновление дерева), файл — можно. */
    @Test fun cacheRefusesDirDelete() {
        val ctx = ins.targetContext
        val dir = File(ctx.cacheDir, "br4").apply { deleteRecursively(); mkdirs() }
        File(dir, "sub").mkdirs()
        File(dir, "sub/big.bin").writeBytes(ByteArray(300_000))
        File(dir, "f.txt").writeBytes(ByteArray(10))
        val h = scanned(dir)
        ins.runOnMainSync { Holder.set(h, Kind.CACHE, dir.path, false) }

        val act = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        val row = Row()
        fun name(i: Int): String {
            var s = ""
            ins.runOnMainSync { row.reset(); act.list.source!!.bind(i, row); s = row.name }
            return s
        }
        assertEquals("sub/", name(0))
        assertEquals(Int.MIN_VALUE, act.deleteBlocking(0))   // отказ: удаление не запускалось
        assertTrue(File(dir, "sub/big.bin").exists())
        assertEquals("f.txt", name(1))
        assertEquals(0, act.deleteBlocking(1))
        assertFalse(File(dir, "f.txt").exists())

        ins.runOnMainSync { act.finish() }
        dir.deleteRecursively()
    }

    /** Свежий каталог теста (mkdtemp) под cacheDir приложения — абсолютный путь. */
    private fun tmpDir(prefix: String): File =
        java.nio.file.Files.createTempDirectory(ins.targetContext.cacheDir.toPath(), prefix).toFile()

    /**
     * Пиктограмма сортировки и [РАЗМЕР|ИМЯ] — один ребёнок Flow: при переносе не разрываются.
     * Пиктограмма не касаемая; смысл — в описаниях сегментов.
     */
    @Test fun sortIconAndSegmentAreOneFlowChild() {
        val ctx = ins.targetContext
        val dir = tmpDir("sorti")
        File(dir, "a.bin").writeBytes(ByteArray(10))
        scan(dir)
        val act = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        try {
            ins.runOnMainSync {
                assertEquals(2, act.chips.childCount)
                val segs = act.segments()
                assertEquals(4, segs.size)
                val sortSeg = segs[0].parent as View
                val group = sortSeg.parent as android.widget.LinearLayout
                assertSame(act.chips, group.parent)
                val icon = group.getChildAt(0)
                assertTrue(icon is android.widget.ImageView)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, icon.importantForAccessibility)
                assertSame(sortSeg, group.getChildAt(1))
                assertEquals(act.getString(R.string.sort_size_desc), segs[0].contentDescription)
                assertEquals(act.getString(R.string.sort_name_desc), segs[1].contentDescription)
            }
        } finally {
            ins.runOnMainSync { act.finish() }
            forgetCache(dir)
            dir.deleteRecursively()
        }
    }

    /**
     * Высота шапки не зависит от сортировки, режима размера («на диске»/«видимый»), плашки и чипа
     * «новее». Строка пути есть всегда: на корне и во вложенных папках высота одна и та же.
     */
    @Test fun headerHeightIsStable() {
        val ctx = ins.targetContext
        val dir = tmpDir("hdr")
        File(dir, "sub/deep").mkdirs()
        File(dir, "sub/deep/a.bin").writeBytes(ByteArray(3_000_000))
        File(dir, "z.bin").writeBytes(ByteArray(10))
        scan(dir)
        val act = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        fun height(): Int { ins.waitForIdleSync(); var v = 0; ins.runOnMainSync { v = act.header.height }; return v }
        try {
            val h0 = height()
            ins.runOnMainSync {
                val min = (44 * ctx.resources.displayMetrics.density).toInt()
                val segs = act.segments()
                assertEquals(4, segs.size)
                segs.forEach { assertTrue("сегмент ниже 44dp: ${it.height}", it.height >= min) }
                assertEquals(listOf(true, false, true, false), segs.map { it.isSelected })
            }
            assertTrue("шапка не измерена", h0 > 0)
            ins.runOnMainSync { act.setApparent(true) }
            assertEquals("видимый", h0, height())
            ins.runOnMainSync { act.setSort(SORT_NAME) }
            assertEquals("сортировка по имени", h0, height())
            ins.runOnMainSync { act.setApparent(false) }
            assertEquals("на диске", h0, height())
            ins.runOnMainSync { act.list.source!!.click(0) }   // sub/ (по имени: sub < z.bin)
            assertNotEquals(0, act.node)
            val h1 = height()
            assertEquals("корень и вложенная папка", h0, h1)
            ins.runOnMainSync { act.list.source!!.click(0) }   // sub/deep/
            assertEquals("вторая вложенная папка", h1, height())
            ins.runOnMainSync { act.onBackPressed() }
            ins.runOnMainSync { act.setApparent(true) }
            assertEquals("вложенная папка, видимый", h1, height())

            val h2 = scanned(dir)
            ins.runOnMainSync { Holder.offer(h2, Kind.SCAN, dir.path, false); act.refreshPending() }
            ins.runOnMainSync { assertEquals(View.VISIBLE, act.newer.visibility) }
            assertEquals("с чипом «новее»", h1, height())
            // Самая длинная плашка рядом с чипом переносится (до 2 строк), не обрезается; две строки
            // 12sp ниже 44dp строки чипа — высота шапки та же.
            ins.runOnMainSync { act.badge.text = Badge.text(act.tx, Kind.ROOT, 0L, 12_300L, true) }
            assertEquals("длинная плашка с чипом", h1, height())
            ins.runOnMainSync {
                val l = act.badge.layout
                assertEquals(act.badge.text.toString(), 0, l.getEllipsisCount(l.lineCount - 1))
            }
            ins.runOnMainSync { act.onBackPressed() }
            assertEquals("корень с чипом «новее»", h0, height())
        } finally {
            ins.runOnMainSync { Holder.dropPending(); act.finish() }
            forgetCache(dir)
            dir.deleteRecursively()
        }
    }

    /**
     * Строка пути: есть и на корне (той же высоты, не ниже 44dp), путь — полный путь папки.
     * Тап — панель пути: полный путь, «Копировать путь» (буфер обмена, подвал «Путь скопирован»),
     * предки от корня; тап по предку — переход к нему. Долгое нажатие копирует сразу.
     */
    @Test fun pathRowPanelCopyJump() {
        val ctx = ins.targetContext
        val dir = tmpDir("path")
        File(dir, "a/b").mkdirs()
        File(dir, "a/b/c.bin").writeBytes(ByteArray(10))
        scan(dir)
        val act = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        // Только из waitFor: он уже на главном потоке.
        fun clip(): String? = act.getSystemService(android.content.ClipboardManager::class.java)
            .primaryClip?.getItemAt(0)?.text?.toString()
        try {
            var rootH = 0
            ins.runOnMainSync {
                assertEquals(View.VISIBLE, act.pathRow.visibility)
                assertEquals(dir.path, act.currentPath)
                assertEquals(dir.name, act.title.text.toString())
                assertTrue(act.pathRow.height >= (44 * ctx.resources.displayMetrics.density).toInt())
                assertEquals(act.getString(R.string.path_row_desc, dir.path), act.pathRow.contentDescription)
                rootH = act.header.height
                act.list.source!!.click(0)   // a/
                act.list.source!!.click(0)   // a/b/
            }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                assertEquals("b", act.title.text.toString())
                assertEquals(File(dir, "a/b").path, act.currentPath)
                assertEquals("шапка на корне и в a/b", rootH, act.header.height)
                act.pathRow.performClick()
            }
            assertTrue(waitFor { act.pathPanel?.dialog?.isShowing == true })
            ins.runOnMainSync {
                val p = act.pathPanel!!
                assertEquals(File(dir, "a/b").path, p.pathText.text.toString())
                assertTrue(p.pathText.isTextSelectable)
                assertEquals(act.crumbNodes.toList(), p.rowNodes)
                assertEquals(3, p.rows.size)
                assertFalse("текущая папка не касаемая", p.rows.last().isClickable)
                p.copyButton.performClick()
                assertFalse(p.dialog.isShowing)
                assertEquals(act.getString(R.string.path_copied), act.footerText.toString())
            }
            assertTrue(waitFor { clip() == File(dir, "a/b").path })

            // Переход к предку из панели: корень, прокрутка/анимация как у прежних крошек.
            ins.runOnMainSync { act.pathRow.performClick() }
            assertTrue(waitFor { act.pathPanel?.dialog?.isShowing == true })
            ins.runOnMainSync {
                val p = act.pathPanel!!
                p.rows[1].performClick()     // a/
                assertFalse(p.dialog.isShowing)
                assertEquals(act.crumbNodes[1], act.node)
                assertEquals("a", act.title.text.toString())
            }

            // Долгое нажатие — копирует сразу.
            ins.runOnMainSync {
                act.getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("x", "x"))
                act.pathRow.performLongClick()
                assertEquals(act.getString(R.string.path_copied), act.footerText.toString())
            }
            assertTrue(waitFor { clip() == File(dir, "a").path })
        } finally {
            ins.runOnMainSync { act.finish() }
            forgetCache(dir)
            dir.deleteRecursively()
        }
    }

    /**
     * Фоновое обновление показанного дерева: полоса 2dp под линией шапки и «обновление · N» на
     * плашке. Высота шапки и верх списка не меняются (место полосы держится всегда); плашка — в
     * одну строку и при шрифте 200%. Счёт — не чаще раза в секунду и молча; начало, ожидание и
     * конец — голосом (live region). Кончился с новым деревом — полоса на 100% и скрывается,
     * без нового дерева — скрывается сразу; плашка снова показывает вид дерева.
     */
    @Test fun refreshProgressInHeader() {
        val ctx = ins.targetContext
        // Настоящий скан писал бы в BgScan.p поверх подставленного счёта.
        assertTrue(waitFor(30_000) { !BgScan.active })
        val dir = tmpDir("prog")
        File(dir, "sub").mkdirs()
        File(dir, "sub/a.bin").writeBytes(ByteArray(1000))
        scan(dir)
        for (scale in listOf(1f, 2f)) {
            Lang.fontScale = scale
            val act = ins.startActivitySync(
                Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
            fun geo(): Pair<Int, Int> {
                ins.waitForIdleSync()
                var g = 0 to 0
                ins.runOnMainSync { g = act.header.height to IntArray(2).also { act.list.getLocationOnScreen(it) }[1] }
                return g
            }
            fun state(st: ScanState?, files: Long = BgScan.p[1]) =
                ins.runOnMainSync { BgScan.p[1] = files; BgScan.stateOverride = st; BgScan.changed() }
            try {
                val idle = geo()
                var source = ""
                ins.runOnMainSync {
                    assertEquals(View.INVISIBLE, act.scanLine.visibility)
                    source = act.badge.text.toString()
                }
                state(ScanState.RUNNING, 1_234_567)
                assertEquals("scale=$scale: идёт", idle, geo())
                ins.runOnMainSync {
                    assertEquals(View.VISIBLE, act.scanLine.visibility)
                    assertEquals(ScanProgress.CAP, act.scanLine.fraction!!, 0f)   // files > items корня
                    assertEquals(ScanProgress.badge(act.tx, ScanState.RUNNING, 1_234_567), act.badge.text.toString())
                    assertEquals("scale=$scale: ${act.badge.text}", 1, act.badge.lineCount)
                    assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, act.badge.accessibilityLiveRegion)
                }
                state(ScanState.RUNNING, 1_234_999)   // тот же тик секунды — плашка прежняя
                ins.runOnMainSync {
                    assertEquals(ScanProgress.badge(act.tx, ScanState.RUNNING, 1_234_567), act.badge.text.toString())
                }
                Thread.sleep(ScanProgress.BADGE_MS + 100)
                state(ScanState.RUNNING)
                ins.runOnMainSync {
                    assertEquals(ScanProgress.badge(act.tx, ScanState.RUNNING, 1_234_999), act.badge.text.toString())
                    assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, act.badge.accessibilityLiveRegion)
                }
                state(ScanState.QUEUED)
                assertEquals("scale=$scale: ждёт", idle, geo())
                ins.runOnMainSync {
                    assertEquals(View.VISIBLE, act.scanLine.visibility)
                    assertNull(act.scanLine.fraction)
                    assertEquals(ScanProgress.badge(act.tx, ScanState.QUEUED, 0), act.badge.text.toString())
                    assertEquals(1, act.badge.lineCount)
                    assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, act.badge.accessibilityLiveRegion)
                }
                // Кончился без нового дерева (не удался): полоса скрыта сразу, плашка молча — вид дерева.
                ins.runOnMainSync { BgScan.endOverride = ScanEnd.FAILED }
                state(null)
                fun idleBadge(what: String) {
                    assertEquals(what, source, act.badge.text.toString())
                    assertEquals(what, View.ACCESSIBILITY_LIVE_REGION_NONE, act.badge.accessibilityLiveRegion)
                    assertEquals(what, 2, act.badge.maxLines)
                }
                ins.runOnMainSync {
                    assertEquals(View.INVISIBLE, act.scanLine.visibility)
                    idleBadge("провал")
                }
                assertEquals("scale=$scale: конец", idle, geo())

                // Чип «новее» уже виден (дерево ждёт с прошлого скана): ход — всё равно одна строка.
                val h2 = scanned(dir)
                ins.runOnMainSync { Holder.offer(h2, Kind.SCAN, dir.path, false); act.refreshPending() }
                val chipIdle = geo()
                ins.runOnMainSync { assertEquals(View.VISIBLE, act.newer.visibility) }
                state(ScanState.RUNNING, 1_234_567)
                assertEquals("scale=$scale: идёт при чипе", chipIdle, geo())
                ins.runOnMainSync {
                    assertEquals(ScanProgress.badge(act.tx, ScanState.RUNNING, 1_234_567), act.badge.text.toString())
                    assertEquals("scale=$scale при чипе: ${act.badge.text}", 1, act.badge.lineCount)
                    assertEquals(1, act.badge.maxLines)
                }
                state(ScanState.QUEUED)
                ins.runOnMainSync { assertEquals("scale=$scale при чипе: ждёт", 1, act.badge.lineCount) }
                // Не удался при старом чипе: решает итог ЭТОГО скана, не hasNewer() — скрыть сразу.
                state(ScanState.RUNNING, 0)
                ins.runOnMainSync { BgScan.endOverride = ScanEnd.FAILED }
                state(null)
                ins.runOnMainSync {
                    assertEquals(View.INVISIBLE, act.scanLine.visibility)
                    assertNotEquals(1f, act.scanLine.fraction)
                    assertEquals(View.VISIBLE, act.newer.visibility)
                    idleBadge("провал при чипе")
                }
                // Удался, дерево ждёт тапа по чипу: 100%, затем скрыта; плашка молча — вид дерева.
                state(ScanState.RUNNING, 0)
                ins.runOnMainSync { BgScan.endOverride = ScanEnd.OK }
                state(null)
                ins.runOnMainSync {
                    assertEquals(1f, act.scanLine.fraction!!, 0f)
                    assertEquals(View.VISIBLE, act.newer.visibility)
                    idleBadge("удался")
                }
                assertTrue(waitFor(2000) { act.scanLine.visibility == View.INVISIBLE })
                assertEquals("scale=$scale: конец при чипе", chipIdle, geo())
            } finally {
                ins.runOnMainSync {
                    BgScan.stateOverride = null; BgScan.endOverride = null; BgScan.p[1] = 0
                    Holder.dropPending(); act.finish()
                }
                Lang.fontScale = null
            }
        }
        forgetCache(dir)
        dir.deleteRecursively()
    }

    /**
     * Ждущее дерево: offer не трогает экран, чип «новее · обновить» виден; тап подставляет его и
     * открывает тот же путь по именам; пропавший путь — ближайший существующий предок.
     */
    @Test fun pendingChipPromotesAndKeepsPath() {
        val ctx = ins.targetContext
        val dir = tmpDir("pend")
        File(dir, "sub/deep").mkdirs()
        File(dir, "sub/deep/a.bin").writeBytes(ByteArray(20_000))
        File(dir, "z.bin").writeBytes(ByteArray(10))
        scan(dir)
        val act = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        val row = Row()
        fun name(i: Int): String {
            var s = ""
            ins.runOnMainSync { row.reset(); act.list.source!!.bind(i, row); s = row.name }
            return s
        }
        fun count(): Int { var c = 0; ins.runOnMainSync { c = act.list.source!!.count }; return c }
        fun path(): String { var s = ""; ins.runOnMainSync { s = Native.str(Native.path(Holder.h, act.node)) }; return s }
        try {
            assertEquals("sub/", name(0))
            ins.runOnMainSync { act.list.source!!.click(0) }
            assertEquals("deep/", name(0))
            ins.runOnMainSync { act.list.source!!.click(0) }
            assertEquals(File(dir, "sub/deep").path, path())
            ins.runOnMainSync { assertEquals(View.GONE, act.newer.visibility) }

            File(dir, "sub/deep/b.bin").writeBytes(ByteArray(10))
            val h2 = scanned(dir)
            var h1 = 0L
            ins.runOnMainSync {
                h1 = Holder.h
                Holder.offer(h2, Kind.SCAN, dir.path, false, 0L, 4200L)   // ms — метка нового дерева в плашке
                act.refreshPending()
            }
            ins.runOnMainSync {
                assertEquals(h1, Holder.h)               // offer не подставил дерево
                assertEquals(View.VISIBLE, act.newer.visibility)
                act.newer.performClick()
            }
            ins.runOnMainSync {
                assertEquals(h2, Holder.h)
                assertEquals(0L, Holder.pending)
                assertEquals(View.GONE, act.newer.visibility)
                assertEquals(Badge.text(act.tx, Kind.SCAN, 0L, 4200L, false), act.badge.text.toString())
            }
            assertEquals(File(dir, "sub/deep").path, path())
            assertEquals(2, count())

            // путь пропал — ближайший предок
            File(dir, "sub/deep").deleteRecursively()
            val h3 = scanned(dir)
            ins.runOnMainSync { Holder.offer(h3, Kind.SCAN, dir.path, false); act.refreshPending() }
            ins.runOnMainSync { act.newer.performClick() }
            ins.runOnMainSync { assertEquals(h3, Holder.h) }
            assertEquals(File(dir, "sub").path, path())
            assertEquals(0, count())
        } finally {
            ins.runOnMainSync { Holder.dropPending(); act.finish() }
            dir.deleteRecursively()
        }
    }

    /**
     * Кэш + ждущее более новое дерево: долгий тап сразу подставляет новое (без скана) и открывает
     * лист того же файла уже в нём; файла в новом дереве нет — листа нет, подвал «“X” уже нет на диске».
     */
    @Test fun newerLongPressPromotesThenSheet() {
        val ctx = ins.targetContext
        val dir = tmpDir("pendc")
        File(dir, "f.txt").writeBytes(ByteArray(10))
        File(dir, "g.txt").writeBytes(ByteArray(20))
        val h = scanned(dir)
        val h2 = scanned(dir)
        ins.runOnMainSync {
            Holder.set(h, Kind.CACHE, dir.path, false)
            Holder.offer(h2, Kind.SCAN, dir.path, false)
        }
        val act = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        val row = Row()
        fun index(name: String): Int {
            var k = -1
            ins.runOnMainSync {
                val src = act.list.source!!
                k = (0 until src.count).first { row.reset(); src.bind(it, row); row.name == name }
            }
            return k
        }
        try {
            ins.runOnMainSync { assertEquals(View.VISIBLE, act.newer.visibility) }
            val fi = index("f.txt")
            ins.runOnMainSync { act.askDelete(fi) }
            assertTrue(waitFor { act.sheet?.dialog?.isShowing == true })
            ins.runOnMainSync {
                assertEquals(h2, Holder.h)                 // подставлено мгновенно
                assertEquals(Kind.SCAN, Holder.kind)
                assertEquals(View.GONE, act.newer.visibility)
                val s = act.sheet!!
                assertEquals(File(dir, "f.txt").path, s.p.path)
                assertNotNull(s.deleteButton)
                s.dismiss()
            }

            // Файл пропал с диска: новое дерево его не знает — листа нет, подвал говорит об этом.
            File(dir, "g.txt").delete()
            val h3 = scanned(dir)
            ins.runOnMainSync { Holder.offer(h3, Kind.SCAN, dir.path, false); act.refreshPending() }
            ins.runOnMainSync { assertEquals(h2, Holder.h) }   // лист закрыт, но флага нет — ждёт чип
            val gi = index("g.txt")
            var before: DeleteSheet? = null
            ins.runOnMainSync { before = act.sheet; act.askDelete(gi) }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                assertEquals(h3, Holder.h)
                assertTrue(act.sheet === before && act.sheet?.dialog?.isShowing != true)
                assertEquals(DeleteProgress.gone(act.tx, "g.txt"), act.footerText.toString())
            }
            assertTrue(File(dir, "f.txt").exists())
        } finally {
            ins.runOnMainSync { Holder.dropPending(); act.finish() }
            dir.deleteRecursively()
        }
    }

    /** Кэш, который записал фоновый скан фикстуры: файл и запись «caches». */
    private fun forgetCache(dir: File) {
        val ctx = ins.targetContext
        Holder.io.submit {}.get()   // saveCache стоит на io
        Holder.cacheFile(ctx, dir.path, false).delete()
        Baseline.files(ctx, dir.path, false).forget()   // точка отсчёта тестового ключа
        ctx.getSharedPreferences(Scans.PREFS, android.content.Context.MODE_PRIVATE).edit()
            .remove(Holder.cacheFile(ctx, dir.path, false).name).commit()
    }

    private fun index(act: BrowserActivity, name: String): Int {
        var k = -1
        ins.runOnMainSync {
            val src = act.list.source!!
            val row = Row()
            k = (0 until src.count).firstOrNull { row.reset(); src.bind(it, row); row.name == name } ?: -1
        }
        return k
    }

    /** Узел [name] среди детей текущего уровня: items (с самим узлом) или -1. */
    private fun items(act: BrowserActivity, name: String): Long {
        val k = index(act, name)
        if (k < 0) return -1
        var v = -1L
        ins.runOnMainSync {
            val c = IntArray(Native.childCount(Holder.h, act.node))
            val n = Native.children(Holder.h, act.node, SORT_SIZE, false, c)
            for (j in 0 until n) if (Native.str(Native.name(Holder.h, c[j])) == name.trimEnd('/'))
                v = LongArray(4).also { Native.nodeInfo(Holder.h, intArrayOf(c[j]), 1, it) }[2]
        }
        return v
    }

    /**
     * Кэш: долгий тап по каталогу — листа сразу нет, в шапке «обновление · …»; экран сам
     * сканирует корень и открывает лист того же каталога со свежими числами.
     */
    @Test fun cacheDirLongPressRefreshesThenSheet() {
        val ctx = ins.targetContext
        val dir = tmpDir("cdir")
        File(dir, "sub").mkdirs()
        File(dir, "sub/big.bin").writeBytes(ByteArray(300_000))
        File(dir, "f.txt").writeBytes(ByteArray(10))
        val h = scanned(dir)
        ins.runOnMainSync { Holder.set(h, Kind.CACHE, dir.path, false) }
        File(dir, "sub/new.bin").writeBytes(ByteArray(100_000))   // диск изменился после «кэша»
        Perms.filesOverride = true
        val act = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        try {
            val i = index(act, "sub/")
            ins.runOnMainSync {
                act.askDelete(i)
                assertTrue(act.sheet?.dialog?.isShowing != true)
                // Ход обновления — в шапке: полоса и «обновление · …» (идёт или ждёт).
                assertEquals(View.VISIBLE, act.scanLine.visibility)
                assertTrue(act.badge.text.toString(), act.badge.text.startsWith(act.prefixOf(R.string.refresh_count)))
            }
            assertTrue(waitFor(30_000) { act.sheet?.dialog?.isShowing == true })
            ins.runOnMainSync {
                assertNotEquals(h, Holder.h)
                assertEquals(Kind.SCAN, Holder.kind)
                val s = act.sheet!!
                assertEquals(File(dir, "sub").path, s.p.path)
                assertEquals(3L, s.p.items)                  // sub + big.bin + new.bin — новые числа
                assertNotNull(s.deleteButton)
                s.dismiss()
            }
            assertTrue(File(dir, "sub/big.bin").exists())
            assertTrue(waitFor { !BgScan.active })
        } finally {
            Perms.filesOverride = null
            ins.runOnMainSync { act.finish() }
            forgetCache(dir)
            dir.deleteRecursively()
        }
    }

    /**
     * Удалено не всё (каталог без права записи внутри): никакого диалога; экран сам обновляет
     * дерево, остаётся на том же пути, подвал «освобождено … · остаток в списке».
     */
    @Test fun partialDeleteRefreshesAndKeepsPath() {
        val ctx = ins.targetContext
        val dir = tmpDir("part")
        val sub = File(dir, "a/sub").apply { mkdirs() }
        File(sub, "x.bin").writeBytes(ByteArray(50_000))
        val locked = File(sub, "locked").apply { mkdirs() }
        File(locked, "y.bin").writeBytes(ByteArray(20_000))
        assertTrue(locked.setWritable(false, false))
        scan(dir)
        Perms.filesOverride = true
        val act = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        try {
            ins.runOnMainSync { act.list.source!!.click(0) }       // a/
            var h0 = 0L
            ins.runOnMainSync { h0 = Holder.h }
            val r = act.deleteBlocking(index(act, "sub/"))
            assertNotEquals(0, r)
            assertTrue(waitFor(30_000) { Holder.h != h0 && act.footerText.startsWith(act.prefixOf(R.string.freed)) })
            ins.runOnMainSync {
                assertNull(act.lastAlert)                          // ни «остановлено», ни «частично»
                assertTrue(act.footerText.toString(), act.footerText.endsWith(act.suffixOf(R.string.freed_left)))
                assertEquals(File(dir, "a").path, Native.str(Native.path(Holder.h, act.node)))
            }
            assertFalse(File(sub, "x.bin").exists())
            assertTrue(File(locked, "y.bin").exists())
            assertEquals(3L, items(act, "sub/"))                    // sub + locked + y.bin — как на диске
            assertTrue(waitFor { !BgScan.active })
        } finally {
            Perms.filesOverride = null
            locked.setWritable(true, true)
            ins.runOnMainSync { act.finish() }
            forgetCache(dir)
            dir.deleteRecursively()
        }
    }

    /**
     * Каталог подменён после скана (увезён, на его месте новый с другим содержимым): ядро сверяет
     * st_dev/st_ino узла и отказывает (-ESTALE) — сообщение «изменилось после скана», новый каталог
     * и увезённый целы, узел ⚠, дерево само не обновляется. Всё — в своём mkdtemp под cacheDir.
     */
    @Test fun replacedDirIsNotDeleted() {
        val ctx = ins.targetContext
        val dir = tmpDir("chg")
        val victim = File(dir, "victim").apply { mkdirs() }
        File(victim, "orig.bin").writeBytes(ByteArray(50_000))
        File(dir, "keep.bin").writeBytes(ByteArray(10))
        scan(dir)
        // Песочница: только абсолютные пути строго внутри dir — проверено до подмены и удаления.
        val aside = File(dir, "victim.old")
        for (f in listOf(victim, aside)) assertTrue(f.path, dir.isAbsolute && f.path.startsWith(dir.path + "/"))
        assertTrue(victim.renameTo(aside))
        val fresh = File(dir, "victim").apply { assertTrue(mkdir()) }
        File(fresh, "new.bin").writeBytes(ByteArray(10))
        Perms.filesOverride = true
        val act = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        try {
            var h0 = 0L
            ins.runOnMainSync { h0 = Holder.h }
            val k = index(act, "victim/")
            assertTrue(k >= 0)
            assertEquals(-NativeErr.ESTALE, act.deleteBlocking(k))
            assertTrue(waitFor { !act.busy && act.lastAlert != null })
            ins.runOnMainSync {
                assertEquals(act.getString(R.string.delete_failed) to act.getString(R.string.delete_changed), act.lastAlert)
                assertEquals(h0, Holder.h)                                  // без автообновления
                val c = IntArray(Native.childCount(Holder.h, act.node))
                val n = Native.children(Holder.h, act.node, SORT_SIZE, false, c)
                val v = (0 until n).first { Native.str(Native.name(Holder.h, c[it])) == "victim" }
                val inf = LongArray(4).also { Native.nodeInfo(Holder.h, intArrayOf(c[v]), 1, it) }
                assertTrue(inf[3].toInt() and F_ERR != 0)                  // узел ⚠: дерево устарело
            }
            assertTrue(File(fresh, "new.bin").exists())
            assertTrue(File(aside, "orig.bin").exists())
            assertFalse(BgScan.active)
        } finally {
            Perms.filesOverride = null
            ins.runOnMainSync { act.finish() }
            forgetCache(dir)
            dir.deleteRecursively()
        }
    }

    /**
     * «Стоп» посреди удаления — без гонки: шаг перед ядром (шов bulk) сам удаляет K файлов,
     * сообщает add(K) и ждёт, пока тест нажмёт «Стоп»; ядро уже не вызывается, итог -EINTR при
     * done > 0. Никакого диалога; дерево обновляется само до настоящего остатка, путь сохраняется.
     */
    @Test fun stopMidDeleteRefreshesAndKeepsPath() {
        val ctx = ins.targetContext
        val dir = tmpDir("stop")
        val sub = File(dir, "a/sub").apply { mkdirs() }
        val files = (0 until 10).map { File(sub, "f$it.bin").apply { writeBytes(ByteArray(8192)) } }
        val k = 4
        scan(dir)
        Perms.filesOverride = true
        val act = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        val deletedK = CountDownLatch(1)
        val go = CountDownLatch(1)
        try {
            ins.runOnMainSync { act.list.source!!.click(0) }       // a/
            val i = index(act, "sub/")
            var r = Int.MIN_VALUE
            val deleter = Thread {
                r = act.deleteBlocking(i) { _, add ->
                    // На io, абсолютные пути внутри своего mkdtemp-каталога.
                    for (f in files.take(k)) check(f.absolutePath.startsWith(dir.absolutePath + "/") && f.delete())
                    add(k.toLong())
                    deletedK.countDown()
                    go.await(30, TimeUnit.SECONDS)
                }
            }.apply { start() }
            assertTrue("шаг не дошёл до K", deletedK.await(30, TimeUnit.SECONDS))
            ins.runOnMainSync {
                assertTrue(act.busy)
                assertEquals(k.toLong(), Holder.deleteProgress())
                act.stopDelete()
            }
            go.countDown()
            deleter.join(30_000)
            assertFalse(deleter.isAlive)
            assertEquals(-DeleteProgress.EINTR, r)
            assertTrue(waitFor(30_000) { act.footerText.startsWith(act.prefixOf(R.string.freed)) })
            ins.runOnMainSync {
                assertNull(act.lastAlert)
                assertTrue(act.footerText.toString(), act.footerText.endsWith(act.suffixOf(R.string.freed_left)))
                assertEquals(File(dir, "a").path, Native.str(Native.path(Holder.h, act.node)))
            }
            assertEquals(10 - k, sub.listFiles()!!.size)
            assertEquals(1L + 10 - k, items(act, "sub/"))          // остаток в дереве = остаток на диске
            assertTrue(waitFor { !BgScan.active })
        } finally {
            go.countDown()
            Perms.filesOverride = null
            ins.runOnMainSync { act.finish() }
            forgetCache(dir)
            dir.deleteRecursively()
        }
    }
}
