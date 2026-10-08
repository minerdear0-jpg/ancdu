package dev.ancdu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The delete log (filesDir/deletes.tsv): record format round-trip with hostile names, escaping,
 * trimming to 500 records, interrupted detection and clearing. Only the store file and its tmp in a
 * JUnit temp folder are touched.
 */
class DeleteLogTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun b(s: String) = s.toByteArray(Charsets.UTF_8)
    private fun raw(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun store(): DeleteLogStore {
        val f = File(tmp.root, "deletes.tsv")
        assertTrue(f.absolutePath.startsWith(tmp.root.absolutePath + "/"))
        return DeleteLogStore(f)
    }

    private fun start(id: Long, names: List<ByteArray> = listOf(b("Download")), time: Long = 1000L * id, dir: Boolean = true) =
        LogRec.Start(id, time, Scans.STORAGE, su = false, names = names, dir = dir, items = 136, disk = 10L shl 30,
            viaRoot = false, fast = false)

    @Test fun escapingKeepsUtf8AndEscapesTheRest() {
        assertEquals("Download", LogCodec.escape(b("Download")))
        assertEquals("ДЖОРДЖ.mp3", LogCodec.escape(b("ДЖОРДЖ.mp3")))
        assertEquals("a\\tb\\nc\\rd\\\\e", LogCodec.escape(b("a\tb\nc\rd\\e")))
        assertEquals("x\\x01\\x7F", LogCodec.escape(raw(0x78, 0x01, 0x7F)))
        // Invalid UTF-8: lone continuation, truncated sequence, overlong «/», surrogate, > U+10FFFF.
        assertEquals("\\x80", LogCodec.escape(raw(0x80)))
        assertEquals("\\xE2\\x82", LogCodec.escape(raw(0xE2, 0x82)))
        assertEquals("\\xC0\\xAF", LogCodec.escape(raw(0xC0, 0xAF)))
        assertEquals("\\xED\\xA0\\x80", LogCodec.escape(raw(0xED, 0xA0, 0x80)))
        assertEquals("\\xF4\\x90\\x80\\x80", LogCodec.escape(raw(0xF4, 0x90, 0x80, 0x80)))
        assertEquals("€😀", LogCodec.escape(b("€😀")))
    }

    @Test fun hostileNamesRoundTrip() {
        val hostile = listOf(b("a\tb"), b("new\nline"), b("back\\slash"), raw(0xFF, 0x61, 0xFE), b("\\x41"), b("😀"),
            raw(0x0D, 0x0A, 0x09), b("‮evil"))
        for (n in hostile) assertArrayEquals(n, LogCodec.unescape(LogCodec.escape(n)))
        val rec = start(42, hostile)
        val line = rec.format()
        assertFalse(line, line.contains('\n'))
        assertEquals(12, line.split('\t').size)
        val back = LogRec.parse(line) as LogRec.Start
        assertEquals(hostile.size, back.names.size)
        for (k in hostile.indices) assertArrayEquals(hostile[k], back.names[k])
        assertEquals(42L, back.id); assertEquals(Scans.STORAGE, back.root); assertTrue(back.dir)
        assertEquals(136L, back.items); assertEquals(10L shl 30, back.disk)
        // A path at the tree root (no names) and a root with a tab in it.
        val top = LogRec.Start(1, 2, "/odd\troot", true, emptyList(), false, 1, 5, true, true)
        val t2 = LogRec.parse(top.format()) as LogRec.Start
        assertEquals("/odd\troot", t2.root); assertTrue(t2.su); assertTrue(t2.viaRoot); assertTrue(t2.fast)
        assertTrue(t2.names.isEmpty())
        val end = LogRec.End(42, 9, -4, 380L shl 20, 77)
        val e2 = LogRec.parse(end.format()) as LogRec.End
        assertEquals(-4, e2.code); assertEquals(380L shl 20, e2.freed); assertEquals(77L, e2.removed)
        assertEquals(5L, (LogRec.parse(LogRec.Freed(42, 5).format()) as LogRec.Freed).bytes)
        assertEquals(42L, (LogRec.parse(LogRec.Seen(42).format()) as LogRec.Seen).id)
        // Garbage, a torn last line and unknown types are ignored.
        for (bad in listOf("", "X\t1", "S\t1\t2", "E\tx\t1\t0\t0\t0", "S\t1\t2\t0\t/r\t\\xZZ\td\t1\t1\t0\t0"))
            assertNull(bad, LogRec.parse(bad))
    }

    @Test fun appendAndReadEntries() {
        val s = store()
        s.append(start(1))
        s.append(LogRec.End(1, 1500, 0, 10L shl 30, 136))
        s.append(start(2, listOf(b("DCIM"), b(".thumbnails"))))
        s.append(LogRec.End(2, 2500, -4, -1, 20))
        s.append(LogRec.Freed(2, 380L shl 20))
        // A refusal: nothing deleted — not shown.
        s.append(start(3, listOf(b("Download"), b("product.img")), dir = false))
        s.append(LogRec.End(3, 3500, -116, -1, 0))
        s.append(start(4, listOf(b("Movies"), b("old-rips"))))
        val all = DeleteLogModel.entries(s.lines())
        assertEquals(listOf(1L, 2L, 3L, 4L), all.map { it.start.id })
        val shown = DeleteLogModel.shown(all)
        assertEquals(listOf(4L, 2L, 1L), shown.map { it.start.id })            // newest first, no refusal
        assertEquals(LogOutcome.DELETED, shown[2].outcome)
        assertEquals(LogOutcome.PARTIAL, shown[1].outcome)
        assertEquals(380L shl 20, shown[1].freed)
        assertEquals(LogOutcome.INTERRUPTED, shown[0].outcome)
    }

    @Test fun interruptedDetectionAndSeen() {
        val s = store()
        s.append(start(1)); s.append(LogRec.End(1, 1, 0, 1, 1))
        s.append(start(2, listOf(b("Download"))))
        assertEquals(listOf(2L), DeleteLogModel.interrupted(DeleteLogModel.entries(s.lines())).map { it.start.id })
        val n = DeleteLogModel.notice(DeleteLogModel.entries(s.lines()))!!
        assertEquals(2L, n.id); assertEquals("Download/", n.path); assertEquals(10L shl 30, n.disk); assertNull(n.freed)
        s.append(LogRec.Seen(2))
        assertNull(DeleteLogModel.notice(DeleteLogModel.entries(s.lines())))
        // Still listed as interrupted in the log itself.
        assertEquals(LogOutcome.INTERRUPTED, DeleteLogModel.shown(DeleteLogModel.entries(s.lines()))[0].outcome)
        // Two unseen: the newest is the notice.
        s.append(start(5)); s.append(start(6, listOf(b("Music"))))
        assertEquals(6L, DeleteLogModel.notice(DeleteLogModel.entries(s.lines()))!!.id)
    }

    /** Over 500 records: rewrite down to 400, so the fsync'd rewrite comes about once per 100 appends. */
    @Test fun trimsWithSlack() {
        val s = store()
        for (k in 1..DeleteLogStore.CAP.toLong()) s.append(LogRec.Seen(k))
        assertEquals(DeleteLogStore.CAP, s.lines().size)
        assertEquals(0, s.rewrites)
        s.append(LogRec.Seen(9999))
        val lines = s.lines()
        assertEquals(DeleteLogStore.KEEP, lines.size)
        assertEquals(LogRec.Seen(102).format(), lines.first())             // the oldest 101 went
        assertEquals(LogRec.Seen(9999).format(), lines.last())
        assertEquals(1, s.rewrites)
        // The next 100 appends are plain appends; the 101st rewrites again.
        for (k in 1..100L) s.append(LogRec.Seen(10_000 + k))
        assertEquals(1, s.rewrites)
        assertEquals(DeleteLogStore.CAP, s.lines().size)
        s.append(LogRec.Seen(20_000))
        assertEquals(2, s.rewrites)
        assertEquals(DeleteLogStore.KEEP, s.lines().size)
        assertFalse(File(s.file.path + ".tmp").exists())
        // Only the log and its tmp are in the folder.
        assertEquals(listOf("deletes.tsv"), tmp.root.list()!!.sorted())
    }

    /** The record count is read once, then kept in memory; a new store reads it once again. */
    @Test fun countBookkeeping() {
        val s = store()
        assertEquals(0, s.size())
        for (k in 1..7L) s.append(LogRec.Seen(k))
        assertEquals(7, s.size())
        assertEquals(7, s.lines().size)
        val again = DeleteLogStore(s.file)
        assertEquals(7, again.size())
        again.append(LogRec.Seen(8))
        assertEquals(8, again.size())
        again.clear()
        assertEquals(0, again.size())
        assertTrue(again.lines().isEmpty())
    }

    /** A group is ONE start and ONE end: folder, count, total, up to 20 names (largest first). */
    @Test fun groupIsOnePairOfRecords() {
        val folder = listOf(b("DCIM"), b(".thumbnails"))
        val objects = (1..1204).map { b("f$it.jpg") to it.toLong() * 1000 }
        val a = LogActions.group(Scans.STORAGE, false, folder, objects, items = 1300, viaRoot = false, fast = false)
        assertEquals(1204, a.count)
        assertEquals(objects.sumOf { it.second }, a.disk)
        assertEquals(LogActions.NAMES, a.itemNames.size)
        assertArrayEquals(b("f1204.jpg"), a.itemNames.first())             // largest first
        val start = LogActions.start(77, 1, a)
        val back = LogRec.parse(start.format()) as LogRec.Start
        assertTrue(back.group)
        assertEquals(1204, back.count)
        assertEquals(20, back.itemNames.size)
        for (k in 0 until 20) assertArrayEquals(a.itemNames[k], back.itemNames[k])
        assertEquals(2, back.names.size)
        // End: deleted / partial / failed counts and freed bytes.
        val results = objects.mapIndexed { i, (n, d) ->
            when {
                i < 1200 -> ItemResult(String(n), false, d, 0, 1)
                i == 1200 -> ItemResult(String(n), true, d, -4, 3)
                else -> ItemResult(String(n), false, d, -13, 0)
            }
        }
        val end = LogActions.end(77, 2, -4, results, total = 1204)
        val e2 = LogRec.parse(end.format()) as LogRec.End
        assertEquals(1200, e2.deleted); assertEquals(1, e2.partial); assertEquals(3, e2.failed)
        assertEquals(objects.take(1200).sumOf { it.second }, e2.freed)
        assertEquals(1203L, e2.removed)
        val entry = LogEntry(back, e2, null, false)
        assertEquals(LogOutcome.PARTIAL, entry.outcome)
        assertEquals(e2.freed, entry.freed)
        // All deleted: the default, silent.
        val ok = LogActions.end(78, 2, 0, objects.map { (n, d) -> ItemResult(String(n), false, d, 0, 1) }, 1204)
        assertEquals(LogOutcome.DELETED, LogEntry(back, ok, null, false).outcome)
        // A group where nothing was deleted is a refusal, not listed.
        val none = LogActions.end(79, 2, -116, objects.map { (n, d) -> ItemResult(String(n), false, d, -116, 0) }, 1204)
        assertTrue(LogEntry(back, none, null, false).refusal)
        // A group start without an end: interrupted, the notice names the folder and keeps count and names.
        val n = DeleteLogModel.notice(listOf(LogEntry(back, null, null, false)))!!
        assertEquals("DCIM/.thumbnails/", n.path); assertEquals(1204, n.count); assertEquals(20, n.items.size)
    }

    /** Micro-bench: a 1 200-object group costs exactly 2 appends and no rewrite. */
    @Test fun groupCostsTwoAppends() {
        val s = store()
        val objects = (1..1200).map { b("x$it") to 10L }
        val a = LogActions.group(Scans.STORAGE, false, listOf(b("Download")), objects, 1200, false, false)
        val t0 = System.nanoTime()
        s.append(LogActions.start(1, 1, a))
        s.append(LogActions.end(1, 2, 0, objects.map { (n, d) -> ItemResult(String(n), false, d, 0, 1) }, objects.size))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(2, s.appends)
        assertEquals(0, s.rewrites)
        assertEquals(2, s.lines().size)
        assertTrue("2 appends took $ms ms", ms < 1000)
    }

    /** Old 11-field starts and 6-field ends (round-1 records) still parse as single objects. */
    @Test fun oldRecordsParse() {
        val old = "S\t5\t6\t0\t/storage/emulated/0\tDownload\td\t1\t2\t0\t0"
        val st = LogRec.parse(old) as LogRec.Start
        assertEquals(1, st.count); assertFalse(st.group)
        val e = LogRec.parse("E\t5\t7\t-4\t-1\t3") as LogRec.End
        assertEquals(0, e.deleted); assertEquals(1, e.partial); assertEquals(0, e.failed)
        assertNull(LogRec.parse("S\t5\t6\t0\t/r\tx\td\t1\t2\t0\t0\t0"))        // count 0 is invalid
    }

    @Test fun tornLastLineDoesNotEatTheNextRecord() {
        val s = store()
        s.append(start(1))
        s.file.appendText("E\t1\t15")                                      // killed mid-write
        s.append(LogRec.End(1, 2, 0, 1, 1))
        val e = DeleteLogModel.entries(s.lines()).single()
        assertEquals(LogOutcome.DELETED, e.outcome)
    }

    /** Clear (user scope change): empty tmp then rename; a start with no end goes too. */
    @Test fun clearEmptiesTheLogAndTheNotice() {
        val s = store()
        s.append(start(1)); s.append(LogRec.End(1, 1, 0, 1, 1))
        s.append(start(2))                                                  // interrupted, unseen
        assertTrue(DeleteLogModel.notice(DeleteLogModel.entries(s.lines())) != null)
        s.clear()
        assertTrue(s.file.exists())
        assertEquals(0L, s.file.length())
        assertTrue(s.lines().isEmpty())
        assertNull(DeleteLogModel.notice(DeleteLogModel.entries(s.lines())))
        assertFalse(File(s.file.path + ".tmp").exists())
        assertEquals(listOf("deletes.tsv"), tmp.root.list()!!.sorted())
        // Clearing an absent log is fine and creates nothing else.
        val other = DeleteLogStore(File(tmp.root, "none.tsv"))
        other.clear()
        assertEquals(0L, other.file.length())
        assertTrue(DeleteLogModel.entries(store().lines()).isEmpty())
    }

    @Test fun missingFileReadsEmpty() {
        assertTrue(store().lines().isEmpty())
    }
}
