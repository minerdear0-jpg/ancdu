package dev.ancdu

import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Сокращение движения: при масштабе анимаций 0 нет ни развёртки скана, ни заполнения полосы. */
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
}
