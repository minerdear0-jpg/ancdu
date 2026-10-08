package dev.ancdu

import android.app.Activity
import android.app.AlertDialog
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.LocaleList
import java.util.Locale

/** Выбор языка интерфейса: системный, English, Русский. [tag] — BCP 47 («» — системный). */
enum class LangChoice(val tag: String, val label: Int) {
    SYSTEM("", R.string.lang_system),
    EN("en", R.string.lang_en),
    RU("ru", R.string.lang_ru);

    companion object {
        /** Сохранённое значение или теги LocaleManager («ru-RU,en» → RU); незнакомое — SYSTEM. */
        fun of(tags: String?): LangChoice {
            val lang = tags?.substringBefore(',')?.substringBefore('-')?.substringBefore('_')?.lowercase().orEmpty()
            return entries.firstOrNull { it.tag.isNotEmpty() && it.tag == lang } ?: SYSTEM
        }
    }
}

/**
 * Чистый Kotlin: где живёт выбор. Он всегда пишется в prefs; на API 33+ правда — язык
 * приложения в системе (LocaleManager: его меняют и системные настройки «Язык приложения»),
 * на 30–32 — prefs, а язык применяется обёрткой контекста каждой Activity.
 */
object LangPrefs {
    const val PREFS = "ui"
    const val KEY = "lang"

    /** Действующий выбор: [appLocales] — LocaleManager.applicationLocales.toLanguageTags() (API 33+). */
    fun effective(sdk: Int, appLocales: String?, stored: String?): LangChoice =
        if (sdk >= 33) LangChoice.of(appLocales) else LangChoice.of(stored)

    /** Значение для prefs. */
    fun store(c: LangChoice): String = c.tag

    /** Язык обёртки контекста: только API < 33 и только явный выбор; иначе null (без обёртки). */
    fun wrapLocale(sdk: Int, stored: String?): Locale? {
        if (sdk >= 33) return null
        val c = LangChoice.of(stored)
        return if (c == LangChoice.SYSTEM) null else Locale.forLanguageTag(c.tag)
    }
}

/** Применение выбора языка. Всё — главный поток. */
object Lang {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(LangPrefs.PREFS, Context.MODE_PRIVATE)

    fun stored(ctx: Context): String? = prefs(ctx).getString(LangPrefs.KEY, null)

    fun choice(ctx: Context): LangChoice = LangPrefs.effective(Build.VERSION.SDK_INT,
        if (Build.VERSION.SDK_INT >= 33) ctx.getSystemService(LocaleManager::class.java)?.applicationLocales?.toLanguageTags() else null,
        stored(ctx))

    /** Для тестов: масштаб шрифта экранов, создаваемых дальше (null — системный). */
    @Volatile var fontScale: Float? = null

    /**
     * attachBaseContext каждой Activity: на API 30–32 ресурсы в выбранном языке, на API 30 — и в
     * выбранной теме (ночные биты uiMode; на 31+ тему ставит UiModeManager).
     */
    fun wrap(base: Context): Context {
        val scale = fontScale
        val loc = LangPrefs.wrapLocale(Build.VERSION.SDK_INT, stored(base))
        val night = ThemePrefs.wrapNight(Build.VERSION.SDK_INT, Theme.stored(base))
        if (scale == null && night == null) return if (loc == null) base else withLocale(base, loc)
        val cfg = Configuration(base.resources.configuration)
        if (loc != null) cfg.setLocales(LocaleList(loc))
        if (scale != null) cfg.fontScale = scale
        if (night != null) cfg.uiMode = ThemePrefs.withNight(cfg.uiMode, night)
        return base.createConfigurationContext(cfg)
    }

    fun withLocale(base: Context, loc: Locale): Context {
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocales(LocaleList(loc))
        return base.createConfigurationContext(cfg)
    }

