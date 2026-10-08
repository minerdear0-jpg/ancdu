package dev.ancdu

import dev.ancdu.XmlTxt.Companion.EN
import dev.ancdu.XmlTxt.Companion.RU
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** One status-line slot: interrupted > stale >= 24 h > growth > nothing (UI BUDGET 4). */
class StatusLineTest {
    private val N = Fmt.NBSP
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private val gib = 1L shl 30
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_791_500_000_000L
    /** 1 Oct 2026, 09:12 UTC. */
    private val oct1 = 1_790_845_920_000L
    private val dl = listOf("Download".toByteArray())
    private val cut = InterruptedDelete(id = 7, root = Scans.STORAGE, su = false, names = dl, dir = true,
        disk = (10.3 * gib).toLong(), time = now - hour)
    private val grew = HomeGrowth((2.1 * gib).toLong(), oct1, dl, "Download/")

    private fun pick(interrupted: InterruptedDelete? = null, running: Boolean = false, tree: Long? = now - hour,
                     approx: Boolean = false, growth: HomeGrowth? = null) =
        StatusLine.pick(interrupted, running, tree, approx, growth, now)

    @Test fun priorityInterruptedFirst() {
        val s = pick(interrupted = cut, tree = now - 3 * day, growth = grew)
        assertTrue(s is Status.Interrupted)
        assertSame(cut, (s as Status.Interrupted).d)
        // Even while a scan runs: the notice never times out.
        assertTrue(pick(interrupted = cut, running = true) is Status.Interrupted)
    }

    @Test fun staleBeatsGrowth() {
        assertTrue(pick(tree = now - day, growth = grew) is Status.Stale)
        assertTrue(pick(tree = now - 3 * day) is Status.Stale)
        // Just under 24 h: growth (or nothing).
        assertTrue(pick(tree = now - day + 1, growth = grew) is Status.Grew)
        assertSame(Status.None, pick(tree = now - day + 1))
    }

    @Test fun noTreeIsStaleUnlessRunning() {
        val s = pick(tree = null)
        assertTrue(s is Status.Stale)
        assertNull((s as Status.Stale).ageMs)
        // A refresh is already running: nothing to ask for.
        assertSame(Status.None, pick(tree = null, running = true))
        assertSame(Status.None, pick(tree = now - 3 * day, running = true))
        assertTrue(pick(tree = now - 3 * day, running = true, growth = grew) is Status.Grew)
        // The future (clock moved back) is not stale.
        assertSame(Status.None, pick(tree = now + day))
    }

    @Test fun approximateIsStale() {
        val s = pick(tree = null, approx = true) as Status.Stale
        assertTrue(s.approx)
    }

    @Test fun defaultIsSilent() {
        assertSame(Status.None, pick())
        assertNull(StatusLine.text(RU, Status.None, now, wide = true))
    }

    @Test fun textsRu() {
        assertEquals("⚠ прервано: Download/ · 10,3${N}ГиБ ›", StatusLine.text(RU, Status.Interrupted(cut), now, true))
        val part = InterruptedDelete(7, Scans.STORAGE, false, dl, true, (10.3 * gib).toLong(), now, freed = (4.8 * gib).toLong())
        assertEquals("⚠ прервано: Download/ · 4,8 из 10,3${N}ГиБ ›", StatusLine.text(RU, Status.Interrupted(part), now, true))
        assertEquals("скан 3 дня назад · обновить ›", StatusLine.text(RU, Status.Stale(3 * day, false), now, true))
        assertEquals("скан 1 день назад · обновить ›", StatusLine.text(RU, Status.Stale(day + 5, false), now, true))
        assertEquals("обновить ›", StatusLine.text(RU, Status.Stale(null, false), now, true))
        assertEquals("приблизительно · обновить ›", StatusLine.text(RU, Status.Stale(null, true), now, true))
        assertEquals("+2,1${N}ГиБ с 1 окт. · больше всего Download/ ›", StatusLine.text(RU, Status.Grew(grew), now, true, utc))
        // Would wrap: «больше всего» goes, the path stays.
        assertEquals("+2,1${N}ГиБ с 1 окт. · Download/ ›", StatusLine.text(RU, Status.Grew(grew), now, false, utc))
        val root = HomeGrowth((2.1 * gib).toLong(), oct1, emptyList(), "")
        assertEquals("+2,1${N}ГиБ с 1 окт. ›", StatusLine.text(RU, Status.Grew(root), now, false, utc))
    }

