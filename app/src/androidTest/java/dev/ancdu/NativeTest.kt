package dev.ancdu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "fx")
        dir.deleteRecursively()
        File(dir, "sub").mkdirs()
        File(dir, "sub/big.bin").writeBytes(ByteArray(300_000))
        File(dir, "🎉 party.txt").writeBytes(ByteArray(5000))
        File(dir, "new\nline").writeBytes(ByteArray(10))
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun waitDone(h: Long): Int {
        val p = LongArray(6)
        repeat(200) {
            Native.progress(h, p)
            if (p[0] != ST_RUNNING.toLong()) return p[0].toInt()
            Thread.sleep(25)
        }
        return -1
    }

    private fun childNames(h: Long, node: Int): List<String> {
        val out = IntArray(Native.childCount(h, node))
        val n = Native.children(h, node, SORT_SIZE, false, out)
        return (0 until n).map { Native.str(Native.name(h, out[it])) }
    }

    private fun firstChild(h: Long, node: Int): Int {
        val out = IntArray(Native.childCount(h, node))
        assertTrue(Native.children(h, node, SORT_SIZE, false, out) > 0)
        return out[0]
    }

    @Test fun scanBrowseDelete() {
        val err = IntArray(1)
        val h = Native.scanStart(dir.path, true, 2, err)
        assertNotEquals(0L, h)
        assertEquals(ST_DONE, waitDone(h))
        assertEquals(SRC_SCAN, Native.source(h))
        assertEquals(listOf("sub", "🎉 party.txt", "new\nline"), childNames(h, 0))
        val ln = IntArray(8); val ld = LongArray(8)
        val k = Native.liveTop(h, ln, ld)
        assertEquals(3, k)
        assertEquals(setOf("sub", "🎉 party.txt", "new\nline"), (0 until k).map { Native.str(Native.liveName(h, ln[it])) }.toSet())
        assertEquals(dir.path, Native.str(Native.path(h, 0)))

        val kids = IntArray(Native.childCount(h, 0))
        Native.children(h, 0, SORT_SIZE, false, kids)
        val info = LongArray(4 * kids.size)
        Native.nodeInfo(h, kids, kids.size, info)
        assertTrue(info[0] >= 300_000)                 // disk(sub)
        assertTrue(info[3].toInt() and F_DIR != 0)     // flags(sub)
        assertEquals(0, Native.parent(h, kids[0]))
        assertEquals(-1, Native.parent(h, 0))

        val party = kids[1]
        assertEquals(File(dir, "🎉 party.txt").path, Native.str(Native.path(h, party)))
        assertEquals(0, Native.delete(h, party, null))
        assertFalse(File(dir, "🎉 party.txt").exists())
        assertEquals(listOf("sub", "new\nline"), childNames(h, 0))

        // неверные узлы не роняют процесс
        assertEquals(0, Native.name(h, 9_999_999).size)
        assertEquals(0, Native.childCount(h, 9_999_999))
        assertEquals(-1, Native.parent(h, -5))
        Native.free(h)
    }

    @Test fun indexAndCache() {
        val err = IntArray(1)
        val h = Native.indexBegin("/storage/emulated/0", 1000, err)
        assertNotEquals(0L, h)
        val rel = "DCIM/Camera/\u0000Download/\u0000".toByteArray()
        val names = "🎉.jpg\u0000x.zip\u0000".toByteArray()
        assertEquals(0, Native.indexAdd(h, rel, names, longArrayOf(5000, 10), 2))
        assertEquals(0, Native.indexFinish(h))
        assertEquals(SRC_INDEX, Native.source(h))
        assertEquals(listOf("DCIM", "Download"), childNames(h, 0))

        val cache = File(dir, "c.ancdu").path
        assertEquals(0, Native.saveCache(h, cache))
        Native.free(h)
        val c = Native.openCache(cache, err)
        assertNotEquals(0L, c)
        assertEquals(ST_DONE, waitDone(c))
        val dcim = firstChild(c, 0)
        val cam = firstChild(c, dcim)
        val jpg = firstChild(c, cam)
        assertArrayEquals("🎉.jpg".toByteArray(), Native.name(c, jpg))
        Native.free(c)

        assertEquals(0L, Native.openCache(File(dir, "missing").path, err))
        assertTrue(err[0] < 0)
    }

    /**
     * Пути — байты UTF-8: корень скана и файл кэша с символом вне BMP (🎉 — суррогатная пара в
     * String; modified UTF-8 GetStringUTFChars дал бы 6 байт CESU-8 и «cannot open»).
     * Путь с \u0000 ядро отклоняет, не усекает.
     */
    @Test fun nonBmpPaths() {
        val root = File(dir, "🎉root").apply { assertTrue(mkdirs()) }
        File(root, "a.bin").writeBytes(ByteArray(100))
        val err = IntArray(1)
        val h = Native.scanStart(root.path, true, 2, err)
        assertNotEquals(0L, h)
        assertEquals(ST_DONE, waitDone(h))
        assertEquals(root.path, Native.str(Native.path(h, 0)))
        assertEquals(listOf("a.bin"), childNames(h, 0))
        val cache = File(root, "🎉.cache").path
        assertEquals(0, Native.saveCache(h, cache))
        Native.free(h)
        assertTrue(File(cache).exists())
        val c = Native.openCache(cache, err)
        assertNotEquals(0L, c)
        Native.free(c)
        assertEquals(0L, Native.scanStart(root.path + "\u0000x", true, 1, err))
        assertEquals(-22, err[0])                        // -EINVAL
        assertEquals(-22, Native.statfs("/data\u0000", LongArray(3)))
    }

    @Test fun statfsData() {
        val out = LongArray(3)
        assertEquals(0, Native.statfs("/data", out))
        assertTrue(out[0] > 0 && out[1] in 0..out[0])
    }
}
