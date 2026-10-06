package dev.ancdu

import org.junit.Assert.assertEquals
import org.junit.Test

class ListMathTest {
    @Test fun scrolling() {
        assertEquals(0, ListMath.maxScroll(5, 100, 1000))
        assertEquals(1000, ListMath.maxScroll(20, 100, 1000))
        assertEquals(1000, ListMath.clampScroll(5000, 20, 100, 1000))
        assertEquals(0, ListMath.clampScroll(-3, 20, 100, 1000))
        assertEquals(3, ListMath.firstVisible(350, 100))
        assertEquals(13, ListMath.lastVisible(350, 100, 1000, 20))
        assertEquals(19, ListMath.lastVisible(1000, 100, 1000, 20))
        assertEquals(-1, ListMath.lastVisible(0, 100, 1000, 0))
    }

    @Test fun hitTesting() {
        assertEquals(3, ListMath.indexAt(50f, 300, 100, 20))
        assertEquals(-1, ListMath.indexAt(50f, 300, 100, 3))
        assertEquals(-1, ListMath.indexAt(-1f, 0, 100, 20))
    }

    @Test fun bars() {
        assertEquals(0f, ListMath.bar(5, 0))
        assertEquals(0.5f, ListMath.bar(50, 100))
        assertEquals(1f, ListMath.bar(500, 100))
    }
}
