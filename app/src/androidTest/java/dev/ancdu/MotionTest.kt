package dev.ancdu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
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
        ins.runOnMainSync {
            val sweep = Sweep(ins.targetContext).also { it.start() }
            assertNull(sweep.animator)
            val bar = SegBar(ins.targetContext).also { it.used = 0.5f }
            assertNull(bar.fill)
        }
    }

    @Test fun sweepAndFillRunWhenAnimationsOn() {
        Motion.override = true
        ins.runOnMainSync {
            val sweep = Sweep(ins.targetContext).also { it.start() }
            assertNotNull(sweep.animator)
            sweep.stop()
            assertNull(sweep.animator)
            val bar = SegBar(ins.targetContext).also { it.used = 0.5f }
            assertNotNull(bar.fill)
            bar.fill!!.end()
        }
    }
}
