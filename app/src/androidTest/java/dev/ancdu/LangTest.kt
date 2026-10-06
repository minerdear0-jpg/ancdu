package dev.ancdu

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * Язык приложения: EN и RU на главном экране и в браузере, plurals RU, переключатель в шапке
 * сохраняется после перезапуска экрана, смена языка сохраняет дерево и путь браузера.
 */
@RunWith(AndroidJUnit4::class)
class LangTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context get() = ins.targetContext
    private val ui get() = ctx.getSharedPreferences(LangPrefs.PREFS, Context.MODE_PRIVATE)
    private var prevStored: String? = null
    /** Язык приложения в системе до теста (API 33+): тест его возвращает, а не сбрасывает. */
    private var prevApp: String = ""

    @Before fun setUp() {
        prevStored = ui.getString(LangPrefs.KEY, null)
        if (Build.VERSION.SDK_INT >= 33)
            prevApp = ctx.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        BgScan.auto = false
        Perms.filesOverride = true
        ins.runOnMainSync { Holder.clear(); Holder.dropPending() }
    }

    @After fun tearDown() {
        setLang(prevApp)
        if (prevStored == null) ui.edit().remove(LangPrefs.KEY).commit()
        else ui.edit().putString(LangPrefs.KEY, prevStored).commit()
        BgScan.auto = true
        Perms.filesOverride = null
        ins.runOnMainSync { Holder.clear() }
    }

    /** Язык приложения, как это делает [Lang.set], но без экрана. «» — как в системе. */
    private fun setLang(tag: String) {
        ui.edit().putString(LangPrefs.KEY, tag).commit()
        if (Build.VERSION.SDK_INT >= 33) ins.runOnMainSync {
            ctx.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        }
        ins.waitForIdleSync()
    }

    private fun waitFor(ms: Long = 10_000, ok: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            var r = false
            ins.runOnMainSync { r = ok() }
            if (r) return true
            Thread.sleep(50)
        }
        return false
    }

    private inline fun <reified A : Activity> resumed(): A? =
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<A>().firstOrNull()

    private fun main(): MainActivity {
        val a = ins.startActivitySync(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        ins.waitForIdleSync()
        return a
    }

    private fun fixture(): File = File(ctx.cacheDir, "lang").apply {
        deleteRecursively()
        File(this, "sub/deep").mkdirs()
        File(this, "sub/a.bin").writeBytes(ByteArray(5000))
        File(this, "z.bin").writeBytes(ByteArray(10))
    }

    private fun browse(dir: File): BrowserActivity {
        val h = Native.scanStart(dir.path, true, 2, IntArray(1))
        val p = LongArray(6)
        val deadline = System.currentTimeMillis() + 10_000
        while (Native.progress(h, p).let { p[0] == ST_RUNNING.toLong() } && System.currentTimeMillis() < deadline)
            Thread.sleep(25)
        assertEquals(ST_DONE.toLong(), p[0])
        ins.runOnMainSync { Holder.set(h, Kind.SCAN, dir.path, false) }
        val a = ins.startActivitySync(Intent(ctx, BrowserActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        return a
    }

    private fun chips(b: BrowserActivity): List<String> =
        (0 until b.chips.childCount).map { (b.chips.getChildAt(it) as android.widget.TextView).text.toString() }

    private fun check(lang: String, main: List<String>, chips: List<String>, unit: String) {
        setLang(lang)
        val a = main()
        val dir = fixture()
        try {
            ins.runOnMainSync {
                assertEquals(lang, a.resources.configuration.locales[0].language)
                assertEquals(main[0], a.storage.storeTitle.text.toString())
                assertEquals(main[1], a.langButton.text.toString())
            }
            ins.runOnMainSync { a.finish() }
            val b = browse(dir)
            ins.runOnMainSync {
                assertEquals(chips, chips(b))
                assertEquals(main[2], b.badge.text.toString())
                // строка каталога: размер в единицах языка, «каталог»/«folder» в описании
                val row = Row().also { b.list.source!!.bind(0, it) }
                assertEquals("sub/", row.name)
                assertTrue(row.size, row.size.endsWith(Fmt.NBSP + unit))
                assertTrue(row.desc, row.desc.endsWith(", " + main[3]))
                b.finish()
            }
        } finally {
            ins.runOnMainSync { Holder.clear() }
            dir.deleteRecursively()
        }
    }

    @Test fun englishScreens() =
        check("en", listOf("Shared storage", "EN ▾", "scan", "folder"), listOf("size", "name", "on disk"), "KiB")

    @Test fun russianScreens() =
        check("ru", listOf("Общее хранилище", "RU ▾", "скан", "каталог"), listOf("размер", "имя", "на диске"), "КиБ")

    /** RU: одна/несколько/много для 1, 2, 5, 21, 761 (ICU устройства, не JVM-правила). */
    @Test fun russianPlurals() {
        val r = Lang.withLocale(ctx, Locale.forLanguageTag("ru")).resources
        val files = listOf(1 to "1 файл", 2 to "2 файла", 5 to "5 файлов", 21 to "21 файл", 761 to "761 файл")
        for ((n, w) in files) assertEquals(w, r.getQuantityString(R.plurals.files, n, n.toString()))
        val errs = listOf(1 to "1 ошибка", 2 to "2 ошибки", 5 to "5 ошибок", 21 to "21 ошибка", 761 to "761 ошибка")
        for ((n, w) in errs) assertEquals(w, r.getQuantityString(R.plurals.errors, n, n.toString()))
        val items = listOf(1 to "1 элемент", 2 to "2 элемента", 5 to "5 элементов", 21 to "21 элемент", 761 to "761 элемент")
        for ((n, w) in items) assertEquals(w, r.getQuantityString(R.plurals.items_long, n, n.toString()))
        val en = Lang.withLocale(ctx, Locale.ENGLISH).resources
        assertEquals("1 file", en.getQuantityString(R.plurals.files, 1, "1"))
        assertEquals("21 files", en.getQuantityString(R.plurals.files, 21, "21"))
        // Txt устройства: число в формате языка (NBSP в группах), форма — по числу
        assertEquals("63${Fmt.NBSP}761 файл", ResTxt(r).q(R.plurals.files, 63_761, Fmt.count(63_761, Locale.forLanguageTag("ru"))))
    }

    /** Кнопка «EN ▾» → «Русский»: экран пересоздан по-русски; после перезапуска — всё ещё русский. */
    @Test fun switchPersistsAcrossRestart() {
        setLang("en")
        val a = main()
        ins.runOnMainSync { a.langButton.performClick() }
        ins.waitForIdleSync()
        ins.runOnMainSync {
            val d = a.langDialog!!
            assertTrue(d.isShowing)
            val list = d.listView
            assertEquals(3, list.count)
            assertEquals("Русский", list.adapter.getItem(2).toString())
            list.performItemClick(list.getChildAt(2), 2, list.adapter.getItemId(2))
        }
        assertTrue("экран не пересоздан по-русски", waitFor(10_000) {
            val m = resumed<MainActivity>()
            m != null && m !== a && m.storage.storeTitle.text.toString() == "Общее хранилище"
        })
        assertEquals("ru", ui.getString(LangPrefs.KEY, null))
        assertEquals(LangChoice.RU, Lang.choice(ctx))
        ins.runOnMainSync { resumed<MainActivity>()!!.finish() }
        ins.waitForIdleSync()
        val again = main()
        ins.runOnMainSync {
            assertEquals("Общее хранилище", again.storage.storeTitle.text.toString())
            assertEquals("RU ▾", again.langButton.text.toString())
            again.finish()
        }
    }

    /** Смена языка пересоздаёт браузер: то же дерево, та же папка и сортировка — уже по-русски. */
    @Test fun switchKeepsTreeAndPath() {
        setLang("en")
        val dir = fixture()
        val b = browse(dir)
        try {
            var h = 0L
            var node = 0
            ins.runOnMainSync {
                b.list.source!!.click(0)          // sub/
                b.setSort(SORT_NAME)
                h = Holder.h; node = b.node
                assertEquals("sub", b.title.text.toString())
            }
            setLang("ru")
            assertTrue("браузер не пересоздан", waitFor(10_000) {
                val n = resumed<BrowserActivity>()
                n != null && n !== b && chips(n)[0] == "размер"
            })
            ins.runOnMainSync {
                val n = resumed<BrowserActivity>()!!
                assertNotSame(b, n)
                assertEquals(h, Holder.h)
                assertEquals(node, n.node)
                assertEquals("sub", n.title.text.toString())
                assertEquals(listOf("размер", "имя", "на диске"), chips(n))
                val row = Row().also { n.list.source!!.bind(0, it) }
                assertEquals("a.bin", row.name)       // по имени: a.bin < deep/
                n.finish()
            }
        } finally {
            ins.runOnMainSync { Holder.clear() }
            dir.deleteRecursively()
        }
    }

    /** API 30–32: обёртка контекста даёт ресурсы выбранного языка (на 33+ её не ставят). */
    @Test fun wrapperGivesChosenLanguage() {
        val ru = Lang.withLocale(ctx, Locale.forLanguageTag("ru"))
        assertEquals("Общее хранилище", ru.getString(R.string.shared_title))
        assertEquals("81,6${Fmt.NBSP}ГиБ", Fmt.size((81.6 * (1L shl 30)).toLong(), ru.tx))
        val en = Lang.withLocale(ctx, Locale.ENGLISH)
        assertEquals("81.6${Fmt.NBSP}GiB", Fmt.size((81.6 * (1L shl 30)).toLong(), en.tx))
        // Немецкая система: ресурсы английские — и числа, и месяцы английские
        val de = Lang.withLocale(ctx, Locale.GERMAN)
        assertEquals("81.6${Fmt.NBSP}GiB", Fmt.size((81.6 * (1L shl 30)).toLong(), de.tx))
        assertEquals("Oct 5", Freshness.date(de.tx, R.string.fmt_day, 1_759_700_000_000L, java.util.TimeZone.getTimeZone("UTC")))
        assertNotNull(LangPrefs.wrapLocale(30, "ru"))
    }
}
