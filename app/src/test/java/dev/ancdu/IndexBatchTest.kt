package dev.ancdu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexBatchTest {
    @Test fun encodesNulSeparatedUtf8() {
        val b = IndexBatch(capacity = 2)
        assertFalse(b.add("DCIM/Camera/", "🎉.jpg", 5000))
        assertTrue(b.add("", "x", 1))            // заполнен
        assertEquals(2, b.n)
        assertArrayEquals("DCIM/Camera/\u0000\u0000".toByteArray(), b.relBytes())
        assertArrayEquals("🎉.jpg\u0000x\u0000".toByteArray(Charsets.UTF_8), b.nameBytes())
        assertEquals(5000L, b.sizes[0])
        b.clear()
        assertEquals(0, b.n)
        assertEquals(0, b.relBytes().size)
    }
}
