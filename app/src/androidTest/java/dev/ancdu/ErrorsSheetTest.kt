package dev.ancdu

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.LocaleList
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Ошибки скана: ссылка подвала, лист с причинами, переход к узлу. Фикстура — свежий mkdtemp под
 * cacheDir: a/locked с правами 000 (возвращаются в finally и в tearDown) и b/late, помеченный F_ERR
 * хуком Native.markErr после скана (папка читается — причина «данные неполные»). Ничего не удаляется
 * приложением; фикстуру убирает тест.
 */
@RunWith(AndroidJUnit4::class)
class ErrorsSheetTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context get() = ins.targetContext
    private val ui get() = ctx.getSharedPreferences(LangPrefs.PREFS, Context.MODE_PRIVATE)
    private var act: BrowserActivity? = null
    private var dir: File? = null
    private var locked: File? = null
    private var prevStored: String? = null
    private var prevApp = ""

    @Before fun setUp() {
        prevStored = ui.getString(LangPrefs.KEY, null)
        if (Build.VERSION.SDK_INT >= 33)
            prevApp = ctx.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        BgScan.auto = false
        ins.runOnMainSync { Holder.clear(); Holder.dropPending() }
    }

    @After fun tearDown() {
        ins.runOnMainSync {
            act?.errorsSheet?.dismiss()
            act?.finish()
            Holder.clear()
        }
        Lang.fontScale = null
        restore()
        setLang(prevApp)
        if (prevStored == null) ui.edit().remove(LangPrefs.KEY).commit()
        else ui.edit().putString(LangPrefs.KEY, prevStored).commit()
        BgScan.auto = true
        dir?.let { d -> if (d.path.startsWith(ctx.cacheDir.path + "/")) d.deleteRecursively() }
    }

    private fun setLang(tag: String) {
        ui.edit().putString(LangPrefs.KEY, tag).commit()
        if (Build.VERSION.SDK_INT >= 33) ins.runOnMainSync {
            ctx.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        }
        ins.waitForIdleSync()
    }

    /** Права a/locked назад (идемпотентно): фикстура всегда удаляема. */
    private fun restore() { locked?.let { runCatching { Os.chmod(it.path, 0x1ed) } } }   // 0755

    private fun fixture(): File =
        java.nio.file.Files.createTempDirectory(ctx.cacheDir.toPath(), "errs").toFile().also {
            assertTrue(it.isAbsolute && it.path.startsWith(ctx.cacheDir.path + "/"))
            dir = it
            // 30 файлов крупнее каталога: locked/ в конце списка a/ — его строку надо прокрутить в вид.
            File(it, "a").mkdirs()
            for (k in 0 until 30) File(it, "a/f%02d".format(k)).writeBytes(ByteArray(9000))
            File(it, "a/locked").mkdirs()
            File(it, "a/locked/hidden").writeBytes(ByteArray(100))
            File(it, "b/late").mkdirs()
            File(it, "b/late/x").writeBytes(ByteArray(100))
            locked = File(it, "a/locked")
            Os.chmod(locked!!.path, 0)
        }

    private fun waitFor(ms: Long = 5_000, ok: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            var r = false
            ins.runOnMainSync { r = ok() }
            if (r) return true
            Thread.sleep(25)
        }
        return false
    }

    private fun child(h: Long, nd: Int, name: String): Int {
        val c = IntArray(Native.childCount(h, nd))
        val k = Native.children(h, nd, SORT_NAME, false, c)
        return (0 until k).map { c[it] }.first { Native.str(Native.name(h, it)) == name }
    }

    /** Скан фикстуры, b/late помечен F_ERR, дерево в Holder, браузер на корне. */
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
        assertEquals(1L, p[3])   // a/locked не открылся
        assertEquals(0, Native.markErr(h, child(h, child(h, 0, "b"), "late")))
        ins.runOnMainSync { Holder.set(h, Kind.SCAN, root.path, false) }
        val a = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        act = a
        return a
    }

    private fun openSheet(a: BrowserActivity): ErrorsSheet {
        assertTrue("footer link", waitFor { a.errLink.isShown && a.errCount == 2 })
        // The amber errors link is on screen: the «новее» chip is outlined (<= 2 accents at once).
        ins.runOnMainSync { assertTrue(a.head.newerOutlined) }
        ins.runOnMainSync { a.errLink.performClick() }
        assertTrue("sheet", waitFor { a.errorsSheet?.dialog?.isShowing == true })
        ins.waitForIdleSync()
        return a.errorsSheet!!
    }

    @Test fun footerLinkSheetAndJump() {
        val root = fixture()
        try {
            val a = browse(root)
            val t = a.tx
            assertTrue(waitFor { a.errCount == 2 })
            ins.runOnMainSync {
                assertEquals(ScanErrors.link(t, 2), a.errLink.text.toString())
                assertTrue(a.errLink.text.endsWith("›"))
                assertEquals(ScanErrors.linkDesc(t, 2), a.errLink.contentDescription.toString())
                assertTrue(a.errLink.isClickable)
                // остальной подвал — простой текст, без «⚠»
                assertFalse(a.footerText.toString(), a.footerText.contains("⚠"))
            }
            ins.waitForIdleSync()
            ins.runOnMainSync { assertTrue("${a.errLink.height}", a.errLink.height >= a.dp(44)) }
            val s = openSheet(a)
            ins.runOnMainSync {
                assertEquals(2, s.list.total)
                assertEquals(listOf("a/locked", "b/late"), s.list.rows.map { it.rel })
                assertEquals(t.s(R.string.err_no_access), s.reasons[0].text.toString())
                assertEquals(t.s(R.string.err_incomplete), s.reasons[1].text.toString())
                // не общее хранилище: заметки об Android/data и кнопки root нет; все строки показаны
                assertNull(s.rootButton)
                assertNull(s.moreText)
                for (r in s.rows) assertTrue("${r.height}", r.height >= a.dp(48))
                assertEquals("a/locked, " + t.s(R.string.err_no_access), s.rows[0].contentDescription.toString())
                s.rows[0].performClick()
            }
            assertTrue("sheet closed", waitFor { a.errorsSheet?.dialog?.isShowing != true })
            ins.waitForIdleSync()
            ins.runOnMainSync {
                assertEquals(root.path + "/a", a.currentPath)
                val src = a.list.source!!
                val i = (0 until src.count).first { Row().also { r -> src.bind(it, r) }.name == "locked/" }
                assertTrue("locked/ is last of 31: $i", i == 30)
                val rh = a.list.rowHeight
                assertTrue("row $i revealed: scroll=${a.list.scroll} h=${a.list.height}",
                    i * rh >= a.list.scroll && (i + 1) * rh <= a.list.scroll + a.list.height)
            }
        } finally {
            restore()
        }
    }

    @Test fun russianTexts() {
        setLang("ru")
        val root = fixture()
        try {
            val a = browse(root)
            assertTrue(waitFor { a.errCount == 2 })
            ins.runOnMainSync {
                assertEquals("⚠ 2 ошибки ›", a.errLink.text.toString())
                assertEquals("Ошибки сканирования: 2. Открыть список", a.errLink.contentDescription.toString())
            }
            val s = openSheet(a)
            ins.runOnMainSync {
                assertEquals("нет доступа", s.reasons[0].text.toString())
                assertEquals("данные неполные — удаление прервано или папка менялась во время скана", s.reasons[1].text.toString())
            }
        } finally {
            restore()
        }
    }

    /**
     * Подвал не переносится и не прыгает, когда появляется ссылка: длинный текст подвала — одна строка
     * с «…», высота полосы подвала с ссылкой и без неё одна и та же (шрифт 100% и 200%).
     */
    @Test fun footerHeightStableWithLink() {
        val root = fixture()
        try {
            for (scale in listOf(1f, 2f)) {
                Lang.fontScale = scale
                val a = browse(root)
                assertTrue("link at $scale", waitFor { a.errLink.isShown && a.errCount == 2 })
                ins.runOnMainSync { a.footer.text = "long footer text ".repeat(20) }
                ins.waitForIdleSync()
                var withLink = 0
                ins.runOnMainSync {
                    withLink = a.footerBar.height
                    assertEquals("one line at $scale", 1, a.footer.lineCount)
                    assertTrue(a.footerBar.height >= a.dp(44))
                    a.errLink.visibility = android.view.View.GONE
                }
                ins.waitForIdleSync()
                var without = 0
                ins.runOnMainSync {
                    without = a.footerBar.height
                    a.errLink.visibility = android.view.View.VISIBLE
                }
                ins.waitForIdleSync()
                ins.runOnMainSync {
                    assertEquals("scale $scale: with link vs without", without, withLink)
                    assertEquals("scale $scale: link back", withLink, a.footerBar.height)
                    assertEquals(1, a.footer.lineCount)
                    a.finish()
                }
                ins.waitForIdleSync()
                act = null
                ins.runOnMainSync { Holder.clear() }
            }
        } finally {
            restore()
        }
    }

    @Test fun largeFont() {
        Lang.fontScale = 2f
        val root = fixture()
        try {
            val a = browse(root)
            val s = openSheet(a)
            ins.runOnMainSync {
                assertTrue(a.errLink.height >= a.dp(44))
                assertTrue(a.errLink.isShown)
                for (r in s.rows) assertTrue("${r.height}", r.height >= a.dp(48))
                // путь не длиннее двух строк, причина видна целиком
                for (v in s.reasons) assertTrue(v.isShown)
            }
        } finally {
            restore()
        }
    }
}
