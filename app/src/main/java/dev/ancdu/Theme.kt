package dev.ancdu

import android.app.Activity
import android.app.AlertDialog
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build

/** Выбор темы: как в системе (по умолчанию), тёмная, светлая. [key] — значение в prefs. */
enum class ThemeChoice(val key: String, val label: Int) {
    SYSTEM("system", R.string.theme_system),
    DARK("dark", R.string.theme_dark),
    LIGHT("light", R.string.theme_light);

    companion object {
        /** Сохранённое значение; незнакомое или нет — SYSTEM. */
        fun of(stored: String?): ThemeChoice = entries.firstOrNull { it.key == stored } ?: SYSTEM
    }
}

/**
 * Чистый Kotlin: где живёт выбор темы и как он применяется. Выбор пишется в prefs «ui», ключ «theme».
 * API 31+: UiModeManager.setApplicationNightMode (система его хранит и сама пересоздаёт экраны);
 * API 30: ночные биты uiMode в обёртке контекста каждой Activity ([Lang.wrap]).
 */
object ThemePrefs {
    const val KEY = "theme"

    fun store(c: ThemeChoice): String = c.key

    /** Режим для UiModeManager.setApplicationNightMode (API 31+); AUTO — как в системе. */
    fun appNightMode(c: ThemeChoice): Int = when (c) {
        ThemeChoice.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
        ThemeChoice.DARK -> UiModeManager.MODE_NIGHT_YES
        ThemeChoice.LIGHT -> UiModeManager.MODE_NIGHT_NO
    }

    /** Ночные биты uiMode обёртки контекста: только API < 31 и только явный выбор; иначе null. */
    fun wrapNight(sdk: Int, stored: String?): Int? {
        if (sdk >= 31) return null
        return when (ThemeChoice.of(stored)) {
            ThemeChoice.SYSTEM -> null
            ThemeChoice.DARK -> Configuration.UI_MODE_NIGHT_YES
            ThemeChoice.LIGHT -> Configuration.UI_MODE_NIGHT_NO
        }
    }

    /** Ночной ли экран при выборе [c], когда у системы ночной режим [systemNight]. */
    fun night(c: ThemeChoice, systemNight: Boolean): Boolean = when (c) {
        ThemeChoice.SYSTEM -> systemNight
        ThemeChoice.DARK -> true
        ThemeChoice.LIGHT -> false
    }

    /**
     * API 31+: экран ночной [actualNight], а выбор [c] при системном [systemNight] ждёт другого —
     * режим приложения в системе разошёлся с prefs (восстановление из копии и т. п.).
     */
    fun needsReapply(c: ThemeChoice, systemNight: Boolean, actualNight: Boolean): Boolean =
        night(c, systemNight) != actualNight

    /** [uiMode] с ночными битами [night] (тип устройства не меняется). */
    fun withNight(uiMode: Int, night: Int): Int = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
}

/** Применение выбора темы. Всё — главный поток. */
object Theme {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(LangPrefs.PREFS, Context.MODE_PRIVATE)

    fun stored(ctx: Context): String? = prefs(ctx).getString(ThemePrefs.KEY, null)

    fun choice(ctx: Context): ThemeChoice = ThemeChoice.of(stored(ctx))

    /**
     * Запомнить и применить [c]. API 31+: UiModeManager — система пересоздаёт экраны, если ночной
     * режим приложения изменился; API 30: пересоздаётся [a], остальные экраны — в своём onResume
     * ([LangActivity]). Дерево и путь живут в Holder и Bundle.
     */
    fun set(a: Activity, c: ThemeChoice) {
        prefs(a).edit().putString(ThemePrefs.KEY, ThemePrefs.store(c)).commit()
        if (Build.VERSION.SDK_INT >= 31) {
            a.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(ThemePrefs.appNightMode(c))
        } else {
            a.recreate()
        }
    }

    /** Сверка уже сделана в этом процессе: повторно не переприменяет (нет цикла пересозданий). */
    private var reconciled = false

    /**
     * API 31+, onCreate главного экрана: prefs — правда. Публичного чтения режима приложения
     * (getApplicationNightMode) нет, поэтому сверяется итог: ночной ли [a] против ожидаемого по
     * выбору и ночному режиму системы (Resources.getSystem — без поправки приложения). Разошлись —
     * setApplicationNightMode(выбор) один раз на процесс: система пересоздаст экран, второй
     * onCreate сверку уже не делает, даже если режим так и не совпал.
     */
    fun reconcile(a: Activity) {
        if (Build.VERSION.SDK_INT < 31 || reconciled) return
        reconciled = true
        val c = choice(a)
        val systemNight = android.content.res.Resources.getSystem().configuration.isNightModeActive
        if (ThemePrefs.needsReapply(c, systemNight, a.resources.configuration.isNightModeActive))
            a.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(ThemePrefs.appNightMode(c))
    }

    /** Диалог «Тема: Как в системе / Тёмная / Светлая» (пункт меню «···»). */
    fun ask(a: Activity): AlertDialog {
        val all = ThemeChoice.entries
        val cur = choice(a)
        return AlertDialog.Builder(a, R.style.Theme_Ancdu_Alert)
            .setTitle(a.tx.s(R.string.theme_title))
            .setSingleChoiceItems(all.map { a.tx.s(it.label) }.toTypedArray(), all.indexOf(cur)) { d, which ->
                d.dismiss()
                if (all[which] != cur) set(a, all[which])
            }
            .show()
    }
}
