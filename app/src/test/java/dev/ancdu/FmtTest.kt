package dev.ancdu

import dev.ancdu.XmlTxt.Companion.EN
import dev.ancdu.XmlTxt.Companion.RU
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FmtTest {
    private val N = Fmt.NBSP

    @Test fun sizesEnglish() {
        assertEquals("0${N}B", Fmt.size(0, EN))
        assertEquals("512${N}B", Fmt.size(512, EN))
        assertEquals("1.0${N}KiB", Fmt.size(1024, EN))
        assertEquals("14.0${N}KiB", Fmt.size(14 * 1024, EN))
        assertEquals("530.5${N}MiB", Fmt.size((530.5 * (1L shl 20)).toLong(), EN))
        assertEquals("81.6${N}GiB", Fmt.size((81.6 * (1L shl 30)).toLong(), EN))
        assertEquals("1.5${N}TiB", Fmt.size(3L shl 39, EN))
        assertEquals("—", Fmt.size(-1, EN))
    }

    @Test fun sizesRussian() {
        assertEquals("512${N}Б", Fmt.size(512, RU))
        assertEquals("14,0${N}КиБ", Fmt.size(14 * 1024, RU))
        assertEquals("530,5${N}МиБ", Fmt.size((530.5 * (1L shl 20)).toLong(), RU))
        assertEquals("81,6${N}ГиБ", Fmt.size((81.6 * (1L shl 30)).toLong(), RU))
        assertEquals("1,5${N}ТиБ", Fmt.size(3L shl 39, RU))
        assertEquals("1,0${N}ПиБ", Fmt.size(1L shl 50, RU))
    }

    @Test fun percents() {
        assertEquals("48%", Fmt.pct(48, 100))
        assertEquals("<1%", Fmt.pct(1, 1000))
        assertEquals("0%", Fmt.pct(0, 1000))
        assertEquals("", Fmt.pct(5, 0))
        assertEquals("100%", Fmt.pct(7, 7))
    }

    @Test fun counts() {
        val ru = Locale.forLanguageTag("ru")
        assertEquals("0", Fmt.count(0, ru))
        assertEquals("999", Fmt.count(999, ru))
        assertEquals("4${N}964", Fmt.count(4964, ru))
        assertEquals("1${N}284${N}113", Fmt.count(1_284_113, ru))
        assertEquals("4,964", Fmt.count(4964, Locale.ENGLISH))
        assertEquals("1,284,113", Fmt.count(1_284_113, Locale.ENGLISH))
        assertEquals("1,000${N}B", Fmt.size(1000, EN))
        assertEquals("1${N}000${N}Б", Fmt.size(1000, RU))
    }

    @Test fun durations() {
        assertEquals("0.3${N}s", Fmt.secs(300, EN))
        assertEquals("6.1${N}s", Fmt.secs(6125, EN))
        assertEquals("0,3${N}с", Fmt.secs(300, RU))
        assertEquals("6,1${N}с", Fmt.secs(6125, RU))
        assertEquals("0.0${N}s", Fmt.secs(-5, EN))
        assertEquals("01:05.3", Fmt.timer(65_300, Locale.ENGLISH))
        assertEquals("01:05,3", Fmt.timer(65_300, Locale.forLanguageTag("ru")))
    }

    @Test fun pluralsRussian() {
        for ((n, w) in listOf(1L to "файл", 2L to "файла", 5L to "файлов", 11L to "файлов", 21L to "файл",
            22L to "файла", 112L to "файлов", 761L to "файл", 762L to "файла", 765L to "файлов"))
            assertEquals("$n $w", RU.q(R.plurals.files, n, n.toString()))
        assertEquals("63${N}761 эл.", RU.items(63_761))
        assertEquals("1 item", EN.items(1))
        assertEquals("2 items", EN.items(2))
        assertEquals("63,767 items", EN.items(63_767))
    }

    @Test fun quantityOfHugeCountsKeepsForm() {
        assertEquals(1_000_001, ResTxt.quantity(5_000_000_001L))
        assertEquals(1_000_000, ResTxt.quantity(4_000_000_000L))
        assertEquals(761, ResTxt.quantity(761))
    }
}
