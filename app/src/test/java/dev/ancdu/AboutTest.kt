package dev.ancdu

import dev.ancdu.XmlTxt.Companion.EN
import dev.ancdu.XmlTxt.Companion.RU
import org.junit.Assert.assertEquals
import org.junit.Test

/** «О приложении» и пункты меню «⋯». */
class AboutTest {
    @Test fun fingerprint() {
        assertEquals("", About.hex(ByteArray(0)))
        assertEquals("00:0A:FF", About.hex(byteArrayOf(0, 10, -1)))
        // SHA-256 сертификата: 32 байта, 95 знаков
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest("cert".toByteArray())
        val s = About.sha256("cert".toByteArray())
        assertEquals(95, s.length)
        assertEquals(About.hex(sha), s)
    }

    @Test fun sourceIsTheRepository() {
        assertEquals("https://github.com/minerdear0-jpg/ancdu", About.SOURCE)
    }

    @Test fun menuTexts() {
        assertEquals("Language: System", EN.s(R.string.menu_lang, EN.s(LangChoice.SYSTEM.label)))
        assertEquals("Язык: Как в системе", RU.s(R.string.menu_lang, RU.s(LangChoice.SYSTEM.label)))
        assertEquals("Sound & haptics: System", EN.s(R.string.fx_item, EN.s(FxMode.SYSTEM.label)))
        assertEquals("Звук и вибрация: Как в системе", RU.s(R.string.fx_item, RU.s(FxMode.SYSTEM.label)))
        assertEquals("About", EN.s(R.string.about))
        assertEquals("О приложении", RU.s(R.string.about))
        assertEquals("Menu", EN.s(R.string.menu))
        assertEquals("Меню", RU.s(R.string.menu))
        assertEquals("Version 1.1.1", EN.s(R.string.about_version, "1.1.1"))
        assertEquals("Версия 1.1.1", RU.s(R.string.about_version, "1.1.1"))
    }
}
