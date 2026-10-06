package dev.ancdu

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BrowserTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    @Test fun navigateSortDelete() {
        val ctx = ins.targetContext
        val dir = File(ctx.cacheDir, "br").apply { deleteRecursively(); mkdirs() }
        File(dir, "sub").mkdirs()
        File(dir, "sub/big.bin").writeBytes(ByteArray(300_000))
        File(dir, "a.txt").writeBytes(ByteArray(5000))
        File(dir, "b.txt").writeBytes(ByteArray(10))

        val err = IntArray(1)
        val h = Native.scanStart(dir.path, true, 2, err)
        val p = LongArray(6)
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            Native.progress(h, p)
            if (p[0] != ST_RUNNING.toLong() || System.currentTimeMillis() > deadline) break
            Thread.sleep(25)
        }
        assertEquals(ST_DONE.toLong(), p[0])
        Holder.set(h, Kind.SCAN, dir.path, "скан", false)

        val act = ins.startActivitySync(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        ins.waitForIdleSync()
        val row = Row()
        // Чтения дерева — только с главного потока (контракт Native).
        fun name(i: Int): String {
            var s = ""
            ins.runOnMainSync { row.reset(); act.list.source!!.bind(i, row); s = row.name }
            return s
        }
        fun count(): Int { var c = 0; ins.runOnMainSync { c = act.list.source!!.count }; return c }

        assertEquals(3, count())
        assertEquals("sub/", name(0))
        ins.runOnMainSync { act.list.source!!.click(0) }
        assertNotEquals(0, act.node)
        assertEquals(1, count())
        assertEquals("big.bin", name(0))
        ins.runOnMainSync { act.onBackPressed() }
        assertEquals(0, act.node)

        ins.runOnMainSync { act.setSort(SORT_NAME) }
        assertEquals("a.txt", name(0))
        val bIndex = (0 until 3).first { name(it) == "b.txt" }
        assertEquals(0, act.deleteBlocking(bIndex))
        assertFalse(File(dir, "b.txt").exists())
        assertEquals(2, count())

        ins.runOnMainSync { act.finish() }
        dir.deleteRecursively()
    }
}
