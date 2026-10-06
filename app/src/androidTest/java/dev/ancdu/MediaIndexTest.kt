package dev.ancdu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaIndexTest {
    /** Работает при любых правах: без доступа индекс содержит лишь файлы приложения, но строится. */
    @Test fun buildsFinishedIndexTree() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val h = MediaIndex.build(ctx)
        try {
            assertNotEquals(0L, h)
            assertEquals(SRC_INDEX, Native.source(h))
            val p = LongArray(6)
            Native.progress(h, p)
            assertEquals(ST_DONE.toLong(), p[0])
            assertTrue(Native.childCount(h, 0) >= 0)
            // Живых итогов у индексных сессий нет.
            assertEquals(0, Native.liveTop(h, IntArray(8), LongArray(8)))
        } finally {
            Native.free(h)
        }
    }
}
