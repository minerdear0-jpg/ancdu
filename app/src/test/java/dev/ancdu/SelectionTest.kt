package dev.ancdu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Выбор в одной папке: ключи — байты имён, узлы — только для текущего дерева. */
class SelectionTest {
    private fun k(s: String) = NameKey(s.toByteArray())

    @Test fun nameKeysCompareBytes() {
        assertEquals(k("a.bin"), NameKey("a.bin".toByteArray()))
        assertEquals(k("a.bin").hashCode(), NameKey("a.bin".toByteArray()).hashCode())
        assertNotEquals(k("a.bin"), k("b.bin"))
        // Разные невалидные UTF-8 имена декодируются в одну строку с U+FFFD, а ключи различны.
        val x = NameKey(byteArrayOf(0x61, 0xFF.toByte()))
        val y = NameKey(byteArrayOf(0x61, 0xFE.toByte()))
        assertEquals(String(x.bytes, Charsets.UTF_8), String(y.bytes, Charsets.UTF_8))
        assertNotEquals(x, y)
    }

    @Test fun startToggleAndLastDeselectExits() {
        val s = Selection()
        assertFalse(s.active)
        s.start(k("a"), 10)
        assertTrue(s.active)
        assertEquals(1, s.count)
        assertTrue(s.contains(10))
        assertTrue(s.toggle(k("b"), 11))          // выбран
        assertEquals(2, s.count)
        assertFalse(s.toggle(k("a"), 10))         // снят
        assertTrue(s.active)
        assertFalse(s.contains(10))
        assertFalse(s.toggle(k("b"), 11))         // снят последний — режим выходит
        assertFalse(s.active)
        assertEquals(0, s.count)
    }

    @Test fun startReplacesOldSelection() {
        val s = Selection()
        s.start(k("a"), 1); s.toggle(k("b"), 2)
        s.start(k("c"), 3)
        assertEquals(listOf(k("c")), s.keys)
        assertArrayEquals(intArrayOf(3), s.nodes)
    }

    @Test fun allSkipsBlockedAndNoneLeaves() {
        val s = Selection()
        val rows = listOf(k("a") to 1, k("sys") to 2, k("c") to 3)
        s.start(k("a"), 1)
        s.selectAll(rows) { it == 2 }
        assertEquals(2, s.count)
        assertFalse(s.contains(2))
        assertTrue(s.isAll(rows) { it == 2 })
        assertFalse(s.isAll(rows) { false })      // без запретов выбрано не всё
        assertEquals(2, s.leave())                // «НИЧЕГО» — выход, число снятых
        assertFalse(s.active)
        assertEquals(0, s.leave())
        // Пустая папка выбираемых: «ВСЕ» не становится «НИЧЕГО» сама по себе.
        assertFalse(Selection().isAll(emptyList()) { false })
    }

    /** Новое дерево: те же имена — другие узлы; пропавшие выбрасываются, пусто — выход. */
    @Test fun rebindByNameDropsMissing() {
        val s = Selection()
        s.start(k("a"), 1); s.toggle(k("b"), 2); s.toggle(k("c"), 3)
        val fresh = mapOf(k("a") to 101, k("c") to 103)
        assertEquals(1, s.rebind { fresh[it] })
        assertEquals(listOf(k("a"), k("c")), s.keys)
        assertArrayEquals(intArrayOf(101, 103), s.nodes)
        assertTrue(s.contains(103))
        assertFalse(s.contains(3))
        assertEquals(2, s.rebind { null })
        assertFalse(s.active)
    }

    @Test fun nodeOfKey() {
        val s = Selection()
        s.start(k("a"), 7)
        assertEquals(7, s.nodeOf(k("a")))
        assertEquals(null, s.nodeOf(k("z")))
    }
}
