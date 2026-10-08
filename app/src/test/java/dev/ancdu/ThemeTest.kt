package dev.ancdu

import android.app.UiModeManager
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Выбор темы: хранение в prefs, режим UiModeManager (API 31+), ночные биты обёртки (API 30), палитра. */
class ThemeTest {
    @Test fun storedValueRoundTrips() {
        for (c in ThemeChoice.entries) assertEquals(c, ThemeChoice.of(ThemePrefs.store(c)))
        assertEquals(listOf("system", "dark", "light"), ThemeChoice.entries.map(ThemePrefs::store))
    }

    @Test fun unknownOrMissingIsSystem() {
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.of(null))
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.of(""))
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.of("sepia"))
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.entries.first())
    }

    @Test fun labelsInMenuOrder() {
        assertEquals(listOf(R.string.theme_system, R.string.theme_dark, R.string.theme_light), ThemeChoice.entries.map { it.label })
        assertEquals("Theme: System", XmlTxt.EN.s(R.string.theme_item, XmlTxt.EN.s(R.string.theme_system)))
        assertEquals("Тема: Как в системе", XmlTxt.RU.s(R.string.theme_item, XmlTxt.RU.s(R.string.theme_system)))
        assertEquals(listOf("Как в системе", "Тёмная", "Светлая"), ThemeChoice.entries.map { XmlTxt.RU.s(it.label) })
        assertEquals(listOf("System", "Dark", "Light"), ThemeChoice.entries.map { XmlTxt.EN.s(it.label) })
    }

    /** API 31+: UiModeManager.setApplicationNightMode — AUTO (как в системе), YES, NO. */
    @Test fun appNightMode() {
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, ThemePrefs.appNightMode(ThemeChoice.SYSTEM))
        assertEquals(UiModeManager.MODE_NIGHT_YES, ThemePrefs.appNightMode(ThemeChoice.DARK))
        assertEquals(UiModeManager.MODE_NIGHT_NO, ThemePrefs.appNightMode(ThemeChoice.LIGHT))
    }

    /** API 30: ночные биты uiMode в обёртке контекста; системный выбор и API 31+ — без обёртки. */
    @Test fun wrapNightOnlyBelow31AndExplicit() {
        assertEquals(Configuration.UI_MODE_NIGHT_YES, ThemePrefs.wrapNight(30, "dark"))
        assertEquals(Configuration.UI_MODE_NIGHT_NO, ThemePrefs.wrapNight(30, "light"))
        assertNull(ThemePrefs.wrapNight(30, "system"))
        assertNull(ThemePrefs.wrapNight(30, null))
        assertNull(ThemePrefs.wrapNight(31, "dark"))
        assertNull(ThemePrefs.wrapNight(34, "light"))
    }

    /** Биты обёртки заменяют только ночные биты uiMode, тип устройства остаётся. */
    @Test fun withNightKeepsUiModeType() {
        val car = Configuration.UI_MODE_TYPE_CAR
        assertEquals(car or Configuration.UI_MODE_NIGHT_NO,
            ThemePrefs.withNight(car or Configuration.UI_MODE_NIGHT_YES, Configuration.UI_MODE_NIGHT_NO))
        assertEquals(car or Configuration.UI_MODE_NIGHT_YES,
            ThemePrefs.withNight(car or Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES))
    }

    /** Палитра — по ночному режиму конфигурации экрана. */
    @Test fun paletteForNightMode() {
        assertEquals(Palette.DARK, Palette.of(night = true))
        assertEquals(Palette.LIGHT, Palette.of(night = false))
    }
}
