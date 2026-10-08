package dev.ancdu

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Delete log UI. DESTRUCTIVE-TEST RULE: the only delete is of one file inside a fresh mkdtemp under
 * cacheDir (absolute path asserted). The user's filesDir/deletes.tsv is copied aside before each
 * test and put back after it, so the log this test writes and clears is never the user's.
 */
@RunWith(AndroidJUnit4::class)
class DeleteLogUiTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context get() = ins.targetContext
    private val log get() = File(ctx.filesDir, DeleteLog.FILE)
    /** The user's log, copied aside into its own mkdtemp under cacheDir. */
    private lateinit var keep: File
    private val saved get() = File(keep, DeleteLog.FILE)
    private var had = false
    private var dir: File? = null
    private val opened = ArrayList<android.app.Activity>()

    @Before fun keepUserLog() {
        drainIo()
        keep = java.nio.file.Files.createTempDirectory(ctx.cacheDir.toPath(), "dlogkeep").toFile()
        assertTrue(keep.isAbsolute && keep.path.startsWith(ctx.cacheDir.path + "/"))
        had = log.exists()
        if (had) log.copyTo(saved, overwrite = true)
        // Start empty: the store's own clear (empty tmp + rename), no unlink outside the sandbox.
        DeleteLogStore(log).clear()
        DeleteLog.init(ctx, force = true)
        drainIo()
    }

    @After fun restoreUserLog() {
        ins.runOnMainSync { for (a in opened) a.finish(); Holder.clear() }
        drainIo()
        if (had) saved.copyTo(log, overwrite = true) else DeleteLogStore(log).clear()
        DeleteLog.init(ctx, force = true)
        drainIo()
        if (keep.path.startsWith(ctx.cacheDir.path + "/")) keep.deleteRecursively()
        dir?.let { d -> if (d.path.startsWith(ctx.cacheDir.path + "/")) d.deleteRecursively() }
    }

    private fun drainIo() { Holder.io.submit {}.get(30, TimeUnit.SECONDS); ins.waitForIdleSync() }

    private fun waitFor(ms: Long = 10_000, ok: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            var r = false
            ins.runOnMainSync { r = ok() }
            if (r) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun sandbox(): File = java.nio.file.Files.createTempDirectory(ctx.cacheDir.toPath(), "dlog").toFile().also {
        assertTrue(it.isAbsolute && it.path.startsWith(ctx.cacheDir.path + "/"))
        dir = it
    }

    private fun browse(root: File): BrowserActivity {
        val h = Native.scanStart(root.path, true, 2, IntArray(1))
        val p = LongArray(6)
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            Native.progress(h, p)
            if (p[0] != ST_RUNNING.toLong() || System.currentTimeMillis() > deadline) break
            Thread.sleep(25)
        }
        ins.runOnMainSync { Holder.set(h, Kind.SCAN, root.path, false) }
        val a = ins.startActivitySync(Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        opened += a
        return a
    }

    private fun index(a: BrowserActivity, name: String): Int {
        var k = -1
        ins.runOnMainSync {
            val s = a.list.source!!
            k = (0 until s.count).firstOrNull { Row().also { r -> s.bind(it, r) }.name == name } ?: -1
        }
        return k
    }

    /** Delete one file, then the success footer «освобождено … · журнал ›» opens the log, which lists it. */
    private fun deleteOneAndOpenLog(): Pair<BrowserActivity, DeleteLogSheet> {
        val d = sandbox()
        val victim = File(d, "victim.bin").apply { writeBytes(ByteArray(8192)) }
        File(d, "keep.bin").writeBytes(ByteArray(10))
        assertTrue(victim.path.startsWith(d.path + "/") && victim.isAbsolute)
        val a = browse(d)
        assertEquals(0, a.deleteBlocking(index(a, "victim.bin")))
        assertFalse(victim.exists())
        assertTrue(File(d, "keep.bin").exists())
        assertTrue(waitFor { !a.busy && a.list.source != null })
        ins.runOnMainSync {
            assertTrue(a.footerText.toString(), a.footerText.endsWith(" · " + a.getString(R.string.log_link)))
            assertTrue(a.footer.isClickable)
            assertTrue(a.footer.performClick())
        }
        assertTrue("log not opened", waitFor { a.logSheet?.dialog?.isShowing == true })
        return a to a.logSheet!!
    }

    @Test fun deletedFileIsInTheLog() {
        val (a, s) = deleteOneAndOpenLog()
        ins.runOnMainSync {
            assertEquals(1, s.rows.size)
            val desc = s.rows[0].contentDescription.toString()
            assertTrue(desc, desc.contains("victim.bin"))
            // «удалено» is the default and is not printed: only the size.
            assertFalse(desc, desc.contains("⚠"))
            assertEquals(View.VISIBLE, s.clearButton.visibility)
            s.dismiss()
        }
        // On disk: one start and one end, same id.
        drainIo()
        val e = DeleteLogModel.entries(DeleteLogStore(log).lines()).single()
        assertEquals(LogOutcome.DELETED, e.outcome)
        assertEquals("victim.bin", String(e.start.names.single()))
        assertFalse(e.start.dir)
        assertEquals(dir!!.path, e.start.root)
        ins.runOnMainSync { a.finish() }
    }

    /** Clear: Cancel keeps the entries; an early double tap on «Очистить» is ignored; Confirm empties the log. */
    @Test fun clearAsksAndGuardsDoubleTap() {
        val (_, s) = deleteOneAndOpenLog()
        ins.runOnMainSync { assertTrue(s.clearButton.performClick()) }
        assertTrue(waitFor { s.confirm?.isShowing == true })
        ins.runOnMainSync {
            val c = s.confirm!!
            assertTrue(c.getButton(DialogInterface.BUTTON_NEGATIVE).isFocused)
            assertTrue(c.getButton(DialogInterface.BUTTON_NEGATIVE).performClick())
        }
        assertTrue(waitFor { s.confirm?.isShowing != true })
        ins.runOnMainSync { assertEquals(1, s.rows.size) }
        drainIo()
        assertEquals(1, DeleteLogModel.entries(DeleteLogStore(log).lines()).size)
        // Second time: a tap right after the dialog opens (the double tap's second half) does nothing.
        ins.runOnMainSync {
            assertTrue(s.clearButton.performClick())
            val ok = s.confirm!!.getButton(DialogInterface.BUTTON_POSITIVE)
            val t0 = SystemClock.uptimeMillis()
            ok.dispatchTouchEvent(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, 5f, 5f, 0))
            ok.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 30, MotionEvent.ACTION_UP, 5f, 5f, 0))
        }
        ins.waitForIdleSync()
        ins.runOnMainSync {
            assertEquals(1, s.guardedTaps)
            assertTrue(s.confirm!!.isShowing)
        }
        drainIo()
        assertEquals(1, DeleteLogModel.entries(DeleteLogStore(log).lines()).size)
        Thread.sleep(DeleteLogSheet.OPEN_GUARD_MS + 100)
        ins.runOnMainSync { assertTrue(s.confirm!!.getButton(DialogInterface.BUTTON_POSITIVE).performClick()) }
        assertTrue("not cleared", waitFor { s.emptyText?.text?.toString() == ctx.getString(R.string.log_cleared) })
        ins.runOnMainSync { assertEquals(View.GONE, s.clearButton.visibility); s.dismiss() }
        drainIo()
        assertTrue(DeleteLogStore(log).lines().isEmpty())
        assertFalse(File(log.path + ".tmp").exists())
    }

    /**
     * A start with no end (the hook writes one): main shows «⚠ прервано: Download/ · …» in the status
     * line; tapping it opens that folder and marks the notice seen (it does not come back).
     */
    @Test fun interruptedNoticeOpensTheFolder() {
        assumeTrue("no shared-storage cache to open", Scans.meta(ctx, Scans.STORAGE, false) != null)
        Perms.filesOverride = true
        BgScan.auto = false
        val mon = ins.addMonitor(BrowserActivity::class.java.name, null, false)
        try {
            val m = ins.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            opened += m
            ins.waitForIdleSync()
            ins.runOnMainSync { DeleteLog.testInterrupt(ctx, Scans.STORAGE, listOf("Download".toByteArray()), true, 10L shl 30) }
            assertTrue("no interrupted status", waitFor { m.storage.status is Status.Interrupted })
            ins.runOnMainSync {
                val text = m.storage.statusTxt.text.toString()
                assertTrue(text, text.startsWith(m.getString(R.string.status_interrupted, "", "").substringBefore(" ·").trimEnd()))
                assertTrue(text, text.contains("Download/"))
                assertTrue(m.storage.statusTxt.performClick())
            }
            val b = ins.waitForMonitorWithTimeout(mon, 10_000) as BrowserActivity?
            assertNotNull("browser not opened", b)
            opened += b!!
            assertTrue(waitFor { b.list.source != null })
            ins.runOnMainSync {
                assertTrue(b.currentPath, b.currentPath.trimEnd('/').endsWith("/Download"))
                assertNull(DeleteLog.notice)
            }
            // Seen is persisted: a fresh read finds no notice.
            drainIo()
            DeleteLog.init(ctx, force = true)
            drainIo()
            ins.runOnMainSync { assertNull(DeleteLog.notice) }
        } finally {
            ins.removeMonitor(mon)
            Perms.filesOverride = null
            BgScan.auto = true
        }
    }

    /** The ··· menu has «Журнал удалений»; with an empty log the sheet has no «Очистить…». */
    @Test fun menuOpensEmptyLog() {
        Perms.filesOverride = true
        BgScan.auto = false
        try {
            val m = ins.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            opened += m
            ins.waitForIdleSync()
            ins.runOnMainSync { m.menuButton.performClick() }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                val row = m.menu!!.rows.first { it.text.toString() == m.getString(R.string.log_title) }
                assertTrue(row.performClick())
            }
            assertTrue(waitFor { m.logSheet?.dialog?.isShowing == true })
            ins.runOnMainSync {
                val s = m.logSheet!!
                assertEquals(0, s.rows.size)
                assertEquals(m.getString(R.string.log_empty), s.emptyText!!.text.toString())
                assertEquals(View.GONE, s.clearButton.visibility)
                s.dismiss()
            }
        } finally {
            Perms.filesOverride = null
            BgScan.auto = true
        }
    }
}
