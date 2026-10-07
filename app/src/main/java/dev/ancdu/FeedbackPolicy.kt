package dev.ancdu

/** Событие интерфейса, которое озвучивается и/или отдаётся вибрацией. */
enum class Cue {
    /** Нажатие, переход в папку, сегмент. */
    TAP,
    /** Назад, отмена, «Стоп». */
    BACK,
    /** Открылся лист удаления (ARM_ROOT — удаление от root). */
    ARM, ARM_ROOT,
    /** Очередная секунда обратного отсчёта (кроме первой). */
    COUNT,
    /** «Удалить» стала доступна. */
    READY,
    /** Удаление подтверждено (COMMIT_ROOT — от root). */
    COMMIT, COMMIT_ROOT,
    DONE,
    /** Отказ: ничего не удалено, ошибка, удаление запрещено. */
    REFUSE,
}

/** Звук: файл [src] (Synth) и скорость SoundPool [rate]. */
enum class Sound(val src: String, val rate: Float) {
    TICK("tick", 1f), TOCK("tick", 0.67f),
    ARM("arm", 1f), ARM_ROOT("arm", 0.75f),
    COUNT("count", 1f), READY("count", 1.5f),
    COMMIT("commit", 1f), DONE("done", 1f), REFUSE("refuse", 1f),
}

/**
 * Вибрация. CLOCK_TICK, CONTEXT_CLICK, CONFIRM, REJECT — HapticFeedbackConstants;
 * QUICK_RISE (0.6) и CLICK_THUD (CLICK, через 70 мс THUD) — композиции Vibrator; CLICK — EFFECT_CLICK.
 */
enum class Haptic { CLOCK_TICK, CONTEXT_CLICK, QUICK_RISE, CLICK, CLICK_THUD, CONFIRM, REJECT }

/** Настройка «Звук и вибрация»; [tag] — значение в prefs, [label] — подпись в меню. */
enum class FxMode(val tag: String, val label: Int) {
    SYSTEM("system", R.string.fx_system), ON("on", R.string.fx_on), OFF("off", R.string.fx_off);

    companion object {
        fun of(stored: String?): FxMode = entries.firstOrNull { it.tag == stored } ?: SYSTEM
    }
}

/**
 * Обстановка на момент события. [systemSounds] — системный «Звук нажатия»
 * (SOUND_EFFECTS_ENABLED), [systemHaptics] — «Виброотклик» (HAPTIC_FEEDBACK_ENABLED);
 * учитываются только в режиме SYSTEM.
 */
data class FxEnv(val mode: FxMode, val ringerNormal: Boolean, val touchExploration: Boolean,
                 val systemSounds: Boolean, val systemHaptics: Boolean)

data class Decision(val sound: Sound?, val haptic: Haptic?)

/**
 * Частота tick/tock (общая): между звуками не меньше 60 мс; больше 6 касаний за 1,5 с — тишина,
 * пока не будет 400 мс без касаний. В историю идёт каждое касание, как в утверждённом превью;
 * когда тишина кончается, история всплеска сбрасывается — иначе заглушённые касания держали бы
 * её до 1,5 с.
 */
class TickLimiter {
    private var last = Long.MIN_VALUE / 2
    private var lastTap = Long.MIN_VALUE / 2
    private var muted = false
    private val hist = ArrayDeque<Long>()

    fun allow(now: Long): Boolean {
        if (muted && now - lastTap >= QUIET_MS) { muted = false; hist.clear() }
        lastTap = now
        hist.addLast(now)
        while (now - hist.first() > WINDOW_MS) hist.removeFirst()
        if (hist.size > BURST) muted = true
        val ok = !muted && now - last >= GAP_MS
        if (ok) last = now
        return ok
    }

    companion object {
        const val GAP_MS = 60L
        const val WINDOW_MS = 1500L
        const val BURST = 6
        const val QUIET_MS = 400L
    }
}

/** Чистое решение «что прозвучит и чем вибрировать». Состояние частоты — в [TickLimiter]. */
object FeedbackPolicy {
    private val MAP = mapOf(
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

    /** С TalkBack остаются только итоговые события: подтверждение, готово, отказ. */
    private val KEEP_WITH_TALKBACK = setOf(Cue.COMMIT, Cue.COMMIT_ROOT, Cue.DONE, Cue.REFUSE)

    private val NONE = Decision(null, null)

    /** [now] — монотонное время в мс (для [ticks]). */
    fun decide(cue: Cue, env: FxEnv, ticks: TickLimiter, now: Long): Decision {
        if (env.mode == FxMode.OFF) return NONE
        if (env.touchExploration && cue !in KEEP_WITH_TALKBACK) return NONE
        val base = MAP.getValue(cue)
        val sys = env.mode == FxMode.SYSTEM
        var sound = base.sound.takeIf { env.ringerNormal && (!sys || env.systemSounds) }
        if (sound == Sound.TICK || sound == Sound.TOCK) { if (!ticks.allow(now)) sound = null }
        val haptic = base.haptic.takeIf { !sys || env.systemHaptics }
        return Decision(sound, haptic)
    }

    /**
     * Звук итога удаления [r]: 0 — done; -EINTR — остановил пользователь («Стоп» уже дал tock),
     * сколько бы ни удалилось, — тишина; любой другой код (ничего не удалено, ошибка, частично) — refuse.
     */
    fun afterDelete(r: Int): Cue? = when (r) {
        0 -> Cue.DONE
        -DeleteProgress.EINTR -> null
        else -> Cue.REFUSE
    }

    /** Открытие листа удаления: удаление запрещено — refuse, от root — armRoot, иначе arm. */
    fun sheetOpen(blocked: Boolean, viaRoot: Boolean): Cue = when {
        blocked -> Cue.REFUSE
        viaRoot -> Cue.ARM_ROOT
        else -> Cue.ARM
    }

    /** Показано число отсчёта [shown] после [previous] (null — первое): новое число, кроме первого, — count. */
    fun countCue(previous: Long?, shown: Long): Cue? = if (previous != null && previous != shown) Cue.COUNT else null
}
