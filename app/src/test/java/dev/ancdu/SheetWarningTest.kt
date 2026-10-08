package dev.ancdu

import dev.ancdu.XmlTxt.Companion.EN
import dev.ancdu.XmlTxt.Companion.RU
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The one warning line of the delete sheet: consequences named only for media and app data. */
class SheetWarningTest {
    private val N = Fmt.NBSP
    private val gib = 1L shl 30
    private val mib = 1L shl 20
    private fun g(v: Double) = (v * gib).toLong()

    private val media = Tag(TagKind.MEDIA).resolve(EN, null)
    private val dl = Tag(TagKind.DL).resolve(EN, null)
    private val cache = Tag(TagKind.CACHE, "org.telegram.messenger").resolve(EN, "Telegram")
    private fun app(pkg: String, label: String?) = Tag(TagKind.APP, pkg).resolve(EN, label)

    @Test fun mockupCase() {
        val risks = listOfNotNull(
            SheetWarning.risk(media, "VID_20261007_201455.mp4", false, g(3.8)),
            SheetWarning.risk(dl, "ggml-large-v3.bin", false, g(2.9)),
            SheetWarning.risk(cache, "cache", true, g(1.1)),
            SheetWarning.risk(app("com.whatsapp", "WhatsApp"), "files", true, g(0.6)))
        assertEquals(2, risks.size)
        assertEquals("⚠ Без корзины. Не вернуть: видео 3,8${N}ГиБ и данные WhatsApp 614,4${N}МиБ.",
            SheetWarning.text(RU, false, risks))
        assertEquals("⚠ No trash. Gone for good: video 3.8${N}GiB and WhatsApp data 614.4${N}MiB.",
            SheetWarning.text(EN, false, risks))
    }

    @Test fun nothingIrreversibleFallsBack() {
        assertEquals("⚠ Без корзины. Отменить нельзя.", SheetWarning.text(RU, false, emptyList()))
        assertEquals("⚠ No trash. This can't be undone.", SheetWarning.text(EN, false, emptyList()))
        // Cache and downloads are not mentioned.
        assertNull(SheetWarning.risk(dl, "a.bin", false, 10))
        assertNull(SheetWarning.risk(cache, "cache", true, 10))
        assertNull(SheetWarning.risk(null, "a.bin", false, 10))
        assertNull(SheetWarning.risk(Tag(TagKind.SYS).resolve(EN, null), "x", true, 10))
    }

    @Test fun rootPrefix() {
        assertEquals("⚠ Удаление от root · Без корзины. Отменить нельзя.", SheetWarning.text(RU, true, emptyList()))
        assertEquals("⚠ Deleting as root · No trash. This can't be undone.", SheetWarning.text(EN, true, emptyList()))
        val r = listOfNotNull(SheetWarning.risk(media, "IMG_1.jpg", false, 5 * mib))
        assertEquals("⚠ Удаление от root · Без корзины. Не вернуть: фото 5,0${N}МиБ.", SheetWarning.text(RU, true, r))
    }

    @Test fun mediaKinds() {
        fun one(name: String, dir: Boolean = false) = SheetWarning.text(RU, false, listOfNotNull(SheetWarning.risk(media, name, dir, gib)))
        assertEquals("⚠ Без корзины. Не вернуть: фото 1,0${N}ГиБ.", one("a.HEIC"))
        assertEquals("⚠ Без корзины. Не вернуть: аудио 1,0${N}ГиБ.", one("a.opus"))
        assertEquals("⚠ Без корзины. Не вернуть: видео 1,0${N}ГиБ.", one("a.mkv"))
        // A media folder (DCIM/) or mixed kinds: «медиа».
        assertEquals("⚠ Без корзины. Не вернуть: медиа 1,0${N}ГиБ.", one("Camera", dir = true))
        val mixed = listOfNotNull(SheetWarning.risk(media, "a.jpg", false, gib), SheetWarning.risk(media, "b.mp4", false, gib))
        assertEquals("⚠ Без корзины. Не вернуть: медиа 2,0${N}ГиБ.", SheetWarning.text(RU, false, mixed))
        assertEquals("⚠ No trash. Gone for good: media 2.0${N}GiB.", SheetWarning.text(EN, false, mixed))
        val photos = listOfNotNull(SheetWarning.risk(media, "a.jpg", false, gib), SheetWarning.risk(media, "b.png", false, gib))
        assertEquals("⚠ No trash. Gone for good: photos 2.0${N}GiB.", SheetWarning.text(EN, false, photos))
    }

    /** Several apps: RU plurals «данные N приложения / приложений». */
    @Test fun appsPlurals() {
        fun apps(n: Int) = (1..n).mapNotNull { SheetWarning.risk(app("com.a$it", "A$it"), "files", true, 100 * mib) }
        assertEquals("⚠ Без корзины. Не вернуть: данные 2 приложений 200,0${N}МиБ.", SheetWarning.text(RU, false, apps(2)))
        assertEquals("⚠ Без корзины. Не вернуть: данные 5 приложений 500,0${N}МиБ.", SheetWarning.text(RU, false, apps(5)))
        assertEquals("⚠ Без корзины. Не вернуть: данные 21 приложения 2,1${N}ГиБ.", SheetWarning.text(RU, false, apps(21)))
        assertEquals("⚠ No trash. Gone for good: data of 2 apps 200.0${N}MiB.", SheetWarning.text(EN, false, apps(2)))
        // The same app twice is one app; without a label — the package name.
        val same = listOfNotNull(SheetWarning.risk(app("com.whatsapp", "WhatsApp"), "a", true, gib),
            SheetWarning.risk(app("com.whatsapp", "WhatsApp"), "b", true, gib))
        assertEquals("⚠ Без корзины. Не вернуть: данные WhatsApp 2,0${N}ГиБ.", SheetWarning.text(RU, false, same))
        val bare = listOfNotNull(SheetWarning.risk(app("com.x", null), "a", true, gib))
        assertEquals("⚠ Без корзины. Не вернуть: данные com.x 1,0${N}ГиБ.", SheetWarning.text(RU, false, bare))
    }

    /** A hard link folds into the one warning line instead of a second amber line. */
    @Test fun hardlinkFolds() {
        assertEquals("⚠ Без корзины. Жёсткая ссылка — место может не освободиться.",
            SheetWarning.text(RU, false, emptyList(), hardlink = true))
        assertEquals("⚠ No trash. Hard link — space may not be freed.", SheetWarning.text(EN, false, emptyList(), hardlink = true))
        val r = listOfNotNull(SheetWarning.risk(media, "a.mp4", false, gib))
        assertEquals("⚠ Без корзины. Не вернуть: видео 1,0${N}ГиБ. Жёсткая ссылка — место может не освободиться.",
            SheetWarning.text(RU, false, r, hardlink = true))
        assertEquals("⚠ Удаление от root · Без корзины. Жёсткая ссылка — место может не освободиться.",
            SheetWarning.text(RU, true, emptyList(), hardlink = true))
    }
}
