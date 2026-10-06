package dev.ancdu

import org.junit.Assert.assertEquals
import org.junit.Test

class FmtTest {
    @Test fun sizes() {
        assertEquals("0 B", Fmt.size(0))
        assertEquals("512 B", Fmt.size(512))
        assertEquals("1.0 KiB", Fmt.size(1024))
        assertEquals("920.0 MiB", Fmt.size(920L shl 20))
        assertEquals("12.4 GiB", Fmt.size((12.4 * (1L shl 30)).toLong()))
        assertEquals("1.5 TiB", Fmt.size(3L shl 39))
        assertEquals("—", Fmt.size(-1))
    }

    @Test fun percents() {
        assertEquals("54%", Fmt.pct(54, 100))
        assertEquals("<1%", Fmt.pct(1, 1000))
        assertEquals("0%", Fmt.pct(0, 1000))
        assertEquals("", Fmt.pct(5, 0))
        assertEquals("100%", Fmt.pct(7, 7))
    }

    @Test fun counts() {
        assertEquals("0", Fmt.count(0))
        assertEquals("999", Fmt.count(999))
        assertEquals("1 284 113", Fmt.count(1_284_113))
    }
}
