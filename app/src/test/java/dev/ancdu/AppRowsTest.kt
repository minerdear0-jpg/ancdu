package dev.ancdu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AppRowsTest {
    @Test fun segmentsSplitApkDataCache() {
        val a = AppStat("Telegram", "org.telegram.messenger", app = 100, data = 900, cache = 300)
        val row = Row()
        AppRows.fill(a, max = 2000, sum = 4000, row = row)
        assertEquals(1000L, a.total)
        assertEquals("Telegram", row.name)
        assertEquals("org.telegram.messenger", row.sub)
        assertEquals(0.5f, row.bar)
        assertEquals("25%", row.pct)
        assertArrayEquals(floatArrayOf(0.1f, 0.6f, 0.3f), row.segs, 1e-6f)
    }

    @Test fun zeroTotalIsSafe() {
        val row = Row()
        AppRows.fill(AppStat("x", "p", 0, 0, 0), max = 0, sum = 0, row = row)
        assertEquals(0f, row.bar)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), row.segs, 0f)
    }
}
