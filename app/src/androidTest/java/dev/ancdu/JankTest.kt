package dev.ancdu

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Бюджет кадров (DUT, 120 Гц, срок кадра 8,33 мс): dumpsys gfxinfo сбрасывается, идёт
 * сценарий, затем читается итог. Ворота — по медиане трёх прогонов: прокрутка — janky ≤ 5% и
 * p95 ≤ 12 мс; переходы вглубь/назад — janky ≤ 10%; лист удаления — только отчёт. В каждом
 * прогоне не меньше 100 кадров. Анимации системы должны быть включены.
 *
 * Отдельный запуск (в обычном прогоне пропускается):
 * am instrument -w -e class dev.ancdu.JankTest -e jank true dev.ancdu.test/androidx.test.runner.AndroidJUnitRunner
 * (или gradle connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.ancdu.JankTest
 * -Pandroid.testInstrumentationRunnerArguments.jank=true). Числа — в logcat с тегом ancdu-jank.
 */
@RunWith(AndroidJUnit4::class)
class JankTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context get() = ins.targetContext
    private lateinit var dir: File
    private var act: BrowserActivity? = null

    data class Frames(val total: Int, val janky: Int, val jankyPct: Double, val p95: Int)

    @Before fun setUp() {
        assumeTrue("jank test: run with -e jank true", InstrumentationRegistry.getArguments().getString("jank") == "true")
        // Каталог mkdtemp под cacheDir, только абсолютные пути внутри него: 200 каталогов на
        // корне, 200 в первом из них, 200 файлов в самом глубоком — первый ряд всегда крупнейший.
        dir = java.nio.file.Files.createTempDirectory(ctx.cacheDir.toPath(), "jank").toFile().absoluteFile
        for (i in 0 until 200) File(dir, "a%03d".format(i)).mkdir()
        val a0 = File(dir, "a000")
        for (i in 0 until 200) File(a0, "b%03d".format(i)).mkdir()
        val deep = File(a0, "b000")
        for (i in 0 until 200) File(deep, "f%03d.bin".format(i)).writeBytes(ByteArray(1))
        val h = Native.scanStart(dir.path, true, 2, IntArray(1))
        val p = LongArray(6)
        val deadline = System.currentTimeMillis() + 30_000
        while (Native.progress(h, p).let { p[0] == ST_RUNNING.toLong() } && System.currentTimeMillis() < deadline)
            Thread.sleep(25)
        assertEquals(ST_DONE.toLong(), p[0])
        ins.runOnMainSync { Holder.set(h, Kind.SCAN, dir.path, false) }
        act = ins.startActivitySync(Intent(ctx, BrowserActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        SystemClock.sleep(500)
    }

    @After fun tearDown() {
        if (!::dir.isInitialized) return
        try {
            act?.let { a -> ins.runOnMainSync { a.sheet?.dismiss(); a.finish() } }
            Holder.io.submit {}.get()
            Holder.cacheFile(ctx, dir.path, false).delete()
            ctx.getSharedPreferences(Scans.PREFS, Context.MODE_PRIVATE).edit()
                .remove(Holder.cacheFile(ctx, dir.path, false).name).commit()
        } finally {
            // Дерево каталога не остаётся в Holder для следующих тестов.
            ins.runOnMainSync { Holder.clear() }
            // Только свой каталог mkdtemp (абсолютный путь под cacheDir).
            check(dir.isAbsolute && dir.parentFile == ctx.cacheDir.absoluteFile && dir.name.startsWith("jank"))
            dir.deleteRecursively()
        }
    }

    private fun shell(cmd: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(cmd)).use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    private fun parse(out: String): Frames {
        val total = Regex("Total frames rendered: (\\d+)").find(out)?.groupValues?.get(1)?.toInt()
        val janky = Regex("Janky frames: (\\d+) \\(([\\d.]+)%\\)").find(out)
        val p95 = Regex("95th percentile: (\\d+)ms").find(out)?.groupValues?.get(1)?.toInt()
        assertNotNull(out, total); assertNotNull(out, janky); assertNotNull(out, p95)
        return Frames(total!!, janky!!.groupValues[1].toInt(), janky.groupValues[2].toDouble(), p95!!)
    }

    /** Три прогона сценария [run]; кадры каждого — не меньше 100. */
    private fun measure(name: String, run: () -> Unit): List<Frames> = (1..3).map { k ->
        shell("dumpsys gfxinfo ${ctx.packageName} reset")
        run()
        SystemClock.sleep(300)
        parse(shell("dumpsys gfxinfo ${ctx.packageName}")).also {
            Log.i("ancdu-jank", "$name run $k: $it")
            assertTrue("$name run $k: too few frames: $it", it.total >= 100)
        }
    }

    private fun median(xs: List<Double>): Double = xs.sorted()[xs.size / 2]

    @Test fun scrollFlings() {
        val a = act!!
        var x = 0; var top = 0; var h = 0
        ins.runOnMainSync {
            val loc = IntArray(2).also { a.list.getLocationOnScreen(it) }
            x = loc[0] + a.list.width / 2; top = loc[1]; h = a.list.height
        }
        val lo = top + h * 4 / 5
        val hi = top + h / 5
        val runs = measure("scroll") {
            // 20 флингов: вниз и обратно, список не упирается в край надолго.
            for (i in 0 until 20) {
                val (y1, y2) = if (i % 4 < 2) lo to hi else hi to lo
                shell("input swipe $x $y1 $x $y2 60")
                SystemClock.sleep(700)
            }
        }
        val jank = median(runs.map { it.jankyPct })
        val p95 = median(runs.map { it.p95.toDouble() })
        Log.i("ancdu-jank", "scroll median janky=$jank% p95=${p95}ms")
        assertTrue("scroll: janky $jank% > 5% ($runs)", jank <= 5.0)
        assertTrue("scroll: p95 ${p95}ms > 12ms ($runs)", p95 <= 12.0)
    }

    @Test fun drillAndBack() {
        val a = act!!
        val runs = measure("drill") {
            // 20 шагов: вглубь, вглубь, назад, назад.
            for (i in 0 until 20) {
                ins.runOnMainSync { if (i % 4 < 2) a.list.source!!.click(0) else a.onBackPressed() }
                SystemClock.sleep(250)
            }
        }
        val jank = median(runs.map { it.jankyPct })
        Log.i("ancdu-jank", "drill median janky=$jank% p95=${median(runs.map { it.p95.toDouble() })}ms")
        assertTrue("drill/back: janky $jank% > 10% ($runs)", jank <= 10.0)
    }

    /** Лист удаления: открыть и закрыть (без удаления) — только отчёт. */
    @Test fun deleteSheetReportOnly() {
        val a = act!!
        ins.runOnMainSync { a.list.source!!.click(0); a.list.source!!.click(0) }   // a000/b000: файлы
        ins.waitForIdleSync()
        val runs = (1..3).map { k ->
            shell("dumpsys gfxinfo ${ctx.packageName} reset")
            for (i in 0 until 10) {
                ins.runOnMainSync { a.askDelete(i) }
                SystemClock.sleep(400)
                ins.runOnMainSync { a.sheet?.dismiss() }
                SystemClock.sleep(300)
            }
            parse(shell("dumpsys gfxinfo ${ctx.packageName}")).also { Log.i("ancdu-jank", "sheet run $k: $it") }
        }
        Log.i("ancdu-jank", "sheet median janky=${median(runs.map { it.jankyPct })}% " +
            "p95=${median(runs.map { it.p95.toDouble() })}ms (report only)")
    }
}
