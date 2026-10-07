package dev.ancdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            // Долгое нажатие строки — только вибрация, без нового звука (#7).
            Cue.LONG_PRESS to Decision(null, Haptic.LONG_PRESS),
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
        for (c in listOf(Cue.TAP, Cue.BACK, Cue.ARM, Cue.ARM_ROOT, Cue.COUNT, Cue.READY, Cue.LONG_PRESS))
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

    @Test fun modeLabels() {
        assertEquals(listOf("System", "On", "Off"), FxMode.entries.map { XmlTxt.EN.s(it.label) })
        assertEquals(listOf("Как в системе", "Вкл", "Выкл"), FxMode.entries.map { XmlTxt.RU.s(it.label) })
        assertEquals("Sound & haptics: On", XmlTxt.EN.s(R.string.fx_item, XmlTxt.EN.s(FxMode.ON.label)))
        assertEquals("Звук и вибрация: Выкл", XmlTxt.RU.s(R.string.fx_item, XmlTxt.RU.s(FxMode.OFF.label)))
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
        // Тишина кончается ровно через 400 мс без касаний (история всплеска сбрасывается).
        val last = taps.last()
        fun afterBurst(): TickLimiter = TickLimiter().also { l -> for (t in taps) FeedbackPolicy.decide(Cue.TAP, on, l, t) }
        assertNull(FeedbackPolicy.decide(Cue.TAP, on, afterBurst(), last + 399).sound)
        val lim = afterBurst()
        assertEquals(Sound.TICK, FeedbackPolicy.decide(Cue.TAP, on, lim, last + 400).sound)
        // После сброса обычный темп снова звучит (история не тянет старый всплеск).
        assertEquals(Sound.TICK, FeedbackPolicy.decide(Cue.TAP, on, lim, last + 470).sound)
        // Касание внутри тишины продлевает её: от него снова отсчитываются 400 мс…
        val ext = afterBurst()
        assertNull(FeedbackPolicy.decide(Cue.TAP, on, ext, last + 300).sound)
        assertNull(FeedbackPolicy.decide(Cue.TAP, on, ext, last + 580).sound)
        // …но не дольше окна 1,5 с с начала тишины (она началась на 7-м касании, t = 420).
        assertEquals(Sound.TICK, FeedbackPolicy.decide(Cue.TAP, on, ext, 420 + 1500).sound)
    }

    /** Ровный темп без пауз ≥ 400 мс (230 мс ≈ 4,3 касания/с) не глушит навсегда. */
    @Test fun steadyTappingNeverMutesForGood() {
        val taps = (0 until 40).map { it * 230L }
        val got = played(taps)
        assertEquals(taps.take(6), got.take(6))
        val gaps = (listOf(0L) + got + listOf(taps.last())).zipWithNext { a, b -> b - a }
        // Пауза не длиннее окна плюс по касанию с каждой стороны тишины.
        assertTrue("longest silence ${gaps.max()} ms", gaps.max() <= 1500 + 2 * 230)
        assertTrue(got.size >= 15)
    }

    /** TalkBack и тихий ringer вместе: итоговые события — только вибрация, остальные — ничего. */
    @Test fun talkBackAndSilentRinger() {
        val env = on.copy(touchExploration = true, ringerNormal = false)
        for (c in listOf(Cue.TAP, Cue.BACK, Cue.ARM, Cue.ARM_ROOT, Cue.COUNT, Cue.READY))
            assertEquals("$c", Decision(null, null), d(c, env))
        for (c in listOf(Cue.COMMIT, Cue.COMMIT_ROOT, Cue.DONE, Cue.REFUSE)) assertEquals("$c", Decision(null, d(c).haptic), d(c, env))
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

    /** Итог удаления: готово — done; «Стоп» пользователя (-EINTR, сколько бы ни удалилось) — тишина; прочее — refuse. */
    @Test fun afterDelete() {
        assertEquals(Cue.DONE, FeedbackPolicy.afterDelete(0))
        assertNull(FeedbackPolicy.afterDelete(-DeleteProgress.EINTR))
        assertEquals(Cue.REFUSE, FeedbackPolicy.afterDelete(-1))     // su отказал, ничего не удалено
        assertEquals(Cue.REFUSE, FeedbackPolicy.afterDelete(-DeleteProgress.ELOOP))
        assertEquals(Cue.REFUSE, FeedbackPolicy.afterDelete(-NativeErr.ESTALE)) // изменилось после скана
        assertEquals(Cue.REFUSE, FeedbackPolicy.afterDelete(-5))     // -EIO, частично
    }

    /** Открытие листа: запрет — refuse (и с root), root — armRoot, иначе arm. */
    @Test fun sheetOpen() {
        assertEquals(Cue.REFUSE, FeedbackPolicy.sheetOpen(blocked = true, viaRoot = false))
        assertEquals(Cue.REFUSE, FeedbackPolicy.sheetOpen(blocked = true, viaRoot = true))
        assertEquals(Cue.ARM_ROOT, FeedbackPolicy.sheetOpen(blocked = false, viaRoot = true))
        assertEquals(Cue.ARM, FeedbackPolicy.sheetOpen(blocked = false, viaRoot = false))
    }

    /** Отсчёт: первое число молчит (совпадает с arm), каждое новое — count, повтор того же — тишина. */
    @Test fun countdownCues() {
        fun cues(shown: List<Long>): List<Cue?> {
            var prev: Long? = null
            return shown.map { s -> FeedbackPolicy.countCue(prev, s).also { prev = s } }
        }
        assertEquals(listOf(null, Cue.COUNT, Cue.COUNT), cues(DeletePolicy.countdown(DeletePolicy.ROOT_PAUSE_MS)))
        assertEquals(listOf(null, Cue.COUNT), cues(DeletePolicy.countdown(DeletePolicy.PAUSE_MS)))
        // Таймер срабатывает каждые ≤100 мс: одно число показывается много раз — count один раз.
        assertEquals(listOf(null, null, null, Cue.COUNT, null, Cue.COUNT), cues(listOf(3L, 3L, 3L, 2L, 2L, 1L)))
    }
}