    /**
     * Запомнить и применить [c]. API 33+: LocaleManager — система сама пересоздаёт Activity
     * (и видит выбор в своих настройках); API 30–32: пересоздаётся [a], остальные экраны —
     * в своём onResume ([LangActivity]). Дерево и путь живут в Holder и Bundle.
     */
    fun set(a: Activity, c: LangChoice) {
        prefs(a).edit().putString(LangPrefs.KEY, LangPrefs.store(c)).commit()
        if (Build.VERSION.SDK_INT >= 33) {
            a.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (c == LangChoice.SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(c.tag)
        } else {
            a.recreate()
        }
    }

    /** Диалог «Системный / English / Русский» (пункт меню «···»; звук и вибрация — свой пункт). */
    fun ask(a: Activity): AlertDialog {
        val all = LangChoice.entries
        val cur = choice(a)
        return AlertDialog.Builder(a, R.style.Theme_Ancdu_Alert)
            .setTitle(a.tx.s(R.string.lang_title))
            .setSingleChoiceItems(all.map { a.tx.s(it.label) }.toTypedArray(), all.indexOf(cur)) { d, which ->
                d.dismiss()
                if (all[which] != cur) set(a, all[which])
            }
            .show()
    }

    /** Диалог «Звук и вибрация: Как в системе / Вкл / Выкл». */
    fun askFx(a: Activity): AlertDialog {
        val all = FxMode.entries
        val cur = Feedback.mode
        return AlertDialog.Builder(a, R.style.Theme_Ancdu_Alert)
            .setTitle(a.tx.s(R.string.fx_title))
            .setSingleChoiceItems(all.map { a.tx.s(it.label) }.toTypedArray(), all.indexOf(cur)) { d, which ->
                d.dismiss()
                FxPrefs.set(a, all[which])
            }
            .show()
    }
}

/**
 * Activity приложения: на API 30–32 ресурсы в выбранном языке ([Lang.wrap]), на API 30 — и в
 * выбранной теме; язык или тему сменили на другом экране — пересоздаётся при возврате. Палитра
 * [C.p] — по ночному режиму конфигурации экрана. Смена ночного режима системы пересоздаёт экран
 * (uiMode нет в configChanges манифеста).
 */
abstract class LangActivity : Activity() {
    private var lang: String? = null
    /** API 30: выбор темы, с которым построен экран (сменили на другом — пересоздаётся в onResume). */
    private var themeKey: String? = null
    /** onResume этого экземпляра уже вызвал recreate(): подклассы второй раз не пересоздают. */
    protected var relaunching = false
        private set

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    /** Палитра [C.p] — по ночному режиму конфигурации ЭТОГО экрана (до построения его view). */
    private fun applyPalette() { C.p = Palette.of(resources.configuration.isNightModeActive) }

    override fun onCreate(savedInstanceState: Bundle?) {
        lang = Lang.stored(this)
        themeKey = Theme.stored(this)
        applyPalette()
        super.onCreate(savedInstanceState)
        Feedback.init(this)
        Growth.init(this)
        if (Build.VERSION.SDK_INT >= 34) {
            Motion.transition(open = true).let { (e, x) -> overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, e, x) }
            Motion.transition(open = false).let { (e, x) -> overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, e, x) }
        }
    }

    /** API 30–33: в этом же сообщении главного потока запущен другой экран ([startActivity]). */
    private var justStarted = false
    private val clearStarted = Runnable { justStarted = false }
    private val starts = Handler(Looper.getMainLooper())

    /** API 30–33: переход открытия — сразу после запуска (на 34+ — overrideActivityTransition). */
    override fun startActivity(intent: Intent, options: Bundle?) {
        super.startActivity(intent, options)
        if (Build.VERSION.SDK_INT < 34) {
            pending(open = true)
            justStarted = true
            starts.removeCallbacks(clearStarted)
            starts.post(clearStarted)
        }
    }

    /**
     * API 30–33: переход закрытия. Сразу после [startActivity] (ScanActivity → Browser) последний
     * overridePendingTransition задаёт общий переход — снова пара открытия, иначе браузер
     * появился бы без анимации.
     */
    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < 34) pending(open = justStarted)
    }

    @Suppress("DEPRECATION")
    private fun pending(open: Boolean) = Motion.transition(open).let { (e, x) -> overridePendingTransition(e, x) }

    override fun onResume() {
        // Экран под этим мог поставить свою палитру: отрисовка этого читает токены заново.
        applyPalette()
        super.onResume()
        val langChanged = Build.VERSION.SDK_INT < 33 && Lang.stored(this) != lang
        val themeChanged = Build.VERSION.SDK_INT < 31 && Theme.stored(this) != themeKey
        if (langChanged || themeChanged) { relaunching = true; recreate() }
    }
}