    @Test fun textsEn() {
        assertEquals("⚠ interrupted: Download/ · 10.3${N}GiB ›", StatusLine.text(EN, Status.Interrupted(cut), now, true))
        assertEquals("scan 3 days ago · refresh ›", StatusLine.text(EN, Status.Stale(3 * day, false), now, true))
        assertEquals("+2.1${N}GiB since Oct 1 · Download/ ›", StatusLine.text(EN, Status.Grew(grew), now, false, utc))
    }

    /** A group start without an end: the folder, and «X из Y» once the rest is counted. */
    @Test fun interruptedGroup() {
        val g = InterruptedDelete(9, Scans.STORAGE, false, listOf("DCIM".toByteArray(), ".thumbnails".toByteArray()), true,
            (412.6 * (1 shl 20)).toLong(), now, count = 1204, items = listOf("a.jpg".toByteArray()))
        assertEquals("⚠ прервано: DCIM/.thumbnails/ · 412,6${N}МиБ ›", StatusLine.text(RU, Status.Interrupted(g), now, true))
        assertEquals("⚠ прервано: DCIM/.thumbnails/ · 380,1 из 412,6${N}МиБ ›",
            StatusLine.text(RU, Status.Interrupted(g.withFreed((380.1 * (1 shl 20)).toLong())), now, true))
        // 1 204 objects, 1 name recorded: the rest can't be counted from the tree.
        assertFalse(g.allNamed)
        assertTrue(InterruptedDelete(9, Scans.STORAGE, false, emptyList(), true, 1, now, count = 2,
            items = listOf("a".toByteArray(), "b".toByteArray())).allNamed)
        assertEquals(1204, g.withFreed(1).count)
    }

    @Test fun interruptedPathOfFileAndHostileName() {
        val f = InterruptedDelete(1, Scans.STORAGE, false, listOf("Download".toByteArray(), byteArrayOf(0x61, 0xFF.toByte())),
            dir = false, disk = 10, time = now)
        assertEquals("Download/a�", f.path)
        // The folder a tap opens: a file — its parent; a folder — itself.
        assertEquals(1, f.folder.size)
        assertEquals(1, cut.folder.size)
        assertEquals("", InterruptedDelete(1, Scans.STORAGE, false, emptyList(), true, 1, now).path)
    }

    @Test fun ofSameUnit() {
        assertEquals("4,8 из 10,3${N}ГиБ", Fmt.sizeOf((4.8 * gib).toLong(), (10.3 * gib).toLong(), RU))
        assertEquals("380,1 из 412,6${N}МиБ", Fmt.sizeOf((380.1 * (1 shl 20)).toLong(), (412.6 * (1 shl 20)).toLong(), RU))
        assertEquals("512,0${N}МиБ из 10,3${N}ГиБ", Fmt.sizeOf(512L shl 20, (10.3 * gib).toLong(), RU))
        assertEquals("4.8 of 10.3${N}GiB", Fmt.sizeOf((4.8 * gib).toLong(), (10.3 * gib).toLong(), EN))
    }
}

/** Main card arithmetic: «apps and system» and the storage-full rule. */
class HomeMathTest {
    private val gib = 1L shl 30

    @Test fun appsAndSystemIsDataUsedMinusShared() {
        // 82,0 GiB used on /data, 35,0 GiB shared storage: 47,0 GiB apps and system.
        assertEquals(47 * gib, HomeMath.appsBytes(82 * gib, 35 * gib))
        assertNull(HomeMath.appsBytes(82 * gib, null))
        assertNull(HomeMath.appsBytes(-1, 35 * gib))
        // Shared bigger than used (stale cache, other volume): no figure, never «0 Б».
        assertNull(HomeMath.appsBytes(30 * gib, 35 * gib))
        assertEquals(0L, HomeMath.appsBytes(35 * gib, 35 * gib))
    }

    @Test fun storageFullBelowOneGibOrFivePercent() {
        val total = 224 * gib
        assertTrue(HomeMath.storageFull(gib - 1, total))
        // 1 GiB is 0,4% of 224 GiB: still under 5%.
        assertTrue(HomeMath.storageFull(gib, total))
        assertTrue(HomeMath.storageFull((total + 19) / 20 - 1, total))
        assertFalse(HomeMath.storageFull((total + 19) / 20, total))
        assertFalse(HomeMath.storageFull(142 * gib, total))
        // A small volume: 5% is under 1 GiB, so 1 GiB rules.
        assertTrue(HomeMath.storageFull(gib - 1, 8 * gib))
        assertFalse(HomeMath.storageFull(gib, 8 * gib))
        // Unknown statfs: not full.
        assertFalse(HomeMath.storageFull(0, 0))
    }
}
