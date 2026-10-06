package dev.ancdu

import dev.ancdu.XmlTxt.Companion.EN
import dev.ancdu.XmlTxt.Companion.RU
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AppRowsTest {
    @Test fun segmentsSplitApkDataCache() {
        val a = AppStat("Telegram", "org.telegram.messenger", app = 100, data = 900, cache = 300)
        val row = Row()
        AppRows.fill(RU, a, max = 2000, sum = 4000, row = row)
        assertEquals(1000L, a.total)
        assertEquals("Telegram", row.name)
        assertEquals("org.telegram.messenger", row.sub)
        assertEquals(0.5f, row.bar)
        assertEquals("25%", row.pct)
        assertArrayEquals(floatArrayOf(0.1f, 0.6f, 0.3f), row.segs, 1e-6f)
        assertEquals("Telegram, 1${Fmt.NBSP}000${Fmt.NBSP}Б: APK 100${Fmt.NBSP}Б, данные 600${Fmt.NBSP}Б, кэш 300${Fmt.NBSP}Б", row.desc)
        AppRows.fill(EN, a, max = 2000, sum = 4000, row = row)
        assertEquals("Telegram, 1,000${Fmt.NBSP}B: APK 100${Fmt.NBSP}B, data 600${Fmt.NBSP}B, cache 300${Fmt.NBSP}B", row.desc)
    }

    @Test fun zeroTotalIsSafe() {
        val row = Row()
        AppRows.fill(EN, AppStat("x", "p", 0, 0, 0), max = 0, sum = 0, row = row)
        assertEquals(0f, row.bar)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), row.segs, 0f)
    }
}
