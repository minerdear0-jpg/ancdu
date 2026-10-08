package dev.ancdu

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.accessibility.AccessibilityManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Звуки и вибрация интерфейса. Решение — [FeedbackPolicy]; здесь только обстановка (режим,
 * ringer, TalkBack, системные настройки), SoundPool и вибратор. Звуки синтезируются ([Synth])
 * при первом запуске в cacheDir/fx_v2_<имя>_<частота>.wav — на Holder.io, не на главном потоке.
 * Всё, кроме генерации, — главный поток.
 */
object Feedback {
    private var app: Context? = null
    private var pool: SoundPool? = null
    private var vibrator: Vibrator? = null
    private val ticks = TickLimiter()
    /** Имя звука → id SoundPool (загрузка запрошена). */
    private val ids = ConcurrentHashMap<String, Int>()
    /** id SoundPool, загрузка которых закончилась успешно. */
    private val loaded = ConcurrentHashMap.newKeySet<Int>()

    /** Режим настройки «Звук и вибрация» (prefs читает [init], меняет [FxPrefs.set]). */
    @Volatile var mode = FxMode.SYSTEM

    /** Для тестов: сколько раз вызван SoundPool.play. */
    val plays = AtomicInteger()
    /** Для тестов: поток, на котором писались файлы звуков (null — ещё не писались). */
    @Volatile var genThread: String? = null
        private set
    /** Для тестов: все звуки загружены в SoundPool. */
    val ready: Boolean get() = ids.size == Synth.NAMES.size && ids.values.all { it in loaded }

    /** Частота вывода устройства (PROPERTY_OUTPUT_SAMPLE_RATE), иначе 48000. */
    fun sampleRate(ctx: Context): Int =
        ctx.getSystemService(AudioManager::class.java)?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()?.takeIf { it in 8000..192000 } ?: 48000

    /** Для тестов: каталог файлов звуков вместо cacheDir (свежий временный каталог теста). */
    @Volatile var dirOverride: File? = null

