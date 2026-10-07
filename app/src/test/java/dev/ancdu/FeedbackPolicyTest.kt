package dev.ancdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedbackPolicyTest {
    private val on = FxEnv(FxMode.ON, ringerNormal = true, touchExploration = false, systemSounds = true, systemHaptics = true)

    /** Решение при свободном ограничителе (новый на каждый вызов). */
    private fun d(cue: Cue, env: FxEnv = on) = FeedbackPolicy.decide(cue, env, TickLimiter(), 0L)

    @Test fun mapping() {
        val want = mapOf(
            Cue.TAP to Decision(Sound.TICK, Haptic.CLOCK_TICK),
            Cue.BACK to Decision(Sound.TOCK, Haptic.CONTEXT_CLICK),
            Cue.ARM to Decision(Sound.ARM, null),
            Cue.ARM_ROOT to Decision(Sound.ARM_ROOT, null),
            Cue.COUNT to Decision(Sound.COUNT, Haptic.CLOCK_TICK),
            Cue.READY to Decision(Sound.READY, Haptic.QUICK_RISE),
            Cue.COMMIT to Decision(Sound.COMMIT, Haptic.CLICK),
            Cue.COMMIT_ROOT to Decision(Sound.COMMIT, Haptic.CLICK_THUD),
            Cue.DONE to Decision(Sound.DONE, Haptic.CONFIRM),
            Cue.REFUSE to Decision(Sound.REFUSE, Haptic.REJECT),
        )
        assertEquals(Cue.entries.toSet(), want.keys)
        for ((c, w) in want) assertEquals("$c", w, d(c))
    }

    @Test fun derivedRates() {
        assertEquals("tick" to 0.67f, Sound.TOCK.src to Sound.TOCK.rate)
        assertEquals("arm" to 0.75f, Sound.ARM_ROOT.src to Sound.ARM_ROOT.rate)
        assertEquals("count" to 1.5f, Sound.READY.src to Sound.READY.rate)
        for (s in Sound.entries) assert(s.src in Synth.NAMES) { "$s" }
    }

    /** Ringer не NORMAL (тихий, вибрация) — только вибрация. */
    @Test fun ringer() {
        val quiet = on.copy(ringerNormal = false)
        for (c in Cue.entries) {
            assertNull("$c", d(c, quiet).sound)
            assertEquals("$c", d(c).haptic, d(c, quiet).haptic)
        }
    }

    /** TalkBack: tick, tock, arm, count и ready молчат целиком; commit, done, refuse — со звуком и вибрацией. */
    @Test fun talkBack() {
        val tb = on.copy(touchExploration = true)
        for (c in listOf(Cue.TAP, Cue.BACK, Cue.ARM, Cue.ARM_ROOT, Cue.COUNT, Cue.READY))
            assertEquals("$c", Decision(null, null), d(c, tb))
        for (c in listOf(Cue.COMMIT, Cue.COMMIT_ROOT, Cue.DONE, Cue.REFUSE)) assertEquals("$c", d(c), d(c, tb))
    }

    @Test fun modes() {
        val off = on.copy(mode = FxMode.OFF)
        for (c in Cue.entries) assertEquals("$c", Decision(null, null), d(c, off))
        // ON — системные «Звук нажатия» и «Виброотклик» не важны.
        val onSysOff = on.copy(systemSounds = false, systemHaptics = false)
        for (c in Cue.entries) assertEquals("$c", d(c), d(c, onSysOff))
        // SYSTEM — звук по «Звуку нажатия», вибрация по «Виброотклику», независимо.
        val sys = on.copy(mode = FxMode.SYSTEM)
        for (c in Cue.entries) {
            assertEquals("$c", d(c), d(c, sys))
            val noSound = d(c, sys.copy(systemSounds = false))
            assertNull("$c", noSound.sound); assertEquals("$c", d(c).haptic, noSound.haptic)
            val noHaptic = d(c, sys.copy(systemHaptics = false))
            assertEquals("$c", d(c).sound, noHaptic.sound); assertNull("$c", noHaptic.haptic)
        }
        // Правила ringer и TalkBack действуют и в SYSTEM.
        assertNull(d(Cue.DONE, sys.copy(ringerNormal = false)).sound)
        assertEquals(Decision(null, null), d(Cue.TAP, sys.copy(touchExploration = true)))
    }

    @Test fun modeTags() {
        assertEquals(FxMode.SYSTEM, FxMode.of(null))
        assertEquals(FxMode.SYSTEM, FxMode.of("junk"))
        for (m in FxMode.entries) assertEquals(m, FxMode.of(m.tag))
    }

    /** Звуки tick за серией вызовов в моменты [times] (мс): какие прозвучали. */
    private fun played(times: List<Long>, cue: (Int) -> Cue = { Cue.TAP }): List<Long> {
        val lim = TickLimiter()
        return times.filterIndexed { i, t -> FeedbackPolicy.decide(cue(i), on, lim, t).sound != null }
    }

    @Test fun minGapBetweenTicks() {
        assertEquals(listOf(0L, 60L, 130L), played(listOf(0, 59, 60, 100, 130)))
        // tock делит интервал с tick.
        assertEquals(listOf(0L, 70L), played(listOf(0, 30, 70)) { if (it == 1) Cue.BACK else Cue.TAP })
    }

    /** Больше 6 тиков за 1,5 с — тишина до 400 мс без касаний (как превью: 20 тапов через 70 мс). */
    @Test fun burstMutesUntilQuiet() {
        val taps = (0 until 20).map { it * 70L }
        assertEquals((0 until 6).map { it * 70L }, played(taps))
        // После паузы ≥ 400 мс после последнего касания звук возвращается.
        val lim = TickLimiter()
        for (t in taps) FeedbackPolicy.decide(Cue.TAP, on, lim, t)
        val last = taps.last()
        assertNull(FeedbackPolicy.decide(Cue.TAP, on, lim, last + 399).sound)   // касание продлевает тишину
        assertEquals(Sound.TICK, FeedbackPolicy.decide(Cue.TAP, on, lim, last + 399 + 2000).sound)
    }

    /** Ограничитель — только звук tick/tock: вибрация остаётся, остальные звуки не задерживаются. */
    @Test fun limiterOnlyTickSound() {
        val lim = TickLimiter()
        FeedbackPolicy.decide(Cue.TAP, on, lim, 0)
        val again = FeedbackPolicy.decide(Cue.TAP, on, lim, 10)
        assertEquals(Decision(null, Haptic.CLOCK_TICK), again)
        assertEquals(Sound.COUNT, FeedbackPolicy.decide(Cue.COUNT, on, lim, 20).sound)
        assertEquals(Sound.DONE, FeedbackPolicy.decide(Cue.DONE, on, lim, 21).sound)
    }
}
