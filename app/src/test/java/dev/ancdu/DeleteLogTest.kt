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
        assertEquals(11, line.split('\t').size)
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

    @Test fun trimsToCapAtomically() {
        val s = store()
        for (k in 1..DeleteLogStore.CAP.toLong()) s.append(LogRec.Seen(k))
        assertEquals(DeleteLogStore.CAP, s.lines().size)
        s.append(LogRec.Seen(9999))
        val lines = s.lines()
        assertEquals(DeleteLogStore.CAP, lines.size)
        assertEquals(LogRec.Seen(2).format(), lines.first())               // the oldest went
        assertEquals(LogRec.Seen(9999).format(), lines.last())
        assertFalse(File(s.file.path + ".tmp").exists())
        // Only the log and its tmp are in the folder.
        assertEquals(listOf("deletes.tsv"), tmp.root.list()!!.sorted())
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
