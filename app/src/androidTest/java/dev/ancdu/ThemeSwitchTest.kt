package dev.ancdu

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.ViewGroup
import android.view.WindowInsetsController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Тема: принудительно светлая — главный экран и браузер на светлом BG, светлые полосы системы,
 * краски списка из LIGHT; «···» → «Тема» → «Тёмная» пересоздаёт экран; смена темы в браузере
 * сохраняет дерево и путь.
 */
@RunWith(AndroidJUnit4::class)
class ThemeSwitchTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context get() = ins.targetContext
    private val ui get() = ctx.getSharedPreferences(LangPrefs.PREFS, Context.MODE_PRIVATE)
    private var prevTheme: String? = null
    private var prevLang: String? = null

    @Before fun setUp() {
        prevTheme = ui.getString(ThemePrefs.KEY, null)
        prevLang = ui.getString(LangPrefs.KEY, null)
        BgScan.auto = false
        Perms.filesOverride = true
        ins.runOnMainSync { Holder.clear(); Holder.dropPending() }
    }

    @After fun tearDown() {
        setTheme(ThemeChoice.of(prevTheme))
        if (prevTheme == null) ui.edit().remove(ThemePrefs.KEY).commit()
        BgScan.auto = true
        Perms.filesOverride = null
        ins.runOnMainSync { Holder.clear() }
    }

    /** Тема приложения, как это делает [Theme.set], но без экрана. */
    private fun setTheme(c: ThemeChoice) {
        ui.edit().putString(ThemePrefs.KEY, ThemePrefs.store(c)).commit()
        if (Build.VERSION.SDK_INT >= 31) ins.runOnMainSync {
            ctx.getSystemService(UiModeManager::class.java).setApplicationNightMode(ThemePrefs.appNightMode(c))
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

    private fun fixture(): File = File(ctx.cacheDir, "theme").apply {
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

    /** Цвет фона корневого view экрана (setContentView). */
    private fun rootBg(a: Activity): Int =
        ((a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).background) as ColorDrawable).color

    /** Строка с именем [name] в текущей папке браузера. */
    private fun rowNamed(b: BrowserActivity, name: String): Row {
        val src = b.list.source!!
        return (0 until src.count).map { i -> Row().also { src.bind(i, it) } }.first { it.name == name }
    }

    private fun lightBars(a: Activity): Boolean {
        val m = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        return a.window.insetsController!!.systemBarsAppearance and m == m
    }

    @Test fun forcedLightRendersLight() {
        setTheme(ThemeChoice.LIGHT)
        val a = main()
        val dir = fixture()
        try {
            ins.runOnMainSync {
                assertTrue(!a.resources.configuration.isNightModeActive)
                assertEquals(Palette.LIGHT, C.p)
                assertEquals(Palette.LIGHT.bg, rootBg(a))
                assertTrue("строка состояния не светлая", lightBars(a))
                assertEquals(Palette.LIGHT.bg, a.window.statusBarColor)
                a.finish()
            }
            val b = browse(dir)
            ins.runOnMainSync {
                assertEquals(Palette.LIGHT.bg, rootBg(b))
                assertTrue(lightBars(b))
                val l = Palette.LIGHT
                assertEquals(listOf(l.text, l.muted, l.ink), b.list.paintColors.toList())
                b.list.source!!.click(0)          // sub/
                val file = rowNamed(b, "a.bin")
                assertEquals(l.blueHi, file.nameColor)
                assertEquals(l.blue, file.barColor)
                assertEquals(l.text, b.title.currentTextColor)
                b.finish()
            }
        } finally {
            ins.runOnMainSync { Holder.clear() }
            dir.deleteRecursively()
        }
    }

    /** «···» → «Тема: Светлая» → «Тёмная»: экран пересоздан тёмным, выбор в prefs. */
    @Test fun menuSwitchLightToDark() {
        val lm = if (Build.VERSION.SDK_INT >= 33) ctx.getSystemService(android.app.LocaleManager::class.java) else null
        val prevApp = lm?.applicationLocales
        ui.edit().putString(LangPrefs.KEY, "en").commit()
        try {
            if (lm != null) ins.runOnMainSync { lm.applicationLocales = android.os.LocaleList.forLanguageTags("en") }
            setTheme(ThemeChoice.LIGHT)
            val a = main()
            ins.runOnMainSync { assertEquals(Palette.LIGHT.bg, rootBg(a)); a.menuButton.performClick() }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                val m = a.menu!!
                assertEquals(5, m.rows.size)
                assertEquals("Theme: Light", m.rows[2].text.toString())
                m.rows[2].performClick()
                assertTrue(!m.dialog.isShowing)
            }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                val d = a.themeDialog!!
                assertTrue(d.isShowing)
                val list = d.listView
                assertEquals(listOf("System", "Dark", "Light"), (0 until list.count).map { list.adapter.getItem(it).toString() })
                assertEquals(2, list.checkedItemPosition)
                list.performItemClick(list.getChildAt(1), 1, list.adapter.getItemId(1))
            }
            assertTrue("экран не пересоздан тёмным", waitFor(10_000) {
                val n = resumed<MainActivity>()
                n != null && n !== a && n.resources.configuration.isNightModeActive && rootBg(n) == Palette.DARK.bg
            })
            assertEquals("dark", ui.getString(ThemePrefs.KEY, null))
            assertEquals(ThemeChoice.DARK, Theme.choice(ctx))
            ins.runOnMainSync {
                val n = resumed<MainActivity>()!!
                assertEquals(Palette.DARK, C.p)
                assertTrue(!lightBars(n))
                n.finish()
            }
        } finally {
            if (prevLang == null) ui.edit().remove(LangPrefs.KEY).commit() else ui.edit().putString(LangPrefs.KEY, prevLang).commit()
            if (lm != null && prevApp != null) ins.runOnMainSync { lm.applicationLocales = prevApp }
            ins.waitForIdleSync()
        }
    }

    /** Смена темы в браузере: то же дерево, та же папка и сортировка — уже в тёмной палитре. */
    @Test fun switchKeepsTreeAndPath() {
        setTheme(ThemeChoice.LIGHT)
        val dir = fixture()
        val b = browse(dir)
        try {
            var h = 0L
            var node = 0
            ins.runOnMainSync {
                assertEquals(Palette.LIGHT.bg, rootBg(b))
                b.list.source!!.click(0)          // sub/
                b.setSort(SORT_NAME)
                h = Holder.h; node = b.node
                assertEquals("sub", b.title.text.toString())
                Theme.set(b, ThemeChoice.DARK)
            }
            assertTrue("браузер не пересоздан тёмным", waitFor(10_000) {
                val n = resumed<BrowserActivity>()
                n != null && n !== b && rootBg(n) == Palette.DARK.bg
            })
            ins.runOnMainSync {
                val n = resumed<BrowserActivity>()!!
                assertNotSame(b, n)
                assertEquals(h, Holder.h)
                assertEquals(node, n.node)
                assertEquals("sub", n.title.text.toString())
                val row = Row().also { n.list.source!!.bind(0, it) }
                assertEquals("a.bin", row.name)       // по имени: a.bin < deep/
                assertEquals(Palette.DARK.blueHi, row.nameColor)
                assertEquals(Palette.DARK.text, n.list.paintColors[0])
                n.finish()
            }
        } finally {
            ins.runOnMainSync { Holder.clear() }
            dir.deleteRecursively()
        }
    }
}
