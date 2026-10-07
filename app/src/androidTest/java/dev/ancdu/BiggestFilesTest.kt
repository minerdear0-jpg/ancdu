package dev.ancdu

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * «Самые крупные файлы» на главном экране: дерево свежего каталога (mkdtemp под cacheDir),
 * подставленное как дерево общего хранилища (тот же приём, что MainTest.cardTimeComesFromTheShownTree).
 * Тап по строке — браузер в папке файла с открытой карточкой. Ничего не удаляется приложением;
 * фикстуру убирает тест, запись «caches» общего хранилища возвращается как была.
 */
@RunWith(AndroidJUnit4::class)
class BiggestFilesTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context get() = ins.targetContext
    private val prefs get() = ctx.getSharedPreferences(Scans.PREFS, Context.MODE_PRIVATE)
    private val storageKey get() = Holder.cacheFile(ctx, Scans.STORAGE, false).name
    private var savedMeta: String? = null
    private var dir: File? = null
    private var main: MainActivity? = null
    private var browser: BrowserActivity? = null

    @Before fun setUp() {
        savedMeta = prefs.getString(storageKey, null)
        BgScan.auto = false
        Perms.filesOverride = true
        ins.runOnMainSync { Holder.clear(); Holder.dropPending(); Scans.lastStorage = null }
    }

    @After fun tearDown() {
        ins.runOnMainSync {
            browser?.quickLook?.dismiss()
            browser?.finish()
            main?.finish()
            Holder.clear()
        }
        Holder.io.submit {}.get(30, TimeUnit.SECONDS)
        BgScan.auto = true
        Perms.filesOverride = null
        if (savedMeta == null) prefs.edit().remove(storageKey).commit()
        else prefs.edit().putString(storageKey, savedMeta).commit()
        dir?.let { d -> if (d.path.startsWith(ctx.cacheDir.path + "/")) d.deleteRecursively() }
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

    private fun fixture(): File =
        java.nio.file.Files.createTempDirectory(ctx.cacheDir.toPath(), "big").toFile().also {
            assertTrue(it.isAbsolute && it.path.startsWith(ctx.cacheDir.path + "/"))
            dir = it
            File(it, "Download").mkdirs()
            File(it, "Download/big.bin").writeBytes(ByteArray(300_000))
            File(it, "DCIM/Camera").mkdirs()
            File(it, "DCIM/Camera/v.mp4").writeBytes(ByteArray(200_000))
            File(it, "notes.txt").writeBytes(ByteArray(50_000))
            // Разное число блоков у каждого: порядок не зависит от равенства размеров.
            for (k in 0 until 8) File(it, "s$k.txt").writeBytes(ByteArray(4096 * (k + 1) + 1))
        }

    /** Дерево [root] — в Holder как дерево общего хранилища без root. */
    private fun asStorage(root: File) {
        val h = Native.scanStart(root.path, true, 2, IntArray(1))
        val p = LongArray(6)
        val deadline = System.currentTimeMillis() + 10_000
        while (Native.progress(h, p).let { p[0] == ST_RUNNING.toLong() } && System.currentTimeMillis() < deadline)
            Thread.sleep(25)
        assertEquals(ST_DONE.toLong(), p[0])
        ins.runOnMainSync { Holder.set(h, Kind.SCAN, Scans.STORAGE, false, System.currentTimeMillis()) }
    }

    private fun launch(): MainActivity =
        (ins.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity)
            .also { main = it; ins.waitForIdleSync() }

    @Test fun homeShowsBiggestFilesAndTapOpensTheFile() {
        asStorage(fixture())
        val a = launch()
        assertTrue("крупнейшие файлы не появились за 5 с", waitFor { a.biggest.rows.size == Biggest.K })
        assertTrue(waitFor { a.biggest.rowViews[0].height > 0 })
        var shownBefore = 0
        ins.runOnMainSync {
            // Тот же источник (поколение дерева, удалений не было) — строки не пересчитываются.
            val n = a.biggest.loads
            a.biggest.refresh()
            assertEquals(n, a.biggest.loads)
            shownBefore = a.biggest.shown
            a.biggest.refresh(force = true)
            assertEquals(n + 1, a.biggest.loads)
            // Волосяные линии только между строками: после последней — ничего.
            val list = a.biggest.rowViews.last().parent as android.view.ViewGroup
            assertEquals(a.biggest.rowViews.last(), list.getChildAt(list.childCount - 1))
            assertEquals(2 * Biggest.K - 1, list.childCount)
        }
        // Дождаться, пока принудительная перезагрузка действительно покажет новые строки.
        assertTrue(waitFor { a.biggest.shown > shownBefore && a.biggest.rows.size == Biggest.K && a.biggest.rowViews[0].height > 0 })
        ins.runOnMainSync {
            val rows = a.biggest.rows
            assertEquals(View.VISIBLE, a.biggest.box.visibility)
            assertEquals(listOf("big.bin", "v.mp4", "notes.txt", "s7.txt", "s6.txt"), rows.map { it.name })
            assertEquals("Download/", rows[0].parent)
            assertEquals("DCIM/Camera/", rows[1].parent)
            // Файл в самом корне: подпись — заголовок корня, как в браузере (для /storage/emulated/0 —
            // «Внутренняя память», для временного корня теста — его имя).
            assertEquals(PathText.rootTitle(dir!!.path, a.getString(R.string.internal_storage)), rows[2].parent)
            assertEquals(TagKind.DL, rows[0].tag?.kind)
            assertEquals(TagKind.MEDIA, rows[1].tag?.kind)
            val v = a.biggest.rowViews[0] as BigRow
            assertTrue("строка ниже 48dp", v.height >= a.dp(48))
            assertEquals(a.getString(R.string.big_desc, "big.bin", v.size, "Download/") + ", " + a.getString(R.string.tag_dl),
                v.contentDescription.toString())
        }
        val mon = ins.addMonitor(BrowserActivity::class.java.name, null, false)
        try {
            ins.runOnMainSync { a.biggest.rowViews[1].performClick() }
            val b = ins.waitForMonitorWithTimeout(mon, 10_000) as BrowserActivity?
            assertNotNull("браузер не открылся", b)
            browser = b
            assertTrue("карточка файла не открылась", waitFor { b!!.quickLook?.dialog?.isShowing == true })
            ins.runOnMainSync {
                val q = b!!.quickLook!!
                assertEquals("v.mp4", q.info.name)
                assertEquals("Camera", b.title.text.toString())
                assertTrue(b.currentPath, b.currentPath.endsWith("/DCIM/Camera"))
            }
        } finally {
            ins.removeMonitor(mon)
        }
    }

    @Test fun hiddenWithoutTreeOrAccess() {
        prefs.edit().remove(storageKey).commit()
        val a = launch()
        Holder.io.submit {}.get(10, TimeUnit.SECONDS)
        ins.waitForIdleSync()
        ins.runOnMainSync { assertEquals(View.GONE, a.biggest.box.visibility) }
        // Дерево есть, а доступа нет — тоже нет секции.
        asStorage(fixture())
        assertTrue(waitFor { a.biggest.rows.isNotEmpty() })
        Perms.filesOverride = false
        ins.runOnMainSync {
            a.biggest.refresh()
            assertEquals(View.GONE, a.biggest.box.visibility)
        }
    }
}