    /** Один раз на процесс, с главного потока (onCreate экранов). */
    fun init(ctx: Context) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Feedback.init off the main thread" }
        if (app != null) return
        val a = ctx.applicationContext
        app = a
        mode = FxPrefs.load(a)
        vibrator = if (Build.VERSION.SDK_INT >= 31) a.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") a.getSystemService(Vibrator::class.java)
        pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build().apply {
            setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded += id }
        }
        prepare(a)
    }

    /** Для тестов: заново подготовить звуки (каталог [dirOverride] мог смениться). Главный поток. */
    fun reload(ctx: Context) {
        init(ctx)
        prepare(app!!)
    }

    /** На Holder.io: недостающие файлы синтезируются и записываются, затем грузятся в SoundPool. */
    private fun prepare(a: Context) {
        val p = pool ?: return
        val sr = sampleRate(a)
        val dir = dirOverride ?: a.cacheDir
        Holder.io.execute {
            for (name in Synth.NAMES) {
                val f = File(dir, "fx_v2_${name}_$sr.wav")
                try {
                    if (!f.isFile) {
                        genThread = Thread.currentThread().name
                        val tmp = File(f.path + ".tmp")
                        tmp.writeBytes(Synth.wav(Synth.render(name, sr), sr))
                        if (!tmp.renameTo(f)) { tmp.delete(); continue }
                    }
                    val old = ids.put(name, p.load(f.path, 1))
                    if (old != null) { p.unload(old); loaded -= old }
                } catch (e: Exception) {
                    Log.w("ancdu", "sound $name not prepared", e)
                }
            }
        }
    }

    // HAPTIC_FEEDBACK_ENABLED устарел с API 33, но это и есть системный «Виброотклик» из ТЗ.
    @Suppress("DEPRECATION")
    private fun env(a: Context): FxEnv {
        val cr = a.contentResolver
        return FxEnv(mode,
            ringerNormal = a.getSystemService(AudioManager::class.java)?.ringerMode == AudioManager.RINGER_MODE_NORMAL,
            touchExploration = a.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true,
            systemSounds = Settings.System.getInt(cr, Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0,
            systemHaptics = Settings.System.getInt(cr, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0)
    }

    /** Событие [cue]; [v] — view для performHapticFeedback (null — вибрация только через Vibrator). Главный поток. */
    /** Для тестов: последний запрошенный сигнал (и при выключенном звуке). */
    @Volatile var lastCue: Cue? = null

    fun cue(v: View?, cue: Cue) {
        lastCue = cue
        val a = app ?: return
        val e = if (mode == FxMode.OFF) FxEnv(FxMode.OFF, false, false, false, false) else env(a)
        val d = FeedbackPolicy.decide(cue, e, ticks, SystemClock.uptimeMillis())
        d.sound?.let { play(it) }
        d.haptic?.let { haptic(v, it) }
    }

    private fun play(s: Sound) {
        val p = pool ?: return
        val id = ids[s.src] ?: return
        if (id !in loaded) return
        plays.incrementAndGet()
        p.play(id, 1f, 1f, 1, 0, s.rate)
    }

    private fun haptic(v: View?, h: Haptic) {
        // «Вкл» — вибрация и при выключенном системном виброотклике (SYSTEM его уже учёл).
        // Ограничение платформы: с API 33 FLAG_IGNORE_GLOBAL_SETTING для обычных приложений
        // игнорируется, и отклик view там всё равно следует системному переключателю; вибрации
        // через Vibrator с USAGE_TOUCH тоже. Итог на 33+: при выключенном системном виброотклике
        // «Вкл» может не дать вибрации вовсе.
        @Suppress("DEPRECATION")
        val flags = if (mode == FxMode.ON) HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING else 0
        fun perform(c: Int) { v?.performHapticFeedback(c, flags) }
        when (h) {
            Haptic.CLOCK_TICK -> perform(HapticFeedbackConstants.CLOCK_TICK)
            Haptic.CONTEXT_CLICK -> perform(HapticFeedbackConstants.CONTEXT_CLICK)
            Haptic.CONFIRM -> perform(HapticFeedbackConstants.CONFIRM)
            Haptic.REJECT -> perform(HapticFeedbackConstants.REJECT)
            Haptic.LONG_PRESS -> perform(HapticFeedbackConstants.LONG_PRESS)
            // QUICK_RISE — API 30 (minSdk).
            Haptic.QUICK_RISE -> if (!compose(30, VibrationEffect.Composition.PRIMITIVE_QUICK_RISE to 0.6f))
                perform(HapticFeedbackConstants.CONTEXT_CLICK)
            Haptic.CLICK -> vibrator?.let { vibrate(it, VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)) }
            // THUD — только API 31+.
            Haptic.CLICK_THUD -> if (!compose(31, VibrationEffect.Composition.PRIMITIVE_CLICK to 1f,
                    VibrationEffect.Composition.PRIMITIVE_THUD to 1f, delayMs = 70))
                perform(HapticFeedbackConstants.CONFIRM)
        }
    }

    /** API 33+: с USAGE_TOUCH — сила вибрации по системной «вибрации при касании». */
    private fun vibrate(vib: Vibrator, e: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= 33) vib.vibrate(e, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        else vib.vibrate(e)
    }

    /**
     * Композиция примитивов (с API [minApi] — самого нового из них, если вибратор их все умеет):
     * первый сразу, следующие — через [delayMs]. false — не сыграна, нужен запасной отклик.
     */
    private fun compose(minApi: Int, vararg prims: Pair<Int, Float>, delayMs: Int = 0): Boolean {
        val vib = vibrator ?: return false
        if (Build.VERSION.SDK_INT < minApi || !vib.hasVibrator()) return false
        if (!vib.areAllPrimitivesSupported(*prims.map { it.first }.toIntArray())) return false
        val c = VibrationEffect.startComposition()
        for ((i, pr) in prims.withIndex()) c.addPrimitive(pr.first, pr.second, if (i == 0) 0 else delayMs)
        vibrate(vib, c.compose())
        return true
    }
}

/** Где живёт настройка «Звук и вибрация»: prefs «ui», ключ «fx». */
object FxPrefs {
    const val KEY = "fx"

    fun load(ctx: Context): FxMode =
        FxMode.of(ctx.getSharedPreferences(LangPrefs.PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun set(ctx: Context, m: FxMode) {
        ctx.getSharedPreferences(LangPrefs.PREFS, Context.MODE_PRIVATE).edit().putString(KEY, m.tag).apply()
        Feedback.mode = m
    }
}

/** Касание [v] звучит и вибрирует через [Feedback]; системный щелчок выключен (не двоится). */
fun View.feedbackClick(cue: Cue = Cue.TAP, onClick: () -> Unit) {
    isSoundEffectsEnabled = false
    setOnClickListener { Feedback.cue(this, cue); onClick() }
}
