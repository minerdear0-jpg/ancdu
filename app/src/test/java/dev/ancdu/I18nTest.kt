package dev.ancdu

import dev.ancdu.XmlTxt.Companion.EN
import dev.ancdu.XmlTxt.Companion.RU
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** Выбор языка: где он живёт и что из него следует (API 33+ — LocaleManager, 30–32 — prefs). */
class LangPrefsTest {
    @Test fun choiceFromTags() {
        assertEquals(LangChoice.SYSTEM, LangChoice.of(null))
        assertEquals(LangChoice.SYSTEM, LangChoice.of(""))
        assertEquals(LangChoice.EN, LangChoice.of("en"))
        assertEquals(LangChoice.RU, LangChoice.of("ru-RU,en"))
        assertEquals(LangChoice.RU, LangChoice.of("RU"))
        assertEquals(LangChoice.SYSTEM, LangChoice.of("de-DE"))     // незнакомый — как в системе
    }

    @Test fun storedRoundTrip() {
        for (c in LangChoice.entries) assertEquals(c, LangChoice.of(LangPrefs.store(c)))
        assertEquals("", LangPrefs.store(LangChoice.SYSTEM))
    }

    @Test fun effectiveChoice() {
        // 33+: правда — язык приложения в системе (его меняют и системные настройки)
        assertEquals(LangChoice.EN, LangPrefs.effective(34, "en", stored = "ru"))
        assertEquals(LangChoice.SYSTEM, LangPrefs.effective(33, "", stored = "ru"))
        // 30–32: prefs
        assertEquals(LangChoice.RU, LangPrefs.effective(30, null, stored = "ru"))
        assertEquals(LangChoice.SYSTEM, LangPrefs.effective(32, null, stored = null))
    }

    @Test fun wrapOnlyBelow33AndOnlyExplicit() {
        assertNull(LangPrefs.wrapLocale(34, "ru"))
        assertNull(LangPrefs.wrapLocale(30, null))
        assertNull(LangPrefs.wrapLocale(30, ""))
        assertEquals(Locale.forLanguageTag("ru"), LangPrefs.wrapLocale(30, "ru"))
        assertEquals(Locale.forLanguageTag("en"), LangPrefs.wrapLocale(32, "en"))
    }

    /** Список языков — в locales_config.xml (системные настройки «Язык приложения»). */
    @Test fun localeConfigListsBoth() {
        val xml = File("src/main/res/xml/locales_config.xml").readText()
        for (c in LangChoice.entries.filter { it.tag.isNotEmpty() })
            assertTrue(c.tag, xml.contains("android:name=\"${c.tag}\""))
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("android:localeConfig=\"@xml/locales_config\""))
    }

    @Test fun languageNames() {
        assertEquals(listOf("Как в системе", "English", "Русский"), LangChoice.entries.map { RU.s(it.label) })
        assertEquals(listOf("System", "English", "Русский"), LangChoice.entries.map { EN.s(it.label) })
    }
}

/** Тексты ядра — только по виду; сырой текст пользователю не показывается. */
class NativeErrTest {
    @Test fun mapsKinds() {
        assertEquals("Can't open /data", NativeErr.text(EN, ScanFail(0, "cannot open /data")))
        assertEquals("Не удалось открыть /data", NativeErr.text(RU, ScanFail(0, "cannot open /data")))
        assertEquals("The root helper did not start", NativeErr.text(EN, ScanFail(0, "helper failed to start: No such file")))
        assertEquals("Root-помощник завершился с кодом 1", NativeErr.text(RU, ScanFail(0, "helper failed (exit 1)")))
        assertEquals("Код ошибки -12", NativeErr.text(RU, ScanFail(-12)))
        assertEquals("unknown error", NativeErr.text(EN, ScanFail(0, "")))
        // строка stderr хелпера/su — не показывается как есть
        assertEquals("неизвестная ошибка", NativeErr.text(RU, ScanFail(0, "arena: Out of memory")))
    }
}

/** Browser badge: muted age «скан 2 ч назад» only when >= 1 h; no duration, no clock time. */
class BadgeTest {
    private val now = 1_791_500_000_000L
    private val hour = 3_600_000L
    private val N = Fmt.NBSP

    @Test fun ageOnlyFromOneHour() {
        assertEquals("", Badge.text(RU, Kind.SCAN, now - 59 * 60_000, false, now))
        assertEquals("скан 1${N}ч назад", Badge.text(RU, Kind.SCAN, now - hour, false, now))
        assertEquals("скан 2${N}ч назад", Badge.text(RU, Kind.SCAN, now - 2 * hour - 5, false, now))
        assertEquals("scan 2${N}h ago", Badge.text(EN, Kind.SCAN, now - 2 * hour, false, now))
        assertEquals("скан 3 дня назад", Badge.text(RU, Kind.SCAN, now - 72 * hour, false, now))
        // Unknown time: no age.
        assertEquals("", Badge.text(RU, Kind.SCAN, 0, false, now))
    }

    @Test fun kindsStayFacts() {
        assertEquals("root", Badge.text(RU, Kind.ROOT, now, false, now))
        assertEquals("root · скан 2${N}ч назад", Badge.text(RU, Kind.ROOT, now - 2 * hour, false, now))
        assertEquals("индекс · приблизительно", Badge.text(RU, Kind.INDEX, 0, false, now))
        assertEquals("кэш", Badge.text(RU, Kind.CACHE, now - 60_000, false, now))
        assertEquals("кэш · 10${N}ч назад", Badge.text(RU, Kind.CACHE, now - 10 * hour, false, now))
        assertEquals("cache · 10${N}h ago", Badge.text(EN, Kind.CACHE, now - 10 * hour, false, now))
        assertEquals("неполный", Badge.text(RU, Kind.SCAN, now, true, now))
        assertEquals("скан 2${N}ч назад · неполный", Badge.text(RU, Kind.SCAN, now - 2 * hour, true, now))
    }
}

/** Оба языка — одинаковые ключи и одинаковые аргументы формата. */
class ResourcesParityTest {
    private val fmt = Regex("%(\\d+\\$)?[sd]")

    @Test fun sameKeysAndArgs() {
        val en = File("src/main/res/values/strings.xml").readText()
        val ru = File("src/main/res/values-ru/strings.xml").readText()
        val item = Regex("<string name=\"([^\"]+)\"( translatable=\"false\")?>([^<]*)</string>")
        val enMap = item.findAll(en).filter { it.groupValues[2].isEmpty() }.associate { it.groupValues[1] to it.groupValues[3] }
        val ruMap = item.findAll(ru).associate { it.groupValues[1] to it.groupValues[3] }
        assertEquals(enMap.keys.sorted(), ruMap.keys.sorted())
        for ((k, v) in enMap) assertEquals(k, fmt.findAll(v).map { it.value }.toSet(), fmt.findAll(ruMap.getValue(k)).map { it.value }.toSet())
    }
}
