package dev.ancdu

import dev.ancdu.XmlTxt.Companion.EN
import dev.ancdu.XmlTxt.Companion.RU
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

/** Delete log sheet rows: date only on a day's first row, default «deleted» silent, deviations get ⚠. */
class LogRowsTest {
    private val N = Fmt.NBSP
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private val mib = 1L shl 20
    private val gib = 1L shl 30
    /** 8 Oct 2026 12:16 UTC. */
    private val oct8 = 1_791_461_760_000L

    private fun entry(id: Long, time: Long, names: List<String>, dir: Boolean, disk: Long, end: LogRec.End?,
                      freed: Long? = null, viaRoot: Boolean = false) =
        LogEntry(LogRec.Start(id, time, Scans.STORAGE, false, names.map { it.toByteArray() }, dir, 1, disk, viaRoot, false),
            end, freed, false)

    @Test fun dateOnlyOnTheFirstRowOfADay() {
        // Newest first: 8 Oct 12:16, 8 Oct 12:02, 7 Oct 23:40, 3 Oct 21:05.
        val times = listOf(oct8, oct8 - 14 * 60_000, oct8 - (12 * 60 + 36) * 60_000L, oct8 - (4 * 24 * 60 + 15 * 60 + 11) * 60_000L)
        assertEquals(listOf("8 окт. 12:16", "12:02", "7 окт. 23:40", "3 окт. 21:05"), LogRows.times(RU, times, utc))
        assertEquals(listOf("Oct 8 12:16", "12:02", "Oct 7 23:40", "Oct 3 21:05"), LogRows.times(EN, times, utc))
    }

    @Test fun sizeColumn() {
        val ok = entry(1, oct8, listOf("Download"), true, (10.3 * gib).toLong(), LogRec.End(1, oct8, 0, (10.3 * gib).toLong(), 136))
        assertEquals("10,3${N}ГиБ", LogRows.size(RU, ok))
        val part = entry(2, oct8, listOf("DCIM", ".thumbnails"), true, (412.6 * mib).toLong(),
            LogRec.End(2, oct8, -4, -1, 20), freed = (380.1 * mib).toLong())
        assertEquals("⚠ 380,1 из 412,6${N}МиБ", LogRows.size(RU, part))
        assertEquals("⚠ 380.1 of 412.6${N}MiB", LogRows.size(EN, part))
        val unknown = entry(3, oct8, listOf("x"), true, gib, LogRec.End(3, oct8, -13, -1, 5))
        assertEquals("⚠ частично", LogRows.size(RU, unknown))
        val cut = entry(4, oct8, listOf("Movies", "old-rips"), true, (2.4 * gib).toLong(), null)
        assertEquals("⚠ прервано", LogRows.size(RU, cut))
        assertEquals("⚠ interrupted", LogRows.size(EN, cut))
    }

    @Test fun pathColumn() {
        assertEquals("Download/", LogRows.path(entry(1, oct8, listOf("Download"), true, 1, null)))
        assertEquals("Download/a.bin", LogRows.path(entry(1, oct8, listOf("Download", "a.bin"), false, 1, null)))
        // The tree root itself (no names): its own path.
        assertEquals(Scans.STORAGE, LogRows.path(entry(1, oct8, emptyList(), true, 1, null)))
    }
}
