package dev.ancdu

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Сокращение движения: при масштабе анимаций 0 нет ни развёртки скана, ни заполнения полосы, ни
 * переходов экранов, ни сдвига списка, ни анимации листа удаления.
 */
@RunWith(AndroidJUnit4::class)
class MotionTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    @After fun tearDown() { Motion.override = null }

    @Test fun noSweepAndNoFillWhenAnimationsOff() {
        Motion.override = false
        SegBar.filledOnce = false
        ins.runOnMainSync {
            val sweep = Sweep(ins.targetContext).also { it.start() }
            assertNull(sweep.animator)
            val bar = SegBar(ins.targetContext).also { it.used = 0.5f }
            assertNull(bar.fill)
        }
    }

    @Test fun sweepAndFillRunWhenAnimationsOn() {
        Motion.override = true
        SegBar.filledOnce = false
        ins.runOnMainSync {
            val sweep = Sweep(ins.targetContext).also { it.start() }
            assertNotNull(sweep.animator)
            sweep.stop()
            assertNull(sweep.animator)
            val bar = SegBar(ins.targetContext).also { it.used = 0.5f }
            assertNotNull(bar.fill)
            bar.fill!!.end()
            // Второй экран того же процесса — без анимации.
            assertNull(SegBar(ins.targetContext).also { it.used = 0.5f }.fill)
        }
    }

    /**
     * Полоса скана без анимаций: неопределённая — статичная (без бега), определённая — шагами,
     * конец — скрыта сразу. С анимациями: бег по кругу, конец — 100% и скрытие через 300 мс.
     */
    @Test fun scanLineWithoutMotion() {
        Motion.override = false
        ins.runOnMainSync {
            val l = ScanLine(ins.targetContext)
            l.show(null)
            assertEquals(View.VISIBLE, l.visibility)
            assertNull(l.animator)
            l.show(0.2f); l.show(0.5f)
            assertNull(l.animator)
            l.finish()
            assertEquals(View.INVISIBLE, l.visibility)
        }
    }

    @Test fun scanLineWithMotion() {
        Motion.override = true
        ins.runOnMainSync {
            val l = ScanLine(ins.targetContext)
            l.show(null)
            assertNotNull(l.animator)
            l.show(0.2f)
            l.finish()
            assertNull(l.animator)
            assertEquals(1f, l.fraction!!, 0f)
            assertEquals("скрывается через 300 мс", View.VISIBLE, l.visibility)
            l.hide()
            assertEquals(View.INVISIBLE, l.visibility)
        }
    }

    /** Дерево [dir] в Holder и браузер на нём. */
    private fun browse(dir: File): BrowserActivity {
        val h = Native.scanStart(dir.path, true, 2, IntArray(1))
        val p = LongArray(6)
        val deadline = System.currentTimeMillis() + 10_000
        while (Native.progress(h, p).let { p[0] == ST_RUNNING.toLong() } && System.currentTimeMillis() < deadline)
            Thread.sleep(25)
        assertEquals(ST_DONE.toLong(), p[0])
        ins.runOnMainSync { Holder.set(h, Kind.SCAN, dir.path, false) }
        val a = ins.startActivitySync(Intent(ins.targetContext, BrowserActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        return a
    }

    private fun forget(dir: File) {
        val ctx = ins.targetContext
        Holder.io.submit {}.get()
        Holder.cacheFile(ctx, dir.path, false).delete()
        ctx.getSharedPreferences(Scans.PREFS, Context.MODE_PRIVATE).edit()
            .remove(Holder.cacheFile(ctx, dir.path, false).name).commit()
    }

    private fun preview() = DeletePreview(name = "a.bin", path = "/x/a.bin", dir = false, disk = 10, apparent = 10,
        items = 1, flags = 0, top = emptyList(), more = 0, owner = null, viaRoot = false, block = null,
        kind = Kind.SCAN, cacheTime = null)

    /**
     * Без анимаций: переходы экранов (0, 0), окно листа без анимации, список при переходе по
     * дереву — сразу на месте. С анимациями: anim-ресурсы, SheetAnim, список въезжает с ±16dp.
     */
    @Test fun screensListAndSheet() {
        val ctx = ins.targetContext
        val dir = java.nio.file.Files.createTempDirectory(ctx.cacheDir.toPath(), "motion").toFile()
        File(dir, "sub/deep").mkdirs()
        File(dir, "sub/deep/a.bin").writeBytes(ByteArray(5000))
        val act = browse(dir)
        try {
            Motion.override = false
            assertEquals(0 to 0, Motion.transition(open = true))
            assertEquals(0 to 0, Motion.transition(open = false))
            ins.runOnMainSync {
                assertEquals(0, DeleteSheet(act, preview()) {}.dialog.window!!.attributes.windowAnimations)
                act.list.source!!.click(0)   // sub/
                assertEquals(0f, act.list.translationX, 0f)
                assertEquals(1f, act.list.alpha, 0f)
                act.onBackPressed()
                assertEquals(0f, act.list.translationX, 0f)
                assertEquals(1f, act.list.alpha, 0f)
            }
            Motion.override = true
            assertEquals(R.anim.screen_open to R.anim.screen_hold_open, Motion.transition(open = true))
            assertEquals(R.anim.screen_hold_close to R.anim.screen_close, Motion.transition(open = false))
            val d16 = ctx.dp(16).toFloat()
            ins.runOnMainSync {
                assertEquals(R.style.SheetAnim, DeleteSheet(act, preview()) {}.dialog.window!!.attributes.windowAnimations)
                act.list.source!!.click(0)   // вглубь: справа
                assertEquals(d16, act.list.translationX, 0f)
                act.list.source!!.click(0)   // ещё вглубь: идущая анимация отменена, снова с +16dp
                assertEquals(d16, act.list.translationX, 0f)
                act.onBackPressed()          // назад: слева
                assertEquals(-d16, act.list.translationX, 0f)
                assertEquals(0f, act.list.alpha, 0f)
                act.jumpTo(0)                // крошки: слева
                assertEquals(-d16, act.list.translationX, 0f)
                act.setSort(SORT_NAME)       // тот же уровень: без анимации
                assertEquals(0f, act.list.translationX, 0f)
                assertEquals(1f, act.list.alpha, 0f)
            }
        } finally {
            ins.runOnMainSync { act.finish() }
            forget(dir)
            dir.deleteRecursively()
        }
    }
}
